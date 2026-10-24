package dev.gmitch215.drift.bench

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.symptom.Signature
import dev.gmitch215.drift.symptom.Symptom
import dev.gmitch215.drift.symptom.SymptomKind

/**
 * The shipped ranker against the one that classes probe and rule evidence by the observed
 * failure, with the dimension prior beside them, on the same scenarios and the same template
 * bootstrap as [Analysis].
 */
object EvidenceEval {
    private val KEEP = setOf(
        "scenarios",
        "with_cause",
        "cause_in_candidates",
        "top1",
        "top3",
        "mrr",
        "top1_when_seen",
        "top3_when_seen",
        "candidate_tiers",
        "cause_rate_by_tier",
        "tier_gates",
        "false_confidence",
        "decoys",
        "controls",
    )

    /**
     * The failure the red arm showed, from a scenario's `data/results` text: the last failure line
     * as a normalized signature and each distinct non-zero exit code.
     */
    fun failure(result: String): List<Symptom> {
        val arms = (CanonicalJson.parse(result) as JsonObject).fields.getValue("arms")
        val red = (arms as JsonObject).fields.getValue("red") as JsonObject
        val line = red.fields.getValue("last_failure").string()
        val exits = red.fields.getValue("exit_codes").array().map { it.long() }
            .filter { it != 0L }.distinct()
        val text = if (line.isBlank()) null else Signature.normalize(line)
        return listOfNotNull(text?.let { Symptom(SymptomKind.SIGNATURE, it, 1) }) +
            exits.map { Symptom(SymptomKind.EXIT, it.toString(), 1) }
    }

    private fun ranker(id: String, note: String, preds: List<Pred>, tiered: Boolean): JsonObject {
        val m = Evaluate.metrics(preds, tiered)
        return obj(
            "id" to JsonString(id),
            "note" to JsonString(note),
            "metrics" to JsonObject(m.fields.filterKeys { it in KEEP }),
        )
    }

    /**
     * Scores [ids] of [lab] (dev ids only); [result] reads `<id>.json` from `data/results`. The
     * weights are hand-set and nothing is fit, so in-sample is the whole result. A subset of the
     * dev ids is labelled `partial`.
     */
    fun run(lab: Lab, ids: List<String>, result: (String) -> String, seed: Long): JsonObject {
        val failures = ids.associateWith { failure(result("$it.json")) }
        val plain = lab.observe(ids)
        val conditioned = lab.observe(ids) { failures.getValue(it) }
        val shipped = Evaluate.inSample(plain, Models.handSet)
        val evidence = Evaluate.inSample(conditioned, Models.rankerOrder)
        val prior = Evaluate.inSample(plain, Models.dimensionPrior)
        fun top1(preds: List<Pred>) =
            preds.filter { it.hit(1) == Metrics.MICRO }.map { it.o.scenario.id }.toSet()
        val before = top1(shipped)
        val after = top1(evidence)
        val missing = lab.devIds.size - ids.size

        fun candidates(pick: (Cand) -> Boolean) =
            JsonInt(conditioned.sumOf { o -> o.ranked.count(pick) }.toLong())

        return obj(
            "input" to obj(
                "label" to JsonString(if (missing == 0) "complete" else "partial"),
                "scenarios" to JsonInt(ids.size.toLong()),
                "dev_scenarios" to JsonInt(lab.devIds.size.toLong()),
                "without_probed_capture" to JsonInt(missing.toLong()),
                "failure" to JsonString(
                    "the red arm's last failure line, normalized as a log signature, and its " +
                        "non-zero exit codes (data/results)",
                ),
                "scenarios_with_failure_text" to JsonInt(
                    failures.values.count { f -> f.any { it.kind == SymptomKind.SIGNATURE } }
                        .toLong(),
                ),
            ),
            "conditioned_candidates" to obj(
                "with_mechanistic_probe" to candidates { it.probe },
                "with_observational_probe" to candidates { it.observationalProbe },
                "with_rule_match" to candidates { it.rules.isNotEmpty() },
                "with_mechanistic_rule" to candidates { it.mechanisticRule },
            ),
            "rankers" to JsonArray(
                listOf(
                    ranker(
                        Models.handSet.id,
                        "shipped: probe and rule evidence count whatever the failure was",
                        shipped,
                        true,
                    ),
                    ranker(Models.rankerOrder.id, Models.rankerOrder.note, evidence, true),
                    ranker(Models.dimensionPrior.id, Models.dimensionPrior.note, prior, false),
                ),
            ),
            "top1_gained" to JsonArray((after - before).sorted().map { JsonString(it) }),
            "top1_lost" to JsonArray((before - after).sorted().map { JsonString(it) }),
            "bootstrap" to Analysis.bootstrap(
                shipped,
                listOf(Models.rankerOrder to evidence, Models.dimensionPrior to prior),
                seed,
            ),
            "trials_to_verdict" to obj(
                "status" to JsonString("pending"),
                "missing" to JsonString(
                    "dev solve certificates run with the shipped order, the evidence order " +
                        "and the dimension-prior order; the repository holds none",
                ),
            ),
        )
    }
}
