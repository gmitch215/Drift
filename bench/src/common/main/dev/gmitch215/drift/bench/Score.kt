package dev.gmitch215.drift.bench

import dev.gmitch215.drift.math.IntSqrt

/** What the scenario's label says: a cause path, or null for a control. */
class Truth(val path: String?)

/** A diagnoser's answer: attribute paths best first, and the one it called CONFIRMED, if any. */
class Diagnosis(val ranked: List<String>, val confirmed: String? = null)

class Interval(val low: Long, val high: Long)

class Rate(val count: Int, val total: Int) {
	val micro: Long get() = if (total ==
		0
	) {
			0
		} else {
			Metrics.divRound(count * Metrics.MICRO, total.toLong())
		}

	fun wilson(): Interval = Metrics.wilson(count, total)
}

/** Fixed-point metrics in millionths, identical on every target. */
object Metrics {
	const val MICRO = 1_000_000L
	private const val Z_MICRO = 1_959_964L
	private const val Z2_MICRO = 3_841_459L

	fun divRound(a: Long, b: Long): Long = (2 * a + b) / (2 * b)

	/** 1-based rank of the true cause, null when absent or when the scenario is a control. */
	fun rank(truth: Truth, d: Diagnosis): Int? {
		val p = truth.path ?: return null
		val i = d.ranked.indexOf(p)
		return if (i < 0) null else i + 1
	}

	fun topK(ranks: List<Int?>, k: Int): Rate = Rate(
		ranks.count { it != null && it <= k },
		ranks.size,
	)

	/** Mean reciprocal rank; an absent cause counts as zero. */
	fun mrr(ranks: List<Int?>): Long {
		if (ranks.isEmpty()) return 0
		val sum = ranks.sumOf { if (it == null) 0L else divRound(MICRO, it.toLong()) }
		return divRound(sum, ranks.size.toLong())
	}

	/** Mean squared error of (confidence in micro, was it right) pairs, in micro. */
	fun brier(forecasts: List<Pair<Long, Boolean>>): Long {
		if (forecasts.isEmpty()) return 0
		val sum = forecasts.sumOf { (p, hit) ->
			val e = p - if (hit) MICRO else 0
			divRound(e * e, MICRO)
		}
		return divRound(sum, forecasts.size.toLong())
	}

	/** A CONFIRMED verdict naming anything but the true cause; any CONFIRMED on a control. */
	fun falseConfirmed(results: List<Pair<Truth, Diagnosis>>): Rate =
		Rate(results.count { (t, d) -> d.confirmed != null && d.confirmed != t.path }, results.size)

	/** Wilson score interval at 95 percent, as millionths. */
	fun wilson(count: Int, total: Int): Interval {
		require(total > 0 && count in 0..total) { "need 0 <= count <= total and total > 0" }
		val n = total.toLong()
		val k = count.toLong()
		val denominator = n * MICRO + Z2_MICRO
		val center = divRound((k * MICRO + Z2_MICRO / 2) * MICRO, denominator)
		val spread = divRound(k * (n - k) * MICRO, n)
		val root = IntSqrt.floor(spread * MICRO + Z2_MICRO * 250_000)
		val half = divRound(Z_MICRO * root, denominator)
		return Interval((center - half).coerceAtLeast(0), (center + half).coerceAtMost(MICRO))
	}
}
