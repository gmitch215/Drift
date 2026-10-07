package dev.gmitch215.drift.plan

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.math.FixedPoint

data class Mass(val id: String, val micro: Long)

/**
 * A distribution over hypotheses in micro-units that sums to exactly 1_000_000. [UNKNOWN] is the
 * mass for "none of these", so the planner never assumes the cause is in the list.
 */
data class Belief(val masses: List<Mass>) {
	init {
		require(masses.all { it.micro >= 0 }) { "a mass cannot be negative" }
		require(masses.sumOf { it.micro } == FixedPoint.ONE) { "masses must sum to 1_000_000" }
		require(masses.map { it.id }.distinct().size == masses.size) { "duplicate hypothesis id" }
	}

	fun mass(id: String): Long = masses.firstOrNull { it.id == id }?.micro ?: 0L

	fun entropy(): Long = Information.entropy(masses.map { it.micro })

	fun toJson(): JsonObject = obj(
		"entropy" to JsonInt(entropy()),
		"masses" to JsonArray(
			masses.map { obj("id" to JsonString(it.id), "micro" to JsonInt(it.micro)) },
		),
	)

	companion object {
		const val UNKNOWN = "unknown"

		/**
		 * Splits [total] over [weights] in proportion, by the largest-remainder method: each share
		 * is floored, then the units left over go to the largest remainders, the earlier index
		 * first on a tie. The shares sum to exactly [total] and none is negative.
		 */
		fun share(weights: List<Long>, total: Long = FixedPoint.ONE): List<Long> {
			require(weights.isNotEmpty() && weights.all { it >= 0 }) { "need non-negative weights" }
			val sum = weights.sum()
			require(sum > 0) { "weights must not all be zero" }
			require(sum <= Long.MAX_VALUE / total) { "weights overflow" }
			val shares = weights.map { it * total / sum }.toMutableList()
			val left = (total - shares.sum()).toInt()
			val order = weights.indices.sortedWith(
				compareBy({ -(weights[it] * total % sum) }, { it }),
			)
			for (i in order.take(left)) shares[i]++
			return shares
		}

		/** The known ids by weight in sorted order, then [UNKNOWN] with exactly [unknown]. */
		fun of(weights: Map<String, Long>, unknown: Long): Belief {
			require(unknown in 0..FixedPoint.ONE) { "unknown mass out of range" }
			require(UNKNOWN !in weights) { "$UNKNOWN is reserved" }
			if (weights.isEmpty()) return Belief(listOf(Mass(UNKNOWN, FixedPoint.ONE)))
			val ids = weights.keys.sorted()
			val shares = share(ids.map { weights.getValue(it) }, FixedPoint.ONE - unknown)
			return Belief(ids.zip(shares) { id, micro -> Mass(id, micro) } + Mass(UNKNOWN, unknown))
		}
	}
}
