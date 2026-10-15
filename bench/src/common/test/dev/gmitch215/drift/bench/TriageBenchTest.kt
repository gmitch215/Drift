package dev.gmitch215.drift.bench

import dev.gmitch215.drift.fixtures.BenchTriage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TriageBenchTest {
    private val items = TriageBench.parse(BenchTriage.text("triage.json"))
    private val split = TriageBench.split(items)

    @Test
    fun theCommittedItemsRoundTripAndHaveUniqueIds() {
        assertEquals(BenchTriage.text("triage.json"), TriageBench.toJson(items))
        assertEquals(items.size, items.map { it.id }.toSet().size)
        assertTrue(items.all { it.source == "fixture" })
    }

    @Test
    fun theSplitFollowsTheSharedRuleByIdAndCoversEveryItem() {
        assertEquals(items.map { it.id }.sorted(), (split.dev + split.test).sorted())
        assertTrue(split.dev.all { SplitRule.isDev(it) })
        assertTrue(split.test.none { SplitRule.isDev(it) })
        assertTrue(split.dev.size > split.test.size)
        println("triage items: ${items.size}, dev ${split.dev.size}, test ${split.test.size}")
    }

    @Test
    fun devScoringReadsOnlyDevItemsAndPrintsTheScores() {
        val score = TriageBench.scoreDev(items)
        assertEquals(split.dev.size, score.items)
        print(score.report("dev"))
        assertTrue(score.precision.micro >= 900_000, "fact precision")
        assertTrue(score.recall.micro >= 900_000, "fact recall")
        assertTrue(score.brier <= 150_000, "Brier")
        assertTrue(score.brierLow <= score.brier && score.brier <= score.brierHigh)
    }

    @Test
    fun theTestSplitNeedsTheUnsealProof() {
        assertFailsWith<IllegalArgumentException> {
            TriageBench.scoreTest(items, Unsealed(emptyList()))
        }
        val score = TriageBench.scoreTest(items, Unsealed(split.test))
        assertEquals(split.test.size, score.items)
    }

    @Test
    fun claimScoresCountHitsAgainstTheLabelledClaims() {
        val claim = items.first { it.claims.isNotEmpty() }
        assertTrue(claim.correction != null, "claim items carry the maintainer correction")
        val all = TriageBench.scoreTest(items, Unsealed(split.test))
        val dev = TriageBench.scoreDev(items)
        assertEquals(items.sumOf { it.claims.size }, dev.claimRecall.total + all.claimRecall.total)
    }

    @Test
    fun anEmptyItemListScoresAsZeros() {
        val score = TriageBench.scoreDev(emptyList())
        assertEquals(0, score.items)
        assertEquals(0, score.brier)
        assertTrue(score.report("none").contains("n/a (0 of 0)"))
    }
}
