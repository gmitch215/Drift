package dev.gmitch215.drift

import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.case.Render
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.math.FixedPoint
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.plan.Frame
import dev.gmitch215.drift.plan.Options
import dev.gmitch215.drift.plan.Planner
import dev.gmitch215.drift.rank.Ranker
import dev.gmitch215.drift.rank.Weights
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RenderTest {
    private val ranking = DranglerCase.ranking
    private val plan = DranglerCase.plan

    private fun text(detail: Detail) = Render.render(ranking, plan, detail)

    private fun flat(s: String) = s.split(' ', '\n').filter { it.isNotEmpty() }.joinToString(" ")

    private fun capsule(vararg attrs: Pair<String, String>) =
        Capsule("c", attrs.map { Attribute(it.first, it.second, "test") })

    @Test
    fun theSummaryIsOneSentenceThatNamesNoSingleCause() {
        assertEquals(
            "9 changes differ with the same support, and tool.node.version ranks first only by " +
                "hand-set weights,\nso the data does not name one cause.\n",
            text(Detail.SUMMARY),
        )
    }

    @Test
    fun theThreeLevelsAreIdenticalOnEveryTarget() {
        assertEquals(
            "a96cfc898d1f8600c8db41163c0bc5bf02c51e1457da7d0c7bedd52d999026eb",
            Sha256.hex(text(Detail.SUMMARY)),
        )
        assertEquals(
            "daa5f59238fc1845d90deb330f1638f3b9f21447aee52e76a9f1645d5bbb4553",
            Sha256.hex(text(Detail.DETAIL)),
        )
        assertEquals(
            "79aaca18e0b913c5d842df210ae64abf7570bd2ea3fa9ea77ca7c1a8c76d8ef6",
            Sha256.hex(text(Detail.FULL)),
        )
    }

    @Test
    fun theNextExperimentRendersAtEveryLevel() {
        assertEquals(
            "802e833afb07de436495f13a6ce0863a2fa6f40b9c2f6c72e955baa571bbb664",
            Sha256.hex(Render.next(plan, Detail.SUMMARY)),
        )
        assertEquals(
            "abb1227e9f3774cd61992841d8266b206cbfb11cd5e60236d743efe60040452f",
            Sha256.hex(Render.next(plan, Detail.DETAIL)),
        )
        assertEquals(
            "9fedf60e1efc650e0cac530bdadd767fe6df897e81d4a13ad8e99548f3b6935a",
            Sha256.hex(Render.next(plan, Detail.FULL)),
        )
    }

    @Test
    fun anExperimentWithARecordedResultIsNotProposedAndTheTextSaysSo() {
        val first = requireNotNull(plan.next).experiment.id
        val text = flat(Render.next(plan.toJson(), Detail.DETAIL, setOf(first)))
        assertTrue("Already has a recorded result, so not proposed: $first." in text, text)
        assertTrue("Next experiment (a proposal, nothing has been run): $first:" !in text, text)
        assertTrue("lower information gain per cost than $first" !in text, text)
        assertTrue(
            "best information gain per cost among the experiments with no result yet" in text,
            text,
        )
        val all = plan.experiments.map { it.experiment.id }.toSet()
        val none = flat(Render.next(plan.toJson(), Detail.DETAIL, all))
        assertTrue("No other experiment can run from here." in none, none)
        val unchanged = Render.next(plan.toJson(), Detail.DETAIL, emptySet())
        assertEquals(Render.next(plan, Detail.DETAIL), unchanged)
    }

    @Test
    fun everyLineFitsInOneHundredColumnsAndIsPlainAscii() {
        for (detail in Detail.entries) {
            for (out in listOf(text(detail), Render.next(plan, detail))) {
                assertTrue(out.endsWith("\n") && !out.endsWith("\n\n"))
                for (line in out.lines()) {
                    assertTrue(line.length <= Render.WIDTH, line)
                    assertTrue(line.all { it.code in 32..126 }, line)
                }
            }
        }
    }

    @Test
    fun eachLevelAddsToTheOneBefore() {
        val summary = text(Detail.SUMMARY)
        val detail = text(Detail.DETAIL)
        val full = text(Detail.FULL)
        assertTrue(detail.startsWith(summary))
        assertTrue(full.startsWith(summary))
        assertTrue(detail.length > summary.length && full.length > detail.length)
        assertFalse("posterior" in detail)
        assertTrue("weight 1.098612 dimension.runtime" in full)
        assertTrue("if reproduced: step:Docker E2E/Set up Node 0.220421" in full)
        assertTrue("excluded step:Docker E2E/Dump Host State on Failure" in full)
        val flatDetail = flat(detail)
        assertTrue("(tier correlated, basis" in flatDetail && ", in bundle-1)" in flatDetail)
        assertTrue("evidence: attribute tool.node.version" in detail)
    }

    @Test
    fun theTextIsHonestAboutBundlesWeightsAndProposals() {
        val detail = flat(text(Detail.DETAIL))
        assertTrue("hand-set weights (hand-set-1, uncalibrated)" in detail)
        assertTrue("they order candidates and are not probabilities" in detail)
        assertTrue("none is the cause on its own" in detail)
        assertTrue("a proposal, nothing has been run" in detail)
        assertTrue("a prediction, not a measurement" in detail)
        assertTrue("Manual experiments, which Drift cannot run" in detail)
        assertTrue("Record the counts as an observation." in detail)
        assertTrue(Regex("""outcomes: reproduced 0\.\d{3}; not-reproduced 0\.\d{3}""") in detail)
        assertFalse("the cause is" in detail.lowercase())
        assertFalse("confirmed" in detail.lowercase())
    }

    @Test
    fun aLiveResultAndItsJsonRenderTheSame() {
        for (detail in Detail.entries) {
            assertEquals(
                text(detail),
                Render.render(ranking.toJson(), plan.toJson(), detail),
            )
        }
    }

    @Test
    fun noChangeSaysTheDataNamesNoCause() {
        val c = capsule("env.TZ" to "UTC")
        val r = Ranker.rank(c, c, emptyList())
        val options = Options(trialMinutes = 1, failures = 1, runs = 1)
        val p = Planner.from(r, Frame.shas("a", "b"), options)
        assertEquals(
            "The two captures show no difference that could explain the failure, " +
                "so the data names no cause.\n",
            Render.render(r, p, Detail.SUMMARY),
        )
        val next = Render.next(p, Detail.SUMMARY)
        assertTrue(next.startsWith("No experiment can run from here."), next)
        assertTrue("no-hypotheses: no candidate cause to test" in next, next)
    }

    @Test
    fun aSingleCandidateIsARankingNotAConfirmedCause() {
        val green = capsule("tool.node.version" to "22.1.0")
        val red = capsule("tool.node.version" to "22.9.0")
        val r = Ranker.rank(green, red, emptyList())
        val p = Planner.from(r, Frame.environments("laptop", "runner"), Options(2, 1, 1))
        val summary = Render.render(r, p, Detail.SUMMARY)
        assertEquals(
            "tool.node.version differs most (tier difference); this ranks differences and does " +
                "not confirm a cause.\n",
            summary.replace("\n", " ").replace("  ", " ").trim() + "\n",
        )
        assertNull(r.candidates.single().bundle)
        val next = flat(Render.next(p, Detail.DETAIL))
        assertTrue("Next experiment (a proposal, nothing has been run):" in next, next)
        assertTrue("automatic-local" in next, next)
        assertTrue("Build a local container from the laptop capture" in next, next)
    }

    @Test
    fun aProbabilityShowsAtTheFullLevelOnlyAndNeverForARankingWithoutOne() {
        val r = Ranker.rank(
            capsule("tool.node.version" to "22.1.0"),
            capsule("tool.node.version" to "22.9.0"),
            emptyList(),
        )
        val p = Planner.from(r, Frame.environments("laptop", "runner"), Options(2, 1, 1))
        val share = FixedPoint.format(r.candidates.single().probability!!)
        val line = "probability $share: its share against the other candidates and none, " +
            "calibrated on the ${Weights.FIT}"
        assertTrue(line in flat(Render.render(r, p, Detail.FULL)))
        assertFalse("probability $share" in flat(Render.render(r, p, Detail.DETAIL)))
        assertFalse("probability 0" in flat(text(Detail.FULL)))
    }

    @Test
    fun theDetailNamesTheDetailLevelsOnly() {
        assertEquals(Detail.FULL, Detail.parse("full"))
        assertEquals(Detail.SUMMARY, Detail.parse("summary"))
        assertNull(Detail.parse("loud"))
        val engine = plan.toJson().fields.keys + ranking.toJson().fields.keys
        assertFalse(engine.any { "detail" in it || "verbosity" in it })
    }
}
