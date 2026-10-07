package dev.gmitch215.drift.rank

import dev.gmitch215.drift.history.Exclusion
import dev.gmitch215.drift.history.StepChange
import dev.gmitch215.drift.history.TransitionKind
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.jsonOrNull
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.know.Basis
import dev.gmitch215.drift.know.RuleMatch

/** Strength of the case for a candidate, weakest first; the rules are in [Ranker]. */
enum class Tier { DIFFERENCE, CORRELATED, PLAUSIBLE, KNOWN }

enum class EvidenceKind { ATTRIBUTE, PROBE, RULE, SPECTRUM }

/** A fact a rule match touched: an attribute path, `probe:<id>` or `run:<field>`. */
data class Evidence(val ref: String) {
	fun toJson(): JsonObject = obj("ref" to JsonString(ref))
}

/**
 * One independent piece of support. [EvidenceItem.ref] is an attribute path, `probe:<id>` or
 * `rule:<id>`; [EvidenceItem.weight] is its log-odds in micro-units and [EvidenceItem.id] names the
 * [Weights] row it came from.
 */
data class EvidenceItem(val kind: EvidenceKind, val ref: String, val weight: Long, val id: String) {
	fun toJson(): JsonObject = obj(
		"kind" to JsonString(kind.name.lowercase()),
		"ref" to JsonString(ref),
		"weight" to JsonInt(weight),
		"id" to JsonString(id),
	)
}

/** Ochiai counts and score (micro-units) of a candidate's red value over the included runs. */
data class Spectrum(val ef: Int, val nf: Int, val ep: Int, val score: Long) {
	fun toJson(): JsonObject = obj(
		"ef" to JsonInt(ef.toLong()),
		"nf" to JsonInt(nf.toLong()),
		"ep" to JsonInt(ep.toLong()),
		"score" to JsonInt(score),
	)
}

/**
 * A changed attribute and the case for it. [Candidate.score] is the sum of the [Candidate.evidence]
 * weights, for ordering within a tier only. [Candidate.matches] holds the rule matches with their
 * mechanism text; [Candidate.basis] is `verified` exactly for a `known` candidate.
 * [Candidate.coverage] is the lowest per-run coverage of the history behind the spectrum (null
 * without one). A candidate with a [Candidate.bundle] id cannot be told apart from the other
 * members by the data, so it is never the cause on its own. [Candidate.probability] (micro-units)
 * is the candidate's share of the posterior, see [Weights.shares]; it is null unless every
 * candidate of the ranking rests on its attribute change alone.
 */
data class Candidate(
	val path: String,
	val tier: Tier,
	val score: Long,
	val evidence: List<EvidenceItem>,
	val matches: List<RuleMatch>,
	val basis: Basis,
	val spectrum: Spectrum?,
	val coverage: Long?,
	val bundle: String?,
	val probability: Long? = null,
) {
	fun toJson(): JsonObject = obj(
		"path" to JsonString(path),
		"tier" to JsonString(tier.name.lowercase()),
		"score" to JsonInt(score),
		"evidence" to JsonArray(evidence.map { it.toJson() }),
		"rules" to JsonArray(matches.map { it.toJson() }),
		"basis" to JsonString(basis.id),
		"spectrum" to (spectrum?.toJson() ?: JsonNull),
		"coverage" to (coverage?.let { JsonInt(it) } ?: JsonNull),
		"bundle" to jsonOrNull(bundle),
		"probability" to (probability?.let { JsonInt(it) } ?: JsonNull),
	)
}

/** Candidates the data cannot separate: same tier, same support, same history counts. */
data class Bundle(val id: String, val members: List<String>) {
	fun toJson(): JsonObject = obj(
		"id" to JsonString(id),
		"members" to JsonArray(members.map(::JsonString)),
	)
}

/**
 * What a green-to-red transition holds beyond the ranked attributes: its [TransitionNote.kind] and
 * the [TransitionNote.steps] added or removed, which no capsule shows.
 */
data class TransitionNote(val kind: TransitionKind, val steps: List<StepChange>) {
	fun toJson(): JsonObject = obj(
		"kind" to JsonString(kind.name.lowercase()),
		"steps" to JsonArray(steps.map { it.toJson() }),
	)
}

/**
 * The full result: [Ranking.candidates] best first, the [Ranking.bundles] among them, rule matches
 * that touch no changed attribute ([Ranking.unattached]), runs left out of the spectrum
 * ([Ranking.excluded]) and the [Ranking.transition] when the input was one. Weights are hand-set
 * and uncalibrated; the probability of a candidate is calibrated only where [Weights.toJson] says
 * it is.
 */
data class Ranking(
	val candidates: List<Candidate>,
	val bundles: List<Bundle>,
	val unattached: List<RuleMatch>,
	val excluded: List<Exclusion>,
	val transition: TransitionNote?,
) {
	fun toJson(): JsonObject = obj(
		"weights" to Weights.toJson(),
		"candidates" to JsonArray(candidates.map { it.toJson() }),
		"bundles" to JsonArray(bundles.map { it.toJson() }),
		"unattached" to JsonArray(unattached.map { it.toJson() }),
		"excluded" to JsonArray(
			excluded.map {
				val reason = JsonString(it.reason.name.lowercase())
				obj("run" to JsonString(it.runId), "reason" to reason)
			},
		),
		"transition" to (transition?.toJson() ?: JsonNull),
	)
}
