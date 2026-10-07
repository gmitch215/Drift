package dev.gmitch215.drift.plan

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.math.FixedPoint

/**
 * What each hypothesis predicts for each outcome of an experiment: [OutcomeTable.rows] maps a
 * hypothesis id to its outcome probabilities in micro-units (they sum to 1_000_000). A hypothesis
 * with no row says nothing about the outcome and gets a uniform one.
 */
class OutcomeTable(val outcomes: List<String>, val rows: Map<String, List<Long>>) {
	init {
		require(outcomes.size >= 2 && outcomes.distinct().size == outcomes.size) { "bad outcomes" }
		for ((id, row) in rows) {
			require(row.size == outcomes.size && row.all { it >= 0 }) { "bad row for $id" }
			require(row.sum() == FixedPoint.ONE) { "row for $id must sum to 1_000_000" }
		}
	}

	fun row(id: String): List<Long> = rows[id] ?: Belief.share(List(outcomes.size) { 1L })

	/** Hypotheses with identical rows, which no outcome of this experiment can tell apart. */
	fun groups(ids: List<String>): List<List<String>> =
		ids.sorted().groupBy { row(it) }.values.sortedBy { it.first() }

	fun toJson(ids: List<String>): JsonObject = obj(
		"outcomes" to JsonArray(outcomes.map(::JsonString)),
		"rows" to JsonObject(
			ids.sorted().associateWith { id -> JsonArray(row(id).map { JsonInt(it) }) },
		),
	)

	companion object {
		const val REPRODUCED = "reproduced"
		const val NOT_REPRODUCED = "not-reproduced"

		/** No probability goes below this: a harness can always misfire or a log be misread. */
		const val NOISE_FLOOR = 10_000L

		private fun clamp(p: Long) = p.coerceIn(NOISE_FLOOR, FixedPoint.ONE - NOISE_FLOOR)

		/**
		 * The failure shows in the treatment arm with probability [whenCulprit] for the ids in
		 * [culprits] and [otherwise] for every other id in [ids], both clamped by [NOISE_FLOOR].
		 */
		fun binary(
			ids: List<String>,
			culprits: Set<String>,
			whenCulprit: Long,
			otherwise: Long,
		): OutcomeTable {
			fun row(p: Long) = clamp(p).let { listOf(it, FixedPoint.ONE - it) }
			return OutcomeTable(
				listOf(REPRODUCED, NOT_REPRODUCED),
				ids.associateWith { if (it in culprits) row(whenCulprit) else row(otherwise) },
			)
		}
	}
}

data class Branch(val outcome: String, val probability: Long, val posterior: Belief) {
	fun toJson(): JsonObject = obj(
		"outcome" to JsonString(outcome),
		"probability" to JsonInt(probability),
		"posterior" to posterior.toJson(),
	)
}

/**
 * Entropy and expected information gain in micro-bits (1_000_000 is one bit). Every figure is an
 * integer: `log2` is exact for powers of two and otherwise within 2 micro-bits of the true value
 * (the error of `FixedPoint.ln` plus one division rounded to nearest), and each entropy term is
 * rounded to nearest, ties up.
 */
object Information {
	private const val LN2_PICO = 693_147_180_560L

	/** `log2(p / 1_000_000)` in micro-bits for `0 < p <= 1_000_000`; zero or negative. */
	fun log2(p: Long): Long {
		require(p in 1..FixedPoint.ONE) { "probability out of range" }
		var m = p
		var k = 0
		while (m < FixedPoint.ONE) {
			m = m shl 1
			k++
		}
		val frac = (2 * FixedPoint.ln(m) * 1_000_000_000_000L + LN2_PICO) / (2 * LN2_PICO)
		return frac - k * FixedPoint.ONE
	}

	/** `-sum p log2 p` over [probabilities] (micro-units); zero masses add nothing. */
	fun entropy(probabilities: List<Long>): Long = probabilities.filter { it > 0 }.sumOf {
		(it * -log2(it) + FixedPoint.ONE / 2) / FixedPoint.ONE
	}

	/**
	 * One branch per outcome with its probability and the posterior after seeing it. Bayes over
	 * integers: joint = prior times likelihood, then largest-remainder normalization, so both the
	 * probabilities and each posterior sum to exactly 1_000_000. An outcome no hypothesis can
	 * produce has probability 0 and leaves the prior unchanged.
	 */
	fun branches(prior: Belief, table: OutcomeTable): List<Branch> {
		val joint = prior.masses.map { m -> table.row(m.id).map { it * m.micro } }
		val perOutcome = table.outcomes.indices.map { o -> joint.sumOf { it[o] } }
		val probabilities = Belief.share(perOutcome)
		return table.outcomes.mapIndexed { o, name ->
			val posterior = if (perOutcome[o] == 0L) {
				prior
			} else {
				val shares = Belief.share(joint.map { it[o] })
				Belief(prior.masses.mapIndexed { i, m -> Mass(m.id, shares[i]) })
			}
			Branch(name, probabilities[o], posterior)
		}
	}

	/** Prior entropy minus the expected posterior entropy over [branches]; never negative. */
	fun gain(prior: Belief, branches: List<Branch>): Long {
		val expected = branches.sumOf {
			(it.probability * it.posterior.entropy() + FixedPoint.ONE / 2) / FixedPoint.ONE
		}
		return (prior.entropy() - expected).coerceAtLeast(0L)
	}

	fun gain(prior: Belief, table: OutcomeTable): Long = gain(prior, branches(prior, table))
}
