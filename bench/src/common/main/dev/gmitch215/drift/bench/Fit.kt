package dev.gmitch215.drift.bench

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.know.Severity
import dev.gmitch215.drift.math.FixedPoint
import dev.gmitch215.drift.model.Dimension
import dev.gmitch215.drift.rank.Weights

/** The ranker's evidence weights as likelihood ratios in micro-units (spectrum is not fit). */
class Table(
	val dimensions: Map<Dimension, Long>,
	val probe: Long,
	val rules: Map<Severity, Long>,
) {
	fun score(c: Cand): Long = FixedPoint.ln(dimensions.getValue(c.dimension)) +
			(if (c.probe) FixedPoint.ln(probe) else 0L) +
			c.rules.sumOf { FixedPoint.ln(rules.getValue(it)) }

	companion object {
		/** The committed table of the ranker right now. */
		fun current(): Table = Table(
			Weights.dimensions.mapValues { it.value.ratio },
			Weights.probe.ratio,
			Weights.rules.mapValues { it.value.ratio },
		)

		/** The `hand-set-1` table the fit replaced; the before arm of every comparison. */
		val handSet = Table(
			mapOf(
				Dimension.RUNTIME to 3_000_000L,
				Dimension.COMPILER to 3_000_000L,
				Dimension.BUILD to 2_500_000L,
				Dimension.USERLAND to 2_500_000L,
				Dimension.OS to 2_000_000L,
				Dimension.KERNEL to 2_000_000L,
				Dimension.CPU_LIMIT to 2_000_000L,
				Dimension.MEMORY to 2_000_000L,
				Dimension.CPU to 1_500_000L,
				Dimension.ENV to 1_500_000L,
				Dimension.LOCALE to 1_500_000L,
				Dimension.LIMITS to 1_500_000L,
				Dimension.NETWORK to 1_500_000L,
				Dimension.BROWSER to 1_500_000L,
			),
			6_000_000L,
			mapOf(
				Severity.HIGH to 24_000_000L,
				Severity.MEDIUM to 12_000_000L,
				Severity.LOW to 6_000_000L,
			),
		)
	}
}

/** Candidates in a group and how many of them are the cause. */
class Count(val causes: Int, val total: Int)

object Folds {
	const val K = 5

	/** Fixed by the id alone: the first 4 bytes of sha256("fold:" + id) mod [K]. */
	fun of(id: String, k: Int = K): Int = (Sha256.hex("fold:$id").take(8).toLong(16) % k).toInt()
}

/** Pool-adjacent-violators over (score, label) pairs; a step function in micro-units. */
class Isotonic(val steps: List<Pair<Long, Long>>) {
	/** Probability of the last step whose first score is at most [score]. */
	fun at(score: Long): Long {
		val step = steps.lastOrNull { it.first <= score } ?: steps.first()
		return step.second
	}

	companion object {
		/**
		 * Blocks merge while a block's smoothed rate exceeds the next block's, where the rate is
		 * (hits + a) / (n + 2a). With `a = 0` this is classic PAV; with `a > 0` the output is
		 * monotone after smoothing, which plain PAV followed by smoothing would not guarantee.
		 */
		fun fit(points: List<Pair<Long, Boolean>>, a: Int = Fit.LAPLACE): Isotonic {
			require(points.isNotEmpty()) { "no points to fit" }
			class Block(val from: Long, var hits: Int, var n: Int)

			fun above(x: Block, y: Block) =
				(x.hits + a).toLong() * (y.n + 2 * a) > (y.hits + a).toLong() * (x.n + 2 * a)

			val stack = ArrayList<Block>()
			for ((score, group) in points.groupBy { it.first }.entries.sortedBy { it.key }) {
				stack += Block(score, group.count { it.second }, group.size)
				while (stack.size > 1 && above(stack[stack.size - 2], stack.last())) {
					val top = stack.removeAt(stack.size - 1)
					val under = stack.last()
					under.hits += top.hits
					under.n += top.n
				}
			}
			return Isotonic(
				stack.map {
					it.from to Metrics.divRound((it.hits + a) * Metrics.MICRO, (it.n + 2L * a))
				},
			)
		}
	}
}

object Fit {
	const val LAPLACE = 1

	/** The weight of "none" when nothing is fit: the same as having no evidence. */
	const val UNIT_NONE = FixedPoint.ONE

	/** Odds of (k + a) over (n - k + a): the smoothed odds of a candidate being the cause. */
	private fun odds(k: Int, n: Int): Pair<Long, Long> = (k + LAPLACE).toLong() to
		(n - k + LAPLACE).toLong()

	private fun ratio(group: Count, all: Count): Long {
		val (a, b) = odds(group.causes, group.total)
		val (c, d) = odds(all.causes, all.total)
		return Metrics.divRound(a * d * Metrics.MICRO, b * c)
	}

	fun count(cands: List<Cand>): Count = Count(cands.count { it.label }, cands.size)

	/** Per-dimension prior only; probe and rule weights keep the values of [base]. */
	fun priors(train: List<Observed>, base: Table = Table.handSet): Table {
		val cands = train.flatMap { it.ranked }
		val all = count(cands)
		return Table(
			Dimension.entries.associateWith { d ->
				ratio(count(cands.filter { it.dimension == d }), all)
			},
			base.probe,
			base.rules,
		)
	}

	/**
	 * The probe ratio (a differing probe against none) and one rule ratio shared by every
	 * severity (a rule match against none), with the dimension weights of [base]; there are too
	 * few matches per severity. Evidence that never occurs in [train] keeps the weight of [base].
	 */
	fun evidence(train: List<Observed>, base: Table = Table.handSet): Table {
		val cands = train.flatMap { it.ranked }
		val all = count(cands)
		val probed = cands.filter { it.probe }
		val ruled = cands.filter { it.rules.isNotEmpty() }
		return Table(
			base.dimensions,
			if (probed.isEmpty()) base.probe else ratio(count(probed), all),
			if (ruled.isEmpty()) {
				base.rules
			} else {
				Severity.entries.associateWith { ratio(count(ruled), all) }
			},
		)
	}

	/** Priors plus [evidence]. */
	fun full(train: List<Observed>, base: Table = Table.handSet): Table {
		val e = evidence(train, base)
		return Table(priors(train, base).dimensions, e.probe, e.rules)
	}

	/**
	 * What the calibration map reads for each candidate of [o]: its summed log-odds, or with
	 * [normalize] its share of the posterior against its rivals and "no listed candidate is the
	 * cause" (weight 1), so a scenario with many changes gives each one less.
	 */
	fun inputs(o: Observed, t: Table, normalize: Boolean, none: Long = UNIT_NONE): List<Long> {
		val scores = o.ranked.map { t.score(it) }
		return if (normalize) Weights.shares(scores, none) else scores
	}

	/** Weights of "no listed candidate is the cause" tried by [noneWeight], micro-units. */
	val NONE_GRID =
		listOf(125_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L)

	/**
	 * The grid value with the lowest training Brier score of the softmax share, over the rankings
	 * that get a printed probability ([Observed.covered]).
	 */
	fun noneWeight(train: List<Observed>, t: Table): Long = NONE_GRID.minBy { none ->
		Metrics.brier(
			train.filter { it.covered }.flatMap { o ->
				inputs(o, t, true, none).zip(o.ranked).map { (p, c) -> p to c.label }
			},
		)
	}

	fun isotonic(train: List<Observed>, table: Table, normalize: Boolean): Isotonic = Isotonic.fit(
			train.flatMap { o ->
				inputs(o, table, normalize).zip(o.ranked).map { (x, c) -> x to c.label }
			},
		)
}
