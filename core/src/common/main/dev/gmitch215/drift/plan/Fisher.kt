package dev.gmitch215.drift.plan

import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.math.FixedPoint

/** An exact probability `numerator / denominator`, both from binomial coefficients. */
data class PValue(val numerator: Long, val denominator: Long) {
	private fun digits(): Pair<Long, Long> {
		var rem = numerator
		var q = 0L
		repeat(6) {
			rem *= 10
			q = q * 10 + rem / denominator
			rem %= denominator
		}
		return q to rem
	}

	/** `floor(p * 1_000_000)`, by long division, so nothing overflows. */
	fun micro(): Long = digits().first

	/** Whether `p <= alpha / 1_000_000`, decided exactly. */
	fun atMost(alpha: Long): Boolean {
		val (q, rem) = digits()
		return q < alpha || (q == alpha && rem == 0L)
	}
}

object Fisher {
	/** Largest table total: `C(60, 30) * 30` still fits in a Long. */
	const val MAX_TOTAL = 60

	fun choose(n: Int, k: Int): Long {
		require(n in 0..MAX_TOTAL) { "n out of range" }
		if (k < 0 || k > n) return 0L
		val m = minOf(k, n - k)
		var c = 1L
		for (i in 1..m) c = c * (n - m + i) / i
		return c
	}

	/**
	 * The one-sided exact p-value of the 2x2 table `[[a, b], [c, d]]`: the probability, with all
	 * margins fixed, of `a` or more in the top-left cell. For an experiment `a` is the failures in
	 * the treatment arm, `b` its passes, `c` and `d` the control's.
	 */
	fun oneSided(a: Int, b: Int, c: Int, d: Int): PValue {
		require(a >= 0 && b >= 0 && c >= 0 && d >= 0) { "counts cannot be negative" }
		val row1 = a + b
		val row2 = c + d
		val col1 = a + c
		var numerator = 0L
		for (x in a..minOf(row1, col1)) numerator += choose(row1, x) * choose(row2, col1 - x)
		return PValue(numerator, choose(row1 + row2, col1))
	}
}

/**
 * The trial count for an arm pair. [TrialPlan.perArm] is null when no count up to the budget
 * reaches [TrialPlan.targetPower]; [TrialPlan.power] is then the best reached, at
 * [TrialPlan.threshold] failures for that count. [TrialPlan.minimum] is the fewest trials per arm
 * at which alpha is reachable at all.
 */
data class TrialPlan(
	val rate: Long,
	val alpha: Long,
	val targetPower: Long,
	val minimum: Int?,
	val perArm: Int?,
	val threshold: Int?,
	val power: Long,
) {
	fun toJson(): JsonObject = obj(
		"rate" to JsonInt(rate),
		"alpha" to JsonInt(alpha),
		"targetPower" to JsonInt(targetPower),
		"minimum" to (minimum?.let { JsonInt(it.toLong()) } ?: JsonNull),
		"perArm" to (perArm?.let { JsonInt(it.toLong()) } ?: JsonNull),
		"threshold" to (threshold?.let { JsonInt(it.toLong()) } ?: JsonNull),
		"power" to JsonInt(power),
	)
}

object Trials {
	const val MAX_PER_ARM = 30

	private const val PICO = 1_000_000_000_000L

	/** Laplace's rule, `(failures + 1) / (runs + 2)` in micro-units: never exactly 0 or 1. */
	fun rate(failures: Int, runs: Int): Long {
		require(failures in 0..runs) { "failures out of range" }
		return (failures + 1L) * FixedPoint.ONE / (runs + 2L)
	}

	/** Fewest trials per arm at which all-fail against all-pass reaches [alpha]. */
	fun minPerArm(alpha: Long, max: Int = MAX_PER_ARM): Int? =
		(1..max).firstOrNull { Fisher.oneSided(it, 0, 0, it).atMost(alpha) }

	/** Fewest treatment failures out of [n], with a clean control of [n], that reach [alpha]. */
	fun threshold(n: Int, alpha: Long): Int? =
		(1..n).firstOrNull { Fisher.oneSided(it, n - it, 0, n).atMost(alpha) }

	/**
	 * `P(Bin(n, rate) >= k)` in micro-units, by a dynamic program in picos with each product
	 * floored, so the value is at most 1 micro-unit low.
	 */
	fun power(n: Int, k: Int, rate: Long): Long {
		var dp = LongArray(n + 1).also { it[0] = PICO }
		repeat(n) {
			val next = LongArray(n + 1)
			for (j in 0..n) {
				next[j] = dp[j] * (FixedPoint.ONE - rate) / FixedPoint.ONE +
					if (j > 0) dp[j - 1] * rate / FixedPoint.ONE else 0L
			}
			dp = next
		}
		return (k..n).sumOf { dp[it] } / 1_000_000
	}

	/**
	 * The smallest trials per arm, from the observed failure [rate], whose chance of reaching
	 * [alpha] against a control that never fails is at least [targetPower].
	 */
	fun plan(rate: Long, alpha: Long, targetPower: Long, max: Int = MAX_PER_ARM): TrialPlan {
		val min = minPerArm(alpha, max)
			?: return TrialPlan(rate, alpha, targetPower, null, null, null, 0L)
		var best = 0L
		var bestK = 0
		for (n in min..max) {
			val k = threshold(n, alpha) ?: continue
			val p = power(n, k, rate)
			if (p >= targetPower) return TrialPlan(rate, alpha, targetPower, min, n, k, p)
			best = p
			bestK = k
		}
		return TrialPlan(rate, alpha, targetPower, min, null, bestK, best)
	}
}
