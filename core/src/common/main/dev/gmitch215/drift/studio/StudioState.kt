package dev.gmitch215.drift.studio

import dev.gmitch215.drift.diff.CapsuleDiff
import dev.gmitch215.drift.diff.DiffResult
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.know.Rule
import dev.gmitch215.drift.know.RuleMatch
import dev.gmitch215.drift.know.Rules
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.rank.Bundle
import dev.gmitch215.drift.rank.Candidate
import dev.gmitch215.drift.rank.Ranker
import dev.gmitch215.drift.rank.Weights
import dev.gmitch215.drift.symptom.Performance
import dev.gmitch215.drift.symptom.Symptom

/** What the two capsules hold, counted over the union of both sides. */
data class Facts(val attributes: Int, val probes: Int) {
    fun toJson(): JsonObject = obj(
        "attributes" to JsonInt(attributes.toLong()),
        "probes" to JsonInt(probes.toLong()),
    )
}

/**
 * The funnel from everything known to the explanations still open: [StudioState.facts] narrow to
 * [StudioState.changed], to the ranked [StudioState.relevant] candidates, to those
 * [StudioState.competing] at the top tier (empty unless two or more share it).
 * [StudioState.bundles] lists the candidates the data cannot separate and [StudioState.unattached]
 * the rule matches that touch no changed attribute.
 */
data class StudioState(
    val facts: Facts,
    val changed: DiffResult,
    val relevant: List<Candidate>,
    val competing: List<Candidate>,
    val bundles: List<Bundle>,
    val unattached: List<RuleMatch>,
    val performance: Performance? = null,
) {
    /** `performance` is written only when the runs carried samples. */
    fun toJson(): JsonObject = JsonObject(
        buildMap {
            put("facts", facts.toJson())
            put("changed", changed.toJson())
            put("relevant", JsonArray(relevant.map { it.toJson() }))
            put("competing", JsonArray(competing.map { it.toJson() }))
            put("bundles", JsonArray(bundles.map { it.toJson() }))
            put("unattached", JsonArray(unattached.map { it.toJson() }))
            put("weights", Weights.toJson())
            performance?.let { put("performance", it.toJson()) }
        },
    )

    companion object {
        /**
         * [performance] symptoms, when there are any, condition the ranking as the failure, next to
         * [failure], the symptoms already read from the failing run.
         */
        fun of(
            green: Capsule,
            red: Capsule,
            rules: List<Rule> = Rules.all,
            performance: Performance? = null,
            failure: List<Symptom> = emptyList(),
        ): StudioState {
            val changed = CapsuleDiff.diff(green, red)
            val measured = performance?.symptoms.orEmpty()
            val conditioned = if (measured.isEmpty()) null else failure + measured
            val ranking = Ranker.rank(green, red, rules, conditioned)
            val relevant = ranking.candidates
            val top = relevant.firstOrNull()?.tier
            return StudioState(
                facts = Facts(
                    attributes = (green.attributes.map { it.path } + red.attributes.map { it.path })
                        .toSet().size,
                    probes = (green.probes.map { it.id } + red.probes.map { it.id }).toSet().size,
                ),
                changed = changed,
                relevant = relevant,
                competing = relevant.filter { it.tier == top }.takeIf { it.size > 1 }.orEmpty(),
                bundles = ranking.bundles,
                unattached = ranking.unattached,
                performance = performance,
            )
        }
    }
}
