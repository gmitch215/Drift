package dev.gmitch215.drift.bench

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SolvesTest {
    private val dev = setOf(
        "right", "wrong", "bundle-min", "bundle-wide", "bundle-miss", "ctl-stuck", "ctl-false",
        "stuck-a", "stuck-b", "narrow-in", "narrow-out", "broken", "garbled", "unsolved",
    )

    private val truths = listOf(
        SolveTruth("right", "single", "env.LANG"),
        SolveTruth("wrong", "decoy", "env.LANG"),
        SolveTruth("bundle-min", "bundle", "tool.node.version"),
        SolveTruth("bundle-wide", "bundle", "os.release.ID"),
        SolveTruth("bundle-miss", "bundle", "env.TZ", listOf("tool.go.version")),
        SolveTruth("ctl-stuck", "control", null),
        SolveTruth("ctl-false", "control", null),
        SolveTruth("stuck-a", "single", "env.LANG"),
        SolveTruth("stuck-b", "intermittent", "env.LANG"),
        SolveTruth("narrow-in", "single", "env.LANG"),
        SolveTruth("narrow-out", "single", "env.LANG"),
        SolveTruth("broken", "single", "env.LANG"),
        SolveTruth("garbled", "single", "env.LANG"),
        SolveTruth("unsolved", "single", "env.LANG"),
    )

    private fun cert(
        kind: String,
        minimal: List<String> = emptyList(),
        members: List<String> = minimal,
        candidates: List<String> = emptyList(),
        necessary: List<String> = emptyList(),
        reason: String? = null,
        statement: String = "",
        trials: List<Int> = listOf(8, 7),
    ): String {
        fun list(v: List<String>) = v.joinToString(",", "[", "]") { "\"$it\"" }
        val why = reason?.let { "\"$it\"" } ?: "null"
        val arms = trials.joinToString(",") {
            "{\"control\":{\"trials\":$it},\"treatment\":{\"trials\":$it}}"
        }
        val removed = if (necessary.isEmpty()) "" else "\"necessary\":${list(necessary)},"
        return "{\"experiments\":[$arms],\"verdict\":{\"candidates\":${list(candidates)}," +
            removed +
            "\"kind\":\"$kind\",\"members\":${list(members)},\"minimalSet\":${list(minimal)}," +
            "\"reason\":$why,\"statement\":\"$statement\"}}"
    }

    private val cases = listOf(
        SolvedCase("right", cert("confirmed", listOf("env.LANG"))),
        SolvedCase("wrong", cert("confirmed", listOf("env.TZ"))),
        SolvedCase(
            "bundle-min",
            cert(
                "confirmed-bundle",
                listOf("tool.node.version"),
                listOf("env.NODE_VERSION", "tool.node.version"),
            ),
        ),
        SolvedCase(
            "bundle-wide",
            cert("confirmed-bundle", listOf("os.release.PRETTY_NAME"), listOf("os.release.ID")),
        ),
        SolvedCase("bundle-miss", cert("confirmed-bundle", listOf("tool.go.version"))),
        SolvedCase(
            "ctl-stuck",
            cert(
                "stuck",
                reason = "not-reproduced",
                statement = "the failure does not reproduce in the container: 0 of 30 trials",
                trials = listOf(30),
            ),
        ),
        SolvedCase("ctl-false", cert("confirmed", listOf("env.LANG"))),
        SolvedCase(
            "stuck-a",
            cert(
                "stuck",
                reason = "not-reproduced",
                statement = "the failure does not reproduce in the container: 0 of 30 trials",
                trials = listOf(30),
            ),
        ),
        SolvedCase(
            "stuck-b",
            cert(
                "stuck",
                reason = "executor",
                statement = "experiment x1 is void: the treatment capsule does not show it",
                trials = listOf(8, 7, 7),
            ),
        ),
        SolvedCase(
            "narrow-in",
            cert("narrowed", listOf("env.LANG"), candidates = listOf("env.LANG")),
        ),
        SolvedCase("narrow-out", cert("narrowed", listOf("env.TZ"), candidates = listOf("env.TZ"))),
        SolvedCase("broken", null),
        SolvedCase("garbled", cert("confirmed-effect", listOf("env.LANG"))),
    )

    private val score = SolveBench.score(dev, truths, cases)

    private fun outcomeOf(id: String) = score.cases.first { it.id == id }.outcome

    private fun parse(text: String): JsonObject = CanonicalJson.parse(text) as JsonObject

    private fun field(v: JsonValue, vararg path: String): JsonValue =
        path.fold(v) { acc, key -> (acc as JsonObject).fields.getValue(key) }

    private fun count(v: JsonValue, vararg path: String) = (field(v, *path) as JsonInt).value

    @Test
    fun aConfirmedVerdictIsCorrectOnlyWhenItNamesTheInjectedCause() {
        assertEquals(SolveOutcome.CONFIRMED_CORRECT, outcomeOf("right"))
        assertEquals(SolveOutcome.CONFIRMED_WRONG, outcomeOf("wrong"))
    }

    @Test
    fun aBundleHoldsTheCauseWhenAMemberIsItAndMinimalWhenTheMinimalSetNamesIt() {
        assertEquals(SolveOutcome.BUNDLE_HOLDS, outcomeOf("bundle-min"))
        assertTrue(score.cases.first { it.id == "bundle-min" }.minimalHolds)
        assertEquals(SolveOutcome.BUNDLE_HOLDS, outcomeOf("bundle-wide"))
        assertTrue(!score.cases.first { it.id == "bundle-wide" }.minimalHolds)
        assertEquals(SolveOutcome.BUNDLE_MISSES, outcomeOf("bundle-miss"))
        assertTrue(score.cases.first { it.id == "bundle-miss" }.declaredHeld)
    }

    @Test
    fun aBundleNamesTheCauseWhenARemovalShowedTheMemberIsNeeded() {
        val truth = SolveTruth("flavor", "bundle", "tool.coreutils.flavor")
        val needed = cert(
            "confirmed-bundle",
            listOf("os.release.ID"),
            listOf("os.release.ID", "tool.coreutils.flavor"),
            necessary = listOf("tool.coreutils.flavor"),
        )
        val plain = cert(
            "confirmed-bundle",
            listOf("os.release.ID"),
            listOf("os.release.ID", "tool.coreutils.flavor"),
        )
        fun scored(text: String) = SolveBench.score(
            setOf("flavor"),
            listOf(truth),
            listOf(SolvedCase("flavor", text)),
        )
        val with = scored(needed)
        val without = scored(plain)
        assertTrue(with.cases.single().minimalHolds)
        assertEquals(SolveOutcome.BUNDLE_HOLDS, with.cases.single().outcome)
        assertTrue(!without.cases.single().minimalHolds)
        assertTrue("\"necessary\":[\"tool.coreutils.flavor\"]" in with.toJson())
        assertTrue("necessary" !in without.toJson())
    }

    @Test
    fun aNeedsVerdictNamesNoCauseAndIsCountedApartFromGenuineStuck() {
        val needs = cert(
            "needs",
            reason = "not-reproduced",
            statement = "0 of 30",
            trials = listOf(30),
        )
        val genuine = cert("stuck", reason = "budget", statement = "no arm", trials = listOf(30))
        val truths = listOf(
            SolveTruth("ctl", "control", null),
            SolveTruth("single", "single", "env.LANG"),
            SolveTruth("other", "single", "env.LANG"),
        )
        val scored = SolveBench.score(
            setOf("ctl", "single", "other"),
            truths,
            listOf(
                SolvedCase("ctl", needs),
                SolvedCase("single", needs),
                SolvedCase("other", genuine),
            ),
        )
        assertEquals(SolveOutcome.CONTROL_STUCK, scored.cases.first { it.id == "ctl" }.outcome)
        assertEquals(SolveOutcome.STUCK, scored.cases.first { it.id == "single" }.outcome)
        val all = field(parse(scored.toJson()), "classes", "all")
        assertEquals(2, count(all, "needs"))
        assertEquals(1, count(all, "genuine_stuck"))
        assertEquals(2, count(parse(scored.toJson()), "trials", "needs", "cases"))
        assertTrue("NEEDS 2, genuine STUCK 1" in scored.report("x"))
        assertTrue("\"needs\"" !in score.toJson())
        assertTrue("genuine_stuck" !in score.toJson())
    }

    @Test
    fun aControlIsCorrectWhenStuckAndAnyVerdictOnItIsAFalseConfirmation() {
        assertEquals(SolveOutcome.CONTROL_STUCK, outcomeOf("ctl-stuck"))
        assertEquals(SolveOutcome.CONTROL_FALSE, outcomeOf("ctl-false"))
    }

    @Test
    fun aStuckCauseCaseIsStuckAndANarrowedOneHoldsOrMissesTheCause() {
        assertEquals(SolveOutcome.STUCK, outcomeOf("stuck-a"))
        assertEquals(SolveOutcome.STUCK, outcomeOf("stuck-b"))
        assertEquals(SolveOutcome.NARROWED_HOLDS, outcomeOf("narrow-in"))
        assertEquals(SolveOutcome.NARROWED_MISSES, outcomeOf("narrow-out"))
    }

    @Test
    fun aMissingOrUnknownCertificateIsUnreadableAndNotAnError() {
        assertEquals(SolveOutcome.UNREADABLE, outcomeOf("broken"))
        assertEquals(SolveOutcome.UNREADABLE, outcomeOf("garbled"))
        val junk = SolveBench.score(
            dev,
            truths,
            listOf(
                SolvedCase("right", "not json"),
                SolvedCase("wrong", "[]"),
                SolvedCase("stuck-a", "{\"verdict\":{\"kind\":\"confirmed\",\"minimalSet\":[]}}"),
            ),
        )
        assertEquals(3, junk.cases.count { it.outcome == SolveOutcome.UNREADABLE })
    }

    @Test
    fun aTestIdIsRefusedAndAnIdInTwoDirectoriesIsAnError() {
        val sealed = truths + SolveTruth("sealed", "single", "env.TZ")
        val e = assertFailsWith<SealedException> {
            SolveBench.score(dev, sealed, listOf(SolvedCase("sealed", null)))
        }
        assertTrue(e.message.orEmpty().contains("sealed"))
        val twice = listOf(SolvedCase("right", null), SolvedCase("right", null))
        assertFailsWith<IllegalArgumentException> { SolveBench.score(dev, truths, twice) }
        assertFailsWith<IllegalArgumentException> {
            SolveBench.score(setOf("nolabel"), truths, listOf(SolvedCase("nolabel", null)))
        }
    }

    @Test
    fun theRatesCountEveryDenominatorAndCarryAWilsonInterval() {
        val json = parse(score.toJson())
        assertEquals(13, count(json, "scenarios"))
        assertEquals(listOf("unsolved"), score.notSolved)
        val all = field(json, "classes", "all")
        assertEquals(1, count(all, "confirmed_correct"))
        assertEquals(1, count(all, "confirmed_wrong"))
        assertEquals(2, count(all, "bundle_holds"))
        assertEquals(1, count(all, "bundle_minimal_names_cause"))
        assertEquals(1, count(all, "bundle_misses"))
        assertEquals(1, count(all, "bundle_misses_with_declared_member"))
        assertEquals(1, count(all, "control_false"))
        assertEquals(1, count(all, "control_stuck"))
        assertEquals(2, count(all, "stuck"))
        assertEquals(1, count(all, "narrowed_holds"))
        assertEquals(2, count(all, "unreadable"))
        // confirmed verdicts: right, wrong and the control's false one
        assertEquals(3, count(all, "confirmed_precision", "total"))
        assertEquals(1, count(all, "confirmed_precision", "count"))
        assertEquals(11, count(all, "resolution", "total"))
        assertEquals(2, count(all, "resolution", "count"))
        val w = Metrics.wilson(1, 3)
        assertEquals(w.low, count(all, "confirmed_precision", "low"))
        assertEquals(w.high, count(all, "confirmed_precision", "high"))
        assertEquals(1, count(json, "classes", "control", "control_correct", "count"))
        assertEquals(2, count(json, "classes", "control", "control_correct", "total"))
        assertEquals(0, count(json, "classes", "decoy", "bundle_holds"))
    }

    @Test
    fun trialsAreTheSumOfTheExperimentArmsWithAMedianAndARange() {
        val json = parse(score.toJson())
        val all = field(json, "trials", "all")
        assertEquals(11, count(all, "cases"))
        assertEquals(30, count(all, "median"))
        assertEquals(30, count(all, "min"))
        assertEquals(60, count(all, "max"))
        assertEquals(3, count(json, "trials", "stuck", "cases"))
        assertEquals(60, count(json, "trials", "stuck", "median"))
        assertEquals(44, count(json, "trials", "stuck", "min"))
        assertEquals(2, count(json, "trials", "narrowed", "cases"))
    }

    @Test
    fun stuckCasesAreCountedByReasonAndByTheFirstClauseOfTheStatement() {
        val json = parse(score.toJson())
        assertEquals(2, count(json, "stuck_by_reason", "not-reproduced"))
        assertEquals(1, count(json, "stuck_by_reason", "executor"))
        val head = "the failure does not reproduce in the container"
        assertEquals(2, count(json, "stuck_by_statement", head))
        assertEquals(1, count(json, "stuck_by_statement", "experiment x1 is void"))
        assertEquals(2, count(json, "stuck_by_cause", "env.LANG"))
        assertEquals(1, (field(json, "stuck_by_cause") as JsonObject).fields.size)
    }

    @Test
    fun theIdListsAndTheCaseTableAgreeAndTheOutputIsStable() {
        val json = parse(score.toJson())
        val ids = (field(json, "ids", "confirmed-wrong") as JsonArray).items
        assertEquals(listOf<JsonValue>(JsonString("wrong")), ids)
        val rows = (field(json, "cases") as JsonArray).items.map { (it as JsonObject).fields }
        assertEquals(rows.map { (it["id"] as JsonString).value }, score.cases.map { it.id })
        assertEquals(JsonNull, rows.first { (it["id"] as JsonString).value == "broken" }["kind"])
        assertEquals(score.toJson(), SolveBench.score(dev, truths, cases.reversed()).toJson())
        assertTrue(score.report("dev").contains("CONFIRMED precision 33.3% (1 of 3"))
    }

    private val evaluation = """
        {"probed":{"scenarios":[
          {"id":"a","cause":"env.LANG","changed":["env.LANG","os.release.ID"],
           "ranked":[{"cause":false},{"cause":true}]},
          {"id":"b","cause":"env.TZ","changed":["env.LANG"],"ranked":[{"cause":false}]},
          {"id":"c","cause":null,"changed":[],"ranked":[]}]}}
    """.trimIndent()

    @Test
    fun theRankerAndPriorRanksComeFromTheEvaluationFile() {
        val ranks = SolveBench.ranks(evaluation)
        assertEquals(2, ranks.getValue("a").ranker)
        assertEquals(2, ranks.getValue("a").prior)
        assertEquals(null, ranks.getValue("b").ranker)
        assertEquals(null, ranks.getValue("b").prior)
        assertEquals(null, ranks.getValue("c").prior)
    }

    @Test
    fun theComparisonScoresTheSolverNextToTheRankerOnTheSameScenarios() {
        val t = listOf(SolveTruth("a", "single", "env.LANG"), SolveTruth("b", "single", "env.TZ"))
        val c = listOf(
            SolvedCase("a", cert("confirmed", listOf("env.LANG"))),
            SolvedCase("b", cert("stuck", reason = "budget")),
        )
        val scored = SolveBench.score(setOf("a", "b"), t, c, SolveBench.ranks(evaluation))
        val json = parse(scored.toJson())
        val cmp = field(json, "classes", "all", "comparison")
        assertEquals(2, count(cmp, "scenarios"))
        assertEquals(1, count(cmp, "solver_confirmed", "count"))
        assertEquals(0, count(cmp, "ranker_top1", "count"))
        assertEquals(1, count(cmp, "ranker_top3", "count"))
        assertEquals(1, count(cmp, "prior_top3", "count"))
    }
}
