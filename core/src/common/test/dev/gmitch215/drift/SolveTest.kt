package dev.gmitch215.drift

import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.fixtures.LabFixtures
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.lab.Budget
import dev.gmitch215.drift.lab.FailWhen
import dev.gmitch215.drift.lab.FakeExecutor
import dev.gmitch215.drift.lab.Outcome
import dev.gmitch215.drift.lab.PilotRule
import dev.gmitch215.drift.lab.RawTrial
import dev.gmitch215.drift.lab.RunConfig
import dev.gmitch215.drift.lab.SolveCertificate
import dev.gmitch215.drift.lab.SolveOptions
import dev.gmitch215.drift.lab.SolveResult
import dev.gmitch215.drift.lab.Solver
import dev.gmitch215.drift.lab.StuckWhy
import dev.gmitch215.drift.lab.TrialSchedule
import dev.gmitch215.drift.lab.VerdictKind
import dev.gmitch215.drift.lab.Verify
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.plan.Belief
import dev.gmitch215.drift.plan.Decision
import dev.gmitch215.drift.plan.Frame
import dev.gmitch215.drift.plan.Hypothesis
import dev.gmitch215.drift.plan.Options
import dev.gmitch215.drift.plan.PlanWeights
import dev.gmitch215.drift.plan.Planner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SolveTest {
    private val green = Capsule.parse(LabFixtures.text("node.json"))

    private fun with(c: Capsule, label: String, vararg set: Pair<String, String>): Capsule {
        val map = set.toMap()
        val kept = c.attributes.filter { it.path !in map }
        val added = map.map { (k, v) -> Attribute(k, v, "test") }
        return Capsule(label, (kept + added).sortedBy { it.path }, c.probes)
    }

    private val tz = "America/New_York"
    private val nofile = "1024:1024"

    private fun red(vararg extra: Pair<String, String>) = with(
        green,
        "lab-node-red",
        "env.TZ" to tz,
        "env.LANG" to "en_US.UTF-8",
        "cgroup.memory.max" to "268435456",
        "limits.nofile" to nofile,
        *extra,
    )

    private fun has(arm: RunConfig, path: String, value: String) =
        arm.capsule.attributes.any { it.path == path && it.value == value }

    private val culprit = { arm: RunConfig -> has(arm, "env.TZ", tz) }

    private fun trial(fail: Boolean, seconds: Long = 2) =
        RawTrial(if (fail) 1 else 0, if (fail) "boom: wrong day" else "ok", seconds)

    private fun failsWhen(cause: (RunConfig) -> Boolean, rate: Int = 4) =
        { arm: RunConfig, i: Int -> trial(cause(arm) && (rate == 4 || i % 4 < rate)) }

    private val options = SolveOptions(
        command = "sh run-test.sh",
        failWhen = FailWhen.parse("wrong day"),
        budget = Budget(400, 60),
        name = "fake",
        pilot = 6,
    )

    private fun solve(
        red: Capsule = red(),
        o: SolveOptions = options,
        files: MemoryCaseFiles = MemoryCaseFiles(),
        executor: FakeExecutor = FakeExecutor(behave = failsWhen(culprit)),
    ): SolveResult = Solver(executor, files, "case", o).solve(green, red)

    // #region outcomes

    @Test
    fun anIsolatedCauseIsConfirmedWithTheMinimalSetSearchedAndTheReverseArmRun() {
        val r = solve()
        val v = r.verdict
        assertEquals(VerdictKind.CONFIRMED, v.kind, v.statement)
        assertEquals(listOf("env.TZ"), v.set)
        assertEquals(listOf("env.TZ"), v.members)
        assertTrue(v.notIsolated.isEmpty())
        val kinds = r.runs.map { it.kind }
        assertTrue("pilot" in kinds && "forward" in kinds && "reverse" in kinds, "$kinds")
        val forward = r.runs.first { it.kind == "forward" && it.outcome == Outcome.SUPPORTED }
        assertTrue("env.TZ" in forward.flips)
        val reverse = r.runs.first { it.kind == "reverse" }
        assertEquals(Outcome.SUPPORTED, reverse.outcome)
        assertEquals(0, reverse.treatment.failures)
        assertTrue(reverse.control.failures > 0)
        assertTrue(r.trials <= options.budget.maxTrials)
        assertEquals(r.runs.sumOf { it.control.trials.size + it.treatment.trials.size }, r.trials)
    }

    @Test
    fun aDifferentKotlinPlatformIsConfirmedAsTheDeclaredPlatform() {
        val wasm = with(green, "wasm-red", "runtime.platform" to "wasm")
        val r = solve(
            red = wasm,
            executor = FakeExecutor(behave = failsWhen({ has(it, "runtime.platform", "wasm") })),
        )
        assertEquals(VerdictKind.CONFIRMED, r.verdict.kind, r.verdict.statement)
        assertEquals(listOf("runtime.platform"), r.verdict.set)
        assertTrue(r.runs.first { it.kind == "forward" }.treatment.failures > 0)
    }

    @Test
    fun theCertificateRecordsEveryConditionOfConfirmed() {
        val cert = SolveCertificate.certificate(solve().case)!!
        val conditions = cert.fields.getValue("conditions") as JsonObject
        val text = CanonicalJson.encode(conditions)
        assertTrue("\"confirmed\":true" in text)
        assertTrue("\"kind\":\"reverse\"" in text)
        assertTrue("\"state\":\"unexplained\"" in text, "the fake capture has no rule or probe")
        val honesty = CanonicalJson.encode(cert.fields.getValue("honesty"))
        assertTrue("not on the original host" in honesty)
        assertTrue("A bundle is never one cause" in honesty)
        assertTrue("uncalibrated" in honesty)
    }

    @Test
    fun aConjunctionOfTwoIsConfirmedAsAConjunction() {
        val both = { arm: RunConfig ->
            has(arm, "env.TZ", tz) && has(arm, "cgroup.memory.max", "268435456")
        }
        val r = solve(executor = FakeExecutor(behave = failsWhen(both)))
        assertEquals(VerdictKind.CONFIRMED, r.verdict.kind, r.verdict.statement)
        assertEquals(listOf("cgroup.memory.max", "env.TZ"), r.verdict.set.sorted())
        assertTrue("conjunction of 2" in r.verdict.statement)
    }

    @Test
    fun theMinimalSetSearchDropsTheDecoysAroundTheCause() {
        val r = solve()
        val minimal = r.runs.filter { it.kind == "minimal" }
        assertTrue(minimal.isNotEmpty(), "the full flip held four dimensions, so ddmin ran")
        assertTrue(minimal.all { it.flips.size < 4 })
        assertTrue(r.runs.first { it.kind == "forward" }.flips.size >= 2)
    }

    @Test
    fun anEffectOfAnImageSwapIsAConfirmedBundleNeverOneAttribute() {
        val cause = { arm: RunConfig -> has(arm, "tool.node.version", "24.1.0") }
        val capture = { arm: RunConfig ->
            val swapped = has(arm, "tool.node.version", "24.1.0")
            if (swapped) {
                with(
                    arm.capsule,
                    arm.label,
                    "os.release.PRETTY_NAME" to "Debian GNU/Linux 13 (trixie)",
                    "tool.libc.version" to "2.41",
                )
            } else {
                arm.capsule
            }
        }
        val r = solve(
            red = with(green, "lab-node-red", "tool.node.version" to "24.1.0"),
            executor = FakeExecutor(capsules = capture, behave = failsWhen(cause)),
        )
        assertEquals(VerdictKind.BUNDLE, r.verdict.kind, r.verdict.statement)
        assertTrue("tool.node.version" in r.verdict.members)
        assertTrue("tool.libc.version" in r.verdict.members)
        assertTrue("tool.libc.version" in r.verdict.notIsolated)
        assertTrue("not one member's" in r.verdict.statement)
        assertTrue("bundle-splitting arm" in r.verdict.statement, r.verdict.statement)
        assertEquals("measured", r.runs.first { it.kind == "forward" }.diff!!.source)
    }

    @Test
    fun aCauseOutsideTheBundleIsNeverConfirmedOnADecoy() {
        val cause = { arm: RunConfig -> has(arm, "kernel.release", "6.8.0-new") }
        val r = solve(
            red = red("kernel.release" to "6.8.0-new"),
            executor = FakeExecutor(behave = failsWhen(cause)),
        )
        assertEquals(VerdictKind.STUCK, r.verdict.kind, r.verdict.statement)
        assertEquals(StuckWhy.UNCONTROLLABLE, r.verdict.why)
        assertTrue("kernel.release" in r.verdict.statement)
        assertTrue(r.runs.none { it.kind == "reverse" })
    }

    @Test
    fun aFalseSupportedOnADecoyIsStoppedByTheReverseArm() {
        // the treatment arm fails whatever is set, so the reverse arm cannot agree
        val onlyTreatment = { arm: RunConfig, i: Int ->
            trial(arm.interventions.isNotEmpty() && i % 4 != 0)
        }
        val r = solve(executor = FakeExecutor(behave = onlyTreatment))
        assertNotEquals(VerdictKind.CONFIRMED, r.verdict.kind, r.verdict.statement)
        assertNotEquals(VerdictKind.BUNDLE, r.verdict.kind)
    }

    @Test
    fun noReproductionInTheFailingContainerIsStuck() {
        val r = solve(executor = FakeExecutor(behave = { _, _ -> trial(false) }))
        assertEquals(VerdictKind.STUCK, r.verdict.kind)
        assertEquals(StuckWhy.NOT_REPRODUCED, r.verdict.why)
        assertEquals(2 * 30, r.trials)
        assertTrue("0 of 30" in r.verdict.statement)
    }

    @Test
    fun aPassingContainerThatFailsAsOftenIsStuck() {
        val r = solve(executor = FakeExecutor(behave = { _, _ -> trial(true) }))
        assertEquals(StuckWhy.NOT_REPRODUCED, r.verdict.why)
        assertTrue("fails as often" in r.verdict.statement)
    }

    @Test
    fun aFailureNoControllableDifferenceReproducesIsStuckWithTheRefutedCandidates() {
        val failingBaselineOnly = { arm: RunConfig, _: Int ->
            trial(arm.label == "failing baseline")
        }
        val r = solve(executor = FakeExecutor(behave = failingBaselineOnly))
        assertEquals(VerdictKind.STUCK, r.verdict.kind, r.verdict.statement)
        assertEquals(StuckWhy.NOT_REPRODUCED, r.verdict.why)
        assertTrue(r.runs.any { it.kind == "forward" && it.outcome == Outcome.REFUTED })
        assertTrue("outside what the capsules" in r.verdict.statement)
    }

    @Test
    fun aBudgetThatCannotSizeAnArmIsStuckWithTheShortfall() {
        val r = solve(o = options.copy(budget = Budget(18, 60)))
        assertEquals(StuckWhy.BUDGET, r.verdict.why, r.verdict.statement)
        assertTrue("short by" in r.verdict.statement, r.verdict.statement)
        assertTrue(r.trials <= 18)
    }

    @Test
    fun theBudgetIsNeverExceeded() {
        for (maxTrials in listOf(0, 6, 12, 13, 30, 31, 45, 80)) {
            val r = solve(o = options.copy(budget = Budget(maxTrials, 60)))
            assertTrue(r.trials <= maxTrials, "$maxTrials -> ${r.trials}")
            if (r.verdict.kind == VerdictKind.CONFIRMED) assertTrue(maxTrials >= 30)
        }
        val seconds = solve(o = options.copy(budget = Budget(400, 1)))
        assertEquals(StuckWhy.BUDGET, seconds.verdict.why)
        assertTrue(seconds.seconds <= 60 + 2, "wall time stays within a minute: ${seconds.seconds}")
    }

    @Test
    fun theWallTimeBudgetStopsAfterTheTrialThatCrossedIt() {
        val slow = FakeExecutor(behave = { arm, _ -> trial(culprit(arm), seconds = 31) })
        val r = solve(executor = slow, o = options.copy(budget = Budget(400, 5)))
        assertEquals(StuckWhy.BUDGET, r.verdict.why, r.verdict.statement)
        assertTrue(r.seconds in 300..340, "${r.seconds}")
    }

    @Test
    fun anExecutorThatCannotBuildOrRunIsAnExecutorProblemNeverAVerdictAboutTheCause() {
        val build = FakeExecutor(problem = { "docker is not installed" }) { _, _ -> trial(false) }
        val a = solve(executor = build)
        assertEquals(StuckWhy.EXECUTOR, a.verdict.why)
        assertTrue("docker is not installed" in a.verdict.statement)
        val infra = FakeExecutor { _, _ -> RawTrial(125, "", 1, infra = "docker daemon error") }
        val b = solve(executor = infra)
        assertEquals(StuckWhy.EXECUTOR, b.verdict.why)
        assertTrue("docker daemon error" in b.verdict.statement)
    }

    @Test
    fun aSingleInfraGlitchIsRetriedOnce() {
        var glitched = false
        val behave = { arm: RunConfig, i: Int ->
            if (!glitched && i == 1) {
                glitched = true
                RawTrial(125, "", 1, infra = "daemon busy")
            } else {
                trial(culprit(arm))
            }
        }
        val r = solve(executor = FakeExecutor(behave = behave))
        assertEquals(VerdictKind.CONFIRMED, r.verdict.kind, r.verdict.statement)
    }

    @Test
    fun noDifferenceTheCapsulesCanShowIsNoCandidates() {
        val g = green
        val r = Solver(
            FakeExecutor { arm, _ -> trial(arm.label == "failing baseline") },
            MemoryCaseFiles(),
            "case",
            options,
        ).solve(g, with(g, "same"))
        assertEquals(StuckWhy.NO_CANDIDATES, r.verdict.why, r.verdict.statement)
    }

    @Test
    fun aReverseArmThatDoesNotAgreeLeavesTheResultNarrowed() {
        val hysteresis = { arm: RunConfig, _: Int ->
            trial(culprit(arm) || arm.interventions.isNotEmpty())
        }
        val r = solve(executor = FakeExecutor(behave = hysteresis))
        assertEquals(VerdictKind.NARROWED, r.verdict.kind, r.verdict.statement)
        assertTrue("not confirmed" in r.verdict.statement)
        assertNotNull(r.verdict.next)
    }

    @Test
    fun anInconclusiveExperimentIsNarrowedWithTheNextStep() {
        val behave = { arm: RunConfig, i: Int ->
            when {
                arm.label == "failing baseline" -> trial(true)
                arm.interventions.isEmpty() -> trial(i == 0)
                else -> trial(culprit(arm) && i % 3 != 2)
            }
        }
        val r = solve(executor = FakeExecutor(behave = behave))
        assertEquals(VerdictKind.NARROWED, r.verdict.kind, r.verdict.statement)
        assertTrue(r.verdict.candidates.isNotEmpty())
        assertNotNull(r.verdict.next)
    }

    // #endregion

    // #region isolation

    private fun conditions(r: SolveResult) =
        SolveCertificate.certificate(r.case)!!.fields.getValue("conditions") as JsonObject

    @Test
    fun anExecutorThatReturnsNoCapsuleConfirmsTheEffectButNotTheIsolation() {
        val r = solve(executor = FakeExecutor(capsules = { null }, behave = failsWhen(culprit)))
        assertEquals("confirmed-effect", r.verdict.kind.id, r.verdict.statement)
        assertTrue("isolation not measured" in r.verdict.statement, r.verdict.statement)
        assertEquals(listOf("env.TZ"), r.verdict.set)
        assertTrue(r.verdict.notIsolated.isNotEmpty())
        assertEquals(JsonString("predicted"), conditions(r).fields["isolation"])
        assertEquals(JsonNull, conditions(r).fields["isolatedAmong"])
        assertTrue(r.runs.filter { it.kind != "pilot" }.all { it.diff!!.source == "predicted" })
    }

    @Test
    fun aMeasuredConfirmationStatesTheCapturedAttributesItHoldsOver() {
        val r = solve()
        assertEquals(VerdictKind.CONFIRMED, r.verdict.kind, r.verdict.statement)
        val c = conditions(r)
        assertEquals(JsonString("measured"), c.fields["isolation"])
        val among = (c.fields["isolatedAmong"] as JsonInt).value
        val forward = r.runs.first { it.kind == "forward" }
        assertEquals((forward.diff!!.toJson().fields["compared"] as JsonInt).value, among)
        assertTrue(among >= red().attributes.size, "$among")
    }

    @Test
    fun aStaleCapsuleFromAnotherRunVoidsTheExperimentInsteadOfConfirming() {
        var first: Capsule? = null
        val stale = { arm: RunConfig ->
            val seen = first ?: arm.capsule
            first = seen
            if (arm.interventions.isEmpty()) arm.capsule else seen
        }
        val r = solve(executor = FakeExecutor(capsules = stale, behave = failsWhen(culprit)))
        assertNotEquals(VerdictKind.CONFIRMED, r.verdict.kind, r.verdict.statement)
        assertEquals(VerdictKind.STUCK, r.verdict.kind, r.verdict.statement)
        assertEquals(StuckWhy.EXECUTOR, r.verdict.why)
        assertTrue("is void" in r.verdict.statement, r.verdict.statement)
        assertEquals("void", r.runs.last().diff!!.source)
        assertTrue(r.runs.none { it.kind == "reverse" })
    }

    private val wrongZone = { arm: RunConfig ->
        val zone = "Europe/Paris"
        if (has(arm, "env.TZ", tz)) with(arm.capsule, arm.label, "env.TZ" to zone) else arm.capsule
    }

    @Test
    fun aCapsuleWhoseValueDiffersFromTheRequestVoidsTheExperiment() {
        val r = solve(executor = FakeExecutor(capsules = wrongZone, behave = failsWhen(culprit)))
        assertNotEquals(VerdictKind.CONFIRMED, r.verdict.kind, r.verdict.statement)
        assertEquals(StuckWhy.EXECUTOR, r.verdict.why, r.verdict.statement)
        assertTrue("env.TZ" in r.verdict.statement, r.verdict.statement)
        val void = r.runs.last()
        assertEquals("void", void.diff!!.source)
        assertEquals(
            JsonArray(listOf(JsonString("env.TZ"))),
            void.diff.toJson().fields["unreached"],
        )
    }

    @Test
    fun aVoidExperimentNeitherSupportsNorRulesOutACandidate() {
        val r = solve(executor = FakeExecutor(capsules = wrongZone, behave = failsWhen(culprit)))
        val cert = SolveCertificate.certificate(r.case)!!
        val posteriors = (cert.fields.getValue("posteriors") as JsonArray).items
        val void = r.runs.last()
        assertEquals("void", void.diff!!.source)
        val steps = posteriors.map { ((it as JsonObject).fields["after"] as JsonString).value }
        assertTrue(steps.none { it.startsWith("${void.id} ") }, "$steps")
        assertTrue(Verify.run(r.case).ok, Verify.run(r.case).text())
    }

    @Test
    fun anExtraChangedAttributeMakesTheEffectABundleNeverOneCause() {
        val extra = { arm: RunConfig ->
            if (has(arm, "env.TZ", tz)) {
                with(arm.capsule, arm.label, "kernel.release" to "6.8.0-new")
            } else {
                arm.capsule
            }
        }
        val r = solve(executor = FakeExecutor(capsules = extra, behave = failsWhen(culprit)))
        assertEquals(VerdictKind.BUNDLE, r.verdict.kind, r.verdict.statement)
        assertTrue("kernel.release" in r.verdict.notIsolated)
        assertEquals(JsonString("measured"), conditions(r).fields["isolation"])
    }

    // #endregion

    // #region trial order

    private fun controlFirst(label: String) =
        label.endsWith("unchanged") || label.startsWith("passing")

    @Test
    fun everyBlockOfTwoTrialsRunsEachArmFirstOnce() {
        val executor = FakeExecutor(behave = failsWhen(culprit))
        solve(executor = executor)
        val experiments = mutableListOf<MutableList<Boolean>>()
        for ((opener, _) in executor.ran.chunked(2)) {
            if (opener.second == 0) experiments += mutableListOf<Boolean>()
            experiments.last() += controlFirst(opener.first)
        }
        assertTrue(experiments.size >= 3, "${experiments.size}")
        for (order in experiments) {
            for (block in order.chunked(2).filter { it.size == 2 }) {
                assertNotEquals(block[0], block[1], "$order")
            }
        }
    }

    @Test
    fun aDriftThatFollowsTheRunOrderIsNotConfirmedUnderACounterbalancedSchedule() {
        var calls = 0
        val reverseLabel = red().label
        val drift = { arm: RunConfig, _: Int ->
            calls++
            val second = calls % 2 == 0
            trial(if (arm.label.startsWith(reverseLabel)) !second else second)
        }
        val r = solve(executor = FakeExecutor(behave = drift))
        assertEquals(VerdictKind.STUCK, r.verdict.kind, r.verdict.statement)
        assertEquals(StuckWhy.NOT_REPRODUCED, r.verdict.why)
        assertTrue("fails as often" in r.verdict.statement, r.verdict.statement)
    }

    @Test
    fun theScheduleIsFixedBySeedAndBalancedInBlocksOfTwo() {
        val seed = TrialSchedule.seed("a", "b", "x1")
        val order = TrialSchedule(seed, 7)
        assertEquals(order.treatmentFirst, TrialSchedule(seed, 7).treatmentFirst)
        assertEquals(14, order.text.length)
        for (block in order.treatmentFirst.chunked(2).filter { it.size == 2 }) {
            assertNotEquals(block[0], block[1])
        }
        val orders = (1..8).map { TrialSchedule(TrialSchedule.seed("a", "b", "x$it"), 8).text }
        assertTrue(orders.toSet().size > 1, "the seed picks which arm opens a block")
        assertNotEquals(seed, TrialSchedule.seed("b", "a", "x1"))
        assertEquals(0, TrialSchedule(seed, 0).treatmentFirst.size)
    }

    @Test
    fun theCertificateRecordsTheSeedAndOrderOfEveryExperiment() {
        val r = solve()
        val cert = SolveCertificate.certificate(r.case)!!
        val summaries = (cert.fields.getValue("experiments") as JsonArray).items
        assertEquals(r.runs.size, summaries.size)
        for ((run, summary) in r.runs.zip(summaries)) {
            val seed = TrialSchedule.seed(green.hash(), red().hash(), run.id)
            val expected = TrialSchedule(seed, run.control.trials.size).toJson()
            assertEquals(expected, (summary as JsonObject).fields["schedule"], run.id)
            val file = CanonicalJson.parse(r.case.text(SolveCertificate.resultPath(run.seq))!!)
            assertEquals(expected, (file as JsonObject).fields["schedule"], run.id)
        }
    }

    // #endregion

    // #region the pilot

    private fun draw(seed: Long, i: Int): Int {
        var z = seed + (i + 1) * -7046029254386353131L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        z = z xor (z ushr 31)
        return ((z ushr 1) % 100).toInt()
    }

    private fun intermittent(seed: Long, percent: Int) =
        { arm: RunConfig, i: Int -> trial(culprit(arm) && draw(seed, i) < percent) }

    private val roomy = options.copy(budget = Budget(1000, 600))

    @Test
    fun theAdaptivePilotFindsAFailureThatShowsAtTenPercent() {
        val r = solve(o = roomy, executor = FakeExecutor(behave = intermittent(14, 10)))
        val pilot = r.runs.first()
        assertEquals("pilot", pilot.kind)
        assertEquals(11, pilot.treatment.trials.size, "the first failure is at index 10")
        assertEquals(1, pilot.treatment.failures)
        assertEquals(StuckWhy.BUDGET, r.verdict.why, "found, but too rare to size an arm")
        assertTrue("target power" in r.verdict.statement, r.verdict.statement)
        assertTrue(Verify.run(r.case).ok, Verify.run(r.case).text())
    }

    @Test
    fun theAdaptivePilotFindsAFailureThatShowsAtThirtyPercent() {
        val r = solve(o = roomy, executor = FakeExecutor(behave = intermittent(23, 30)))
        val pilot = r.runs.first()
        assertEquals(10, pilot.treatment.trials.size, "the first failure is at index 9")
        assertNotEquals(StuckWhy.NOT_REPRODUCED, r.verdict.why, r.verdict.statement)
        assertTrue(Verify.run(r.case).ok, Verify.run(r.case).text())
    }

    @Test
    fun aPilotThatSeesNoFailureStopsAtTheCapAndStatesTheRateBound() {
        val r = solve(executor = FakeExecutor(behave = { _, _ -> trial(false) }))
        assertEquals(StuckWhy.NOT_REPRODUCED, r.verdict.why)
        assertEquals(30, r.runs.single().treatment.trials.size)
        assertTrue("below 10% at 95%" in r.verdict.statement, r.verdict.statement)
        assertTrue(Verify.run(r.case).ok, Verify.run(r.case).text())
        val tight = options.copy(budget = Budget(40, 60))
        val small = solve(o = tight, executor = FakeExecutor { _, _ -> trial(false) })
        assertEquals(10, small.runs.single().treatment.trials.size)
        assertTrue("below 30% at 95%" in small.verdict.statement, small.verdict.statement)
    }

    @Test
    fun thePilotRuleIsIntegerArithmeticOverTheCapAndTheFirstFailure() {
        assertEquals(10, PilotRule.upperPercent(30))
        assertEquals(38, PilotRule.upperPercent(8))
        assertEquals(100, PilotRule.upperPercent(3))
        assertEquals(100, PilotRule.upperPercent(1))
        assertEquals(30, PilotRule.max(6, 400))
        assertEquals(6, PilotRule.max(6, 18))
        assertEquals(25, PilotRule.max(8, 100))
        assertEquals(6, PilotRule.trials(6, 30, 2))
        assertEquals(11, PilotRule.trials(6, 30, 10))
        assertEquals(30, PilotRule.trials(6, 30, null))
    }

    // #endregion

    // #region the family

    private fun family(r: SolveResult) =
        SolveCertificate.certificate(r.case)!!.fields.getValue("family") as JsonObject

    private fun forwardPerArm(r: SolveResult) =
        r.runs.first { it.kind == "forward" }.treatment.trials.size

    @Test
    fun theCertificateStatesTheFamilyAndTheFalseConfirmedBoundPerSearch() {
        val f = family(solve())
        assertEquals(JsonString("holm"), f.fields["method"])
        assertEquals(JsonInt(options.maxRounds.toLong()), f.fields["size"])
        val alpha = (f.fields["alpha"] as JsonInt).value
        val each = (f.fields["alphaPerTest"] as JsonInt).value
        val bound = (f.fields["falseConfirmedBound"] as JsonInt).value
        assertEquals(PlanWeights.ALPHA, alpha)
        assertEquals(alpha / options.maxRounds, each)
        assertEquals(each * options.maxRounds, bound)
        assertTrue(bound <= alpha, "the bound over the whole search stays within alpha")
        assertTrue("per search" in ((f.fields["scope"] as JsonString).value))
    }

    @Test
    fun everyExperimentRuleTestsAtTheFamilyShareOfAlpha() {
        val r = solve()
        val rules = r.runs.mapNotNull { it.spec?.rule }
        assertTrue(rules.isNotEmpty())
        for (rule in rules) assertEquals(PlanWeights.ALPHA / options.maxRounds, rule.alpha)
    }

    @Test
    fun aLargerFamilyNeedsMoreTrialsPerArmAndIsRecorded() {
        val wide = solve(o = roomy.copy(maxRounds = 40))
        assertEquals(JsonInt(40), family(wide).fields["size"])
        assertTrue(forwardPerArm(wide) > forwardPerArm(solve()), "${forwardPerArm(wide)}")
        assertTrue(Verify.run(wide.case).ok, Verify.run(wide.case).text())
    }

    // #endregion

    // #region the planner and the executor agree

    @Test
    fun anExperimentTheControlsAllowButTheArmsCannotSetIsReplannedNeverRun() {
        val r = solve(red = red("tool.git.version" to "2.50.0"))
        assertEquals(VerdictKind.CONFIRMED, r.verdict.kind, r.verdict.statement)
        val cert = SolveCertificate.certificate(r.case)!!
        val skipped = CanonicalJson.encode(cert.fields.getValue("skipped"))
        assertTrue("tool.git.version" in skipped, skipped)
        val blocked = CanonicalJson.encode(cert.fields.getValue("blocked"))
        assertTrue("tool.git.version" in blocked)
        for (run in r.runs.filter { it.kind != "pilot" }) {
            val spec = run.spec!!
            assertTrue(spec.executable, "${run.id} ran but was not executable")
            assertTrue(spec.blockers.isEmpty())
        }
    }

    @Test
    fun theBlockedHypothesesArePlannedAsManualWithTheirReason() {
        val ranking = dev.gmitch215.drift.rank.Ranker.rank(green, red())
        val frame = Frame.environments("g", "r")
        val base = Planner.from(ranking, frame, Options(1, 3, 3))
        val id = base.hypotheses.first { "env.TZ" in it.members }.id
        val after = Planner.plan(
            base.hypotheses,
            base.prior,
            frame,
            base.options,
            blocked = mapOf(id to "no value"),
        )
        val manual = after.experiments.filter { it.decision == Decision.MANUAL }
        assertTrue(manual.any { id in it.experiment.flips && "no value" in it.reason })
        assertTrue(
            after.experiments.filter { it.decision != Decision.MANUAL }
                .none { id in it.experiment.flips },
        )
        val unblocked = Planner.plan(base.hypotheses, base.prior, frame, base.options)
        assertEquals(base.json(), unblocked.json(), "no blocked map leaves the plan unchanged")
    }

    // #endregion

    // #region preregistration, determinism, integrity

    @Test
    fun theSpecWithItsRuleIsWrittenBeforeTheFirstTrialOfItsArms() {
        val files = MemoryCaseFiles()
        val seen = mutableListOf<Boolean>()
        val behave = { arm: RunConfig, i: Int ->
            val id = files.files.keys.filter { it.startsWith("case/arms/") }
                .map { it.substringAfter("arms/").substringBefore('-') }
            seen += id.all { files.files.containsKey("case/experiments/$it.json") || it == "p0" }
            trial(culprit(arm) && i >= 0)
        }
        solve(files = files, executor = FakeExecutor(behave = behave))
        assertTrue(seen.isNotEmpty() && seen.all { it })
    }

    @Test
    fun theSameOutcomesGiveTheSameBytesAndTheCertificateHashIsPinned() {
        val a = solve().case
        val b = solve().case
        assertEquals(a.files, b.files)
        val cert = a.text(SolveCertificate.CERTIFICATE)!!
        assertEquals(GOLDEN, Sha256.hex(cert), "certificate bytes changed")
    }

    @Test
    fun theCaseHoldsEveryFileAndPassesItsChecks() {
        val c = solve().case
        assertEquals(emptyList(), c.check())
        assertEquals(emptyList(), SolveCertificate.check(c))
        assertTrue(c.files.keys.any { it.startsWith("arms/x1-control/") })
        assertTrue(c.files.keys.any { it.startsWith("experiments/x1.json") })
        assertTrue("results/0001.json" in c.files)
        val index = CanonicalJson.parse(c.text("results/index.json")!!) as JsonObject
        assertEquals(
            c.files.keys.count { it.startsWith("results/0") },
            (index.fields.getValue("results") as dev.gmitch215.drift.json.JsonArray).items.size,
        )
    }

    @Test
    fun everyResultCarriesItsRawCounts() {
        val r = solve()
        for ((i, run) in r.runs.withIndex()) {
            val record = CanonicalJson.parse(r.case.text(SolveCertificate.resultPath(i + 1))!!)
            val text = CanonicalJson.encode(record)
            assertTrue("\"failures\":${run.control.failures}" in text)
            assertTrue("\"trials\":[" in text)
            assertEquals(run.control.trials.size, run.treatment.trials.size)
        }
    }

    @Test
    fun editingAResultOrItsHashChainIsDetected() {
        val c = solve().case
        val edited = c.files.toMutableMap()
        val second = edited.getValue("results/0002.json")
        edited["results/0002.json"] = second.replace("\"fail", "\"pass")
        assertTrue(CaseFile(edited).check().isNotEmpty(), "the manifest hash catches it")
        val manifestFixed = edited.toMutableMap()
        manifestFixed[CaseFile.MANIFEST] = CaseFile.manifest(manifestFixed)
        assertEquals(emptyList(), CaseFile(manifestFixed).check())
        val problems = SolveCertificate.check(CaseFile(manifestFixed))
        assertEquals(1, problems.size)
        assertTrue("results chain broken at entry 1" in problems.single(), problems.single())
        val dropped = c.files.toMutableMap()
        dropped.remove("results/0004.json")
        assertTrue(SolveCertificate.check(CaseFile(dropped)).isNotEmpty())
    }

    @Test
    fun theBeliefMovesOnlyOnADecidedExperiment() {
        val r = solve()
        val cert = SolveCertificate.certificate(r.case)!!
        val posteriors = (cert.fields.getValue("posteriors") as dev.gmitch215.drift.json.JsonArray)
        assertTrue(posteriors.items.size >= 3)
        val first = Belief.UNKNOWN
        assertTrue(first in CanonicalJson.encode(posteriors))
        assertNull(
            (cert.fields["conditions"] as? JsonObject)?.fields?.get("missing"),
        )
    }

    @Test
    fun failWhenParsesExitAndPatternsAndRefusesABadPattern() {
        assertEquals(FailWhen.Exit, FailWhen.parse("exit"))
        assertTrue(FailWhen.parse("wrong (day|month)").failed(RawTrial(0, "wrong month", 1)))
        assertFalse(FailWhen.parse("wrong day").failed(RawTrial(1, "all fine", 1)))
        assertTrue(FailWhen.Exit.failed(RawTrial(3, "", 1)))
        assertFailsWith<IllegalArgumentException> { FailWhen.parse("(") }
        assertFailsWith<IllegalArgumentException> { FailWhen.parse("") }
    }

    @Test
    fun hypothesisSetsAreTheSameAcrossReplans() {
        val ranking = dev.gmitch215.drift.rank.Ranker.rank(green, red())
        val plan = Planner.from(ranking, Frame.environments("g", "r"), Options(1, 3, 3))
        val ids = plan.hypotheses.map(Hypothesis::id)
        assertTrue(ids.containsAll(listOf("env.TZ", "env.LANG", "cgroup.memory.max")))
    }

    // #endregion

    companion object {
        const val GOLDEN = "bf2f1377ebc0661e9ef937ebcee9c0b3f60179c33c674f7fc57098b4cefd0549"
    }
}
