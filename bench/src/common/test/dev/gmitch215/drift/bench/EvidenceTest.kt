package dev.gmitch215.drift.bench

import dev.gmitch215.drift.fixtures.BenchData
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.symptom.Symptom
import dev.gmitch215.drift.symptom.SymptomKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvidenceTest {
    private fun lab() = Lab.parse(
        BenchData.text("scenarios.json"),
        BenchData.text("split.json"),
        excluded = Lab.UNPROBED,
    ) { BenchData.text("probed/$it") }

    private fun probedIds(lab: Lab) = lab.devIds.filter {
        "probed/$it.green.json" in BenchData.names && "probed/$it.red.json" in BenchData.names
    }

    private fun result(id: String) = BenchData.text("results/$id")

    @Test
    fun theFailureIsTheRedArmsLastLineAndItsNonZeroExitCodes() {
        assertEquals(
            listOf(
                Symptom(SymptomKind.SIGNATURE, "Killed", 1),
                Symptom(SymptomKind.EXIT, "137", 1),
            ),
            EvidenceEval.failure(result("memory-oom-1.json")),
        )
        val quota = EvidenceEval.failure(result("cpu-limit-quota-basic-2.json"))
        assertEquals(SymptomKind.SIGNATURE, quota.first().kind)
        assertTrue("refusing to start" in quota.first().value, quota.first().value)
        val quiet = """{"arms":{"red":{"last_failure":"","exit_codes":[0,0]}}}"""
        assertEquals(emptyList(), EvidenceEval.failure(quiet))
    }

    @Test
    fun conditioningOnTheFailureNeverRaisesATier() {
        val lab = lab()
        val ids = probedIds(lab)
        assertTrue(ids.isNotEmpty())
        val plain = lab.observe(ids)
        val conditioned = lab.observe(ids) { EvidenceEval.failure(result("$it.json")) }
        for ((a, b) in plain.zip(conditioned)) {
            assertEquals(a.ranked.map { it.path }.toSet(), b.ranked.map { it.path }.toSet())
            val before = a.ranked.associate { it.path to it.tier.ordinal }
            for (c in b.ranked) {
                assertTrue(c.tier.ordinal <= before.getValue(c.path), a.scenario.id)
            }
        }
    }

    @Test
    fun theEvaluationScoresThreeRankersAndLabelsAPartialInput() {
        val lab = lab()
        val ids = probedIds(lab)
        val out = EvidenceEval.run(lab, ids, ::result, 20261006L)
        val input = out.require("input").obj()
        assertEquals(ids.size.toLong(), input.require("scenarios").long())
        val label = if (ids.size == lab.devIds.size) "complete" else "partial"
        assertEquals(label, input.require("label").string())
        val names = listOf("hand-set", "evidence-ranker", "dimension-prior")
        assertEquals(names, out.require("rankers").array().map { it.obj().require("id").string() })
        val models = out.require("bootstrap").obj().require("models").array()
        assertEquals(names, models.map { it.obj().require("id").string() })
        assertEquals("pending", out.require("trials_to_verdict").obj().require("status").string())
        val gained = out.require("top1_gained").array().map { it.string() }
        val lost = out.require("top1_lost").array().map { it.string() }
        assertTrue(gained.toSet().intersect(lost.toSet()).isEmpty())
        assertTrue((gained + lost).all { it in ids })
    }
}
