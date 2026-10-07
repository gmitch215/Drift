package dev.gmitch215.drift.bench

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.math.FixedPoint
import dev.gmitch215.drift.rank.Tier

/** A candidate as one model ordered it, with the probability that model gave it. */
class Entry(
	val path: String,
	val label: Boolean,
	val decoy: Boolean,
	val tier: Tier?,
	val bundled: Boolean,
	val p: Long,
	val ruled: Boolean = false,
	val verified: Boolean = false,
)

/**
 * [Pred.random] says the order is only a placeholder: hit values are the expectation over orders.
 */
class Pred(val o: Observed, val ranked: List<Entry>, val random: Boolean = false) {
	val n: Int get() = ranked.size

	val rank: Int? get() = ranked.indexOfFirst { it.label }.takeIf { it >= 0 }?.plus(1)

	/** Micro-units: 1_000_000 when the cause is within the first [k], the expectation if random. */
	fun hit(k: Int): Long {
		val r = rank ?: return 0
		return if (random) {
			Metrics.divRound(minOf(k, n) * Metrics.MICRO, n.toLong())
		} else {
			if (r <= k) Metrics.MICRO else 0
		}
	}

	fun reciprocal(): Long {
		val r = rank ?: return 0
		if (!random) return Metrics.divRound(Metrics.MICRO, r.toLong())
		val sum = (1..n).sumOf { Metrics.divRound(Metrics.MICRO, it.toLong()) }
		return Metrics.divRound(sum, n.toLong())
	}
}

fun interface Learner {
	fun learn(train: List<Observed>): (Observed) -> Pred
}

class Model(
	val id: String,
	val note: String,
	val tiered: Boolean,
	val learner: Learner,
	val table: ((List<Observed>) -> Table)? = null,
	val mapped: Boolean = false,
	val normalized: Boolean = false,
	val fitsWeights: Boolean = false,
	val none: ((List<Observed>) -> Long)? = null,
)

object Models {
	private fun logistic(score: Long): Long {
		val x = score.coerceIn(-19 * FixedPoint.ONE, 19 * FixedPoint.ONE)
		return Metrics.divRound(Metrics.MICRO * Metrics.MICRO, Metrics.MICRO + FixedPoint.exp(-x))
	}

	private fun entry(c: Cand, p: Long) = Entry(
		c.path,
		c.label,
		c.decoy,
		c.tier,
		c.bundled,
		p,
		c.rules.isNotEmpty(),
		c.verifiedRule,
	)

	private fun ordered(o: Observed, table: Table, byTier: Boolean = true): List<Pair<Cand, Long>> =
		o.ranked
		.map { it to table.score(it) }
		.sortedWith(
			compareBy(
				{ if (byTier) -it.first.tier.ordinal else 0 },
				{ -it.second },
				{ it.first.path },
			),
		)

	/** Hand-set order with every probability the same (the training base rate). */
	private fun baseRate(base: Count) = Metrics.divRound(
		(base.causes + Fit.LAPLACE) * Metrics.MICRO,
		(base.total + 2L * Fit.LAPLACE),
	)

	private fun mapped(table: (List<Observed>) -> Table, normalize: Boolean) = Learner { train ->
		val t = table(train)
		val iso = Fit.isotonic(train, t, normalize)
		return@Learner { o ->
			val x = Fit.inputs(o, t, normalize).zip(o.ranked).associate { (v, c) -> c.path to v }
			Pred(o, ordered(o, t).map { (c, _) -> entry(c, iso.at(x.getValue(c.path))) })
		}
	}

	val random = Model(
		"random",
		"uniform order over the ranker's candidates",
		false,
		Learner {
			{ o ->
				val sorted = o.ranked.sortedBy { it.path }
				val p = if (sorted.isEmpty()) 0 else Metrics.MICRO / sorted.size
				Pred(o, sorted.map { Entry(it.path, it.label, it.decoy, null, false, p) }, true)
			}
		},
	)

	val diffOrder = Model(
		"diff-order",
		"every changed attribute, alphabetical",
		false,
		Learner {
			{ o ->
				val cause = o.scenario.cause?.path
				val decoys = o.scenario.decoys.map { it.path }.toSet()
				val p = if (o.changed.isEmpty()) 0 else Metrics.MICRO / o.changed.size
				Pred(o, o.changed.map { Entry(it, it == cause, it in decoys, null, false, p) })
			}
		},
	)

	val handSet = Model(
		"hand-set",
		"hand-set-1 order; probability read as logistic(score)",
		true,
		Learner {
			{ o -> Pred(o, ordered(o, Table.handSet).map { (c, s) -> entry(c, logistic(s)) }) }
		},
	)

	val scoreOrder = Model(
		"hand-set-score-order",
		"hand-set-1 scores only: the tier does not order; probability read as logistic(score)",
		true,
		Learner {
			{ o ->
				Pred(o, ordered(o, Table.handSet, false).map { (c, s) -> entry(c, logistic(s)) })
			}
		},
	)

	val softmax = Model(
		"hand-set+softmax",
		"hand-set-1 order; probability is the share of the posterior against rivals and none",
		true,
		Learner {
			{ o ->
				val x = Fit.inputs(o, Table.handSet, true).zip(o.ranked)
					.associate { (v, c) -> c.path to v }
				Pred(o, ordered(o, Table.handSet).map { (c, _) -> entry(c, x.getValue(c.path)) })
			}
		},
		{ Table.handSet },
		false,
		true,
	)

	val softmaxNone = Model(
		"hand-set+softmax-none",
		"hand-set-1 order; softmax share with the none weight fit on the training scenarios",
		true,
		Learner { train ->
			val none = Fit.noneWeight(train, Table.handSet)
			return@Learner { o ->
				val x = Fit.inputs(o, Table.handSet, true, none).zip(o.ranked)
					.associate { (v, c) -> c.path to v }
				Pred(o, ordered(o, Table.handSet).map { (c, _) -> entry(c, x.getValue(c.path)) })
			}
		},
		{ Table.handSet },
		false,
		true,
		false,
		{ Fit.noneWeight(it, Table.handSet) },
	)

	val evidenceFit = Model(
		"evidence-fit+softmax-none",
		"hand-set-1 dimensions; probe and rule ratios fit; softmax share with a fitted none weight",
		true,
		Learner { train ->
			val t = Fit.evidence(train)
			val none = Fit.noneWeight(train, t)
			return@Learner { o ->
				val x = Fit.inputs(o, t, true, none).zip(o.ranked)
					.associate { (v, c) -> c.path to v }
				Pred(o, ordered(o, t).map { (c, _) -> entry(c, x.getValue(c.path)) })
			}
		},
		{ Fit.evidence(it) },
		false,
		true,
		true,
		{ Fit.noneWeight(it, Fit.evidence(it)) },
	)

	/** The shipped readout with the probe ratio set to [ratio]; only the sweep uses it. */
	fun probeAt(ratio: Long) = Model(
		"probe-$ratio",
		"hand-set-1 dimensions; probe ratio $ratio; softmax share with a fitted none weight",
		true,
		Learner { train ->
			val t = Table(Table.handSet.dimensions, ratio, Table.handSet.rules)
			val none = Fit.noneWeight(train, t)
			return@Learner { o ->
				val x = Fit.inputs(o, t, true, none).zip(o.ranked)
					.associate { (v, c) -> c.path to v }
				Pred(o, ordered(o, t).map { (c, _) -> entry(c, x.getValue(c.path)) })
			}
		},
	)

	val baseRate = Model(
		"hand-set+base-rate",
		"hand-set-1 order; constant probability",
		true,
		Learner { train ->
			val p = baseRate(Fit.count(train.flatMap { it.ranked }))
			return@Learner { o -> Pred(o, ordered(o, Table.handSet).map { (c, _) -> entry(c, p) }) }
		},
	)

	private fun isotonic(
		id: String,
		note: String,
		normalize: Boolean,
		fitsWeights: Boolean = false,
		table: (List<Observed>) -> Table,
	) = Model(id, note, true, mapped(table, normalize), table, true, normalize, fitsWeights)

	val handSetIso = isotonic("hand-set+isotonic", "hand-set-1 order; isotonic map", false) {
		Table.handSet
	}

	val handSetSoftIso = isotonic(
		"hand-set+softmax+isotonic",
		"hand-set-1 order; isotonic map over the softmax share",
		true,
	) { Table.handSet }

	val priorSoftIso = isotonic(
		"priors+softmax+isotonic",
		"fitted dimension priors; isotonic map over the softmax share",
		true,
		true,
	) { Fit.priors(it) }

	val fullSoftIso = isotonic(
		"full+softmax+isotonic",
		"fitted priors, probe and rule; isotonic map over the softmax share",
		true,
		true,
	) { Fit.full(it) }

	val priorIso = isotonic(
		"priors+isotonic",
		"fitted dimension priors; isotonic map",
		false,
		true,
	) {
		Fit.priors(it)
	}

	val fullIso = isotonic(
		"full+isotonic",
		"fitted priors, probe and rule; isotonic map",
		false,
		true,
	) { Fit.full(it) }

	/** The selection rule walks this list in order, starting from the shipped model. */
	val all = listOf(
		softmaxNone,
		baseRate,
		softmax,
		evidenceFit,
		handSetIso,
		handSetSoftIso,
		priorSoftIso,
		fullSoftIso,
		priorIso,
		fullIso,
	)

	val everything = listOf(random, diffOrder, handSet, scoreOrder) + all
}

object Evaluate {
	const val BINS = 5

	fun inSample(obs: List<Observed>, model: Model): List<Pred> {
		val predict = model.learner.learn(obs)
		return obs.map(predict)
	}

	fun crossValidated(obs: List<Observed>, model: Model, k: Int = Folds.K): List<Pred> =
		(0 until k).flatMap { f ->
			val train = obs.filter { Folds.of(it.scenario.id, k) != f }
			val test = obs.filter { Folds.of(it.scenario.id, k) == f }
			val predict = model.learner.learn(train)
			test.map(predict)
		}.sortedBy { it.o.scenario.id }

	private fun rateJson(r: Rate): JsonObject {
		val w = if (r.total == 0) Interval(0, 0) else r.wilson()
		return obj(
			"count" to JsonInt(r.count.toLong()),
			"total" to JsonInt(r.total.toLong()),
			"micro" to JsonInt(r.micro),
			"low" to JsonInt(w.low),
			"high" to JsonInt(w.high),
		)
	}

	/** A rate from hit values that are 0 or 1_000_000, or a mean without an interval. */
	private fun hits(values: List<Long>): JsonObject {
		if (values.all { it == 0L || it == Metrics.MICRO }) {
			return rateJson(Rate(values.count { it != 0L }, values.size))
		}
		val mean = if (values.isEmpty()) 0 else Metrics.divRound(values.sum(), values.size.toLong())
		return obj("micro" to JsonInt(mean), "total" to JsonInt(values.size.toLong()))
	}

	private fun mean(values: List<Long>): JsonInt =
		JsonInt(if (values.isEmpty()) 0 else Metrics.divRound(values.sum(), values.size.toLong()))

	private fun count(n: Int, total: Int): JsonObject = rateJson(Rate(n, total))

	private fun confident(t: Tier?) = t != null && t.ordinal >= Tier.PLAUSIBLE.ordinal

	fun brier(preds: List<Pred>): Long =
		Metrics.brier(preds.flatMap { p -> p.ranked.map { it.p to it.label } })

	/** Brier over the rankings the product prints a probability for. */
	fun brierCovered(preds: List<Pred>): Long = brier(preds.filter { it.o.covered })

	fun mrr(preds: List<Pred>): Long {
		val cause = preds.filter { it.o.scenario.cause != null }
		return if (cause.isEmpty()) {
			0
		} else {
			Metrics.divRound(cause.sumOf { it.reciprocal() }, cause.size.toLong())
		}
	}

	fun metrics(preds: List<Pred>, tiered: Boolean): JsonObject {
		val cause = preds.filter { it.o.scenario.cause != null }
		val seen = cause.filter { it.rank != null }
		val entries = preds.flatMap { it.ranked }
		val fields = linkedMapOf<String, JsonValue>(
			"scenarios" to JsonInt(preds.size.toLong()),
			"with_cause" to JsonInt(cause.size.toLong()),
			"cause_in_candidates" to JsonInt(seen.size.toLong()),
			"top1" to hits(cause.map { it.hit(1) }),
			"top3" to hits(cause.map { it.hit(3) }),
			"mrr" to mean(cause.map { it.reciprocal() }),
			"top1_when_seen" to hits(seen.map { it.hit(1) }),
			"top3_when_seen" to hits(seen.map { it.hit(3) }),
			"mrr_when_seen" to mean(seen.map { it.reciprocal() }),
			"candidates" to JsonInt(entries.size.toLong()),
			"brier" to JsonInt(Metrics.brier(entries.map { it.p to it.label })),
			"reliability" to reliability(entries),
			"covered" to covered(preds),
			"by_class" to byClass(preds),
			"by_evidence" to byEvidence(preds),
		)
		if (tiered) fields.putAll(tierSections(preds))
		return JsonObject(fields)
	}

	internal fun reliability(entries: List<Entry>): JsonArray = JsonArray(
		(0 until BINS).map { b ->
			val inBin = entries.filter {
				minOf(BINS - 1, (it.p * BINS / Metrics.MICRO).toInt()) == b
			}
			val observed = Rate(inBin.count { it.label }, inBin.size)
			obj(
				"bin" to JsonInt(b.toLong()),
				"from" to JsonInt(b * Metrics.MICRO / BINS),
				"to" to JsonInt((b + 1) * Metrics.MICRO / BINS),
				"n" to JsonInt(inBin.size.toLong()),
				"mean_predicted" to mean(inBin.map { it.p }),
				"observed" to rateJson(observed),
			)
		},
	)

	/** The rankings that print a probability: their size, Brier score and reliability. */
	private fun covered(preds: List<Pred>): JsonObject {
		val entries = preds.filter { it.o.covered }.flatMap { it.ranked }
		return obj(
			"scenarios" to JsonInt(preds.count { it.o.covered && it.ranked.isNotEmpty() }.toLong()),
			"candidates" to JsonInt(entries.size.toLong()),
			"brier" to JsonInt(Metrics.brier(entries.map { it.p to it.label })),
			"reliability" to reliability(entries),
		)
	}

	/**
	 * Out-of-fold Brier of the shipped readout as the probe ratio varies, over the rankings that
	 * carry a probe and over all of them, next to the uniform 1/n readout on the same rankings.
	 */
	fun probeSweep(obs: List<Observed>): JsonObject {
		fun probed(p: Pred) = p.o.ranked.any { it.probe }
		val uniform = crossValidated(obs, Models.random).filter(::probed)
		return obj(
			"uniform_probed_rankings" to JsonInt(brier(uniform)),
			"ratios" to JsonArray(
				PROBE_RATIOS.map { ratio ->
					val cv = crossValidated(obs, Models.probeAt(ratio))
					obj(
						"probe" to JsonInt(ratio),
						"cv_brier_probed_rankings" to JsonInt(brier(cv.filter(::probed))),
						"cv_brier_all_rankings" to JsonInt(brier(cv)),
					)
				},
			),
		)
	}

	val PROBE_RATIOS = listOf(
		1_000_000L,
		1_500_000L,
		2_000_000L,
		3_000_000L,
		4_000_000L,
		6_000_000L,
		8_000_000L,
		12_000_000L,
	)

	private const val SCALE = 1_000_000_000_000L

	/**
	 * The chance, in micro-units, of at least [k] hits when scenario i hits with probability
	 * `p[i]` (micro-units); the exact Poisson-binomial tail.
	 */
	fun tail(p: List<Long>, k: Int): Long {
		val dp = LongArray(p.size + 1)
		dp[0] = SCALE
		for ((i, q) in p.withIndex()) {
			for (j in i + 1 downTo 0) {
				val stay = dp[j] * (Metrics.MICRO - q)
				val move = if (j > 0) dp[j - 1] * q else 0L
				dp[j] = (stay + move) / Metrics.MICRO
			}
		}
		return Metrics.divRound(dp.drop(k).sum(), SCALE / Metrics.MICRO)
	}

	/**
	 * The shipped order against a random order of the same candidates: hits, the hits a random
	 * order is expected to make, and the chance that a random order makes at least as many.
	 */
	fun versusRandom(obs: List<Observed>): JsonObject {
		fun withCause(preds: List<Pred>) = preds.filter { it.o.scenario.cause != null }
		val ranker = withCause(inSample(obs, Models.handSet))
		val random = withCause(inSample(obs, Models.random))
		fun at(k: Int): JsonObject {
			val chance = random.map { it.hit(k) }
			val hits = ranker.count { it.hit(k) == Metrics.MICRO }
			return obj(
				"hits" to JsonInt(hits.toLong()),
				"scenarios" to JsonInt(ranker.size.toLong()),
				"expected_micro" to JsonInt(chance.sum()),
				"p_at_least_micro" to JsonInt(tail(chance, hits)),
			)
		}
		return obj("top1" to at(1), "top3" to at(3))
	}

	/** Rankings split by the evidence their candidates carry: attribute only, a probe, a rule. */
	private fun byEvidence(preds: List<Pred>): JsonObject {
		val groups = linkedMapOf<String, List<Pred>>(
			"attribute_only" to preds.filter { p ->
				p.o.ranked.none { it.probe || it.dimensionProbe || it.rules.isNotEmpty() }
			},
			"with_probe" to preds.filter { p -> p.o.ranked.any { it.probe } },
			"with_dimension_probe" to preds.filter { p -> p.o.ranked.any { it.dimensionProbe } },
			"with_rule" to preds.filter { p -> p.o.ranked.any { it.rules.isNotEmpty() } },
		)
		return JsonObject(
			groups.mapValues { (_, group) ->
				val withCause = group.filter { it.o.scenario.cause != null }
				val entries = group.flatMap { it.ranked }
				obj(
					"scenarios" to JsonInt(group.size.toLong()),
					"with_cause" to JsonInt(withCause.size.toLong()),
					"top1" to hits(withCause.map { it.hit(1) }),
					"top3" to hits(withCause.map { it.hit(3) }),
					"mrr" to mean(withCause.map { it.reciprocal() }),
					"candidates" to JsonInt(entries.size.toLong()),
					"brier" to JsonInt(Metrics.brier(entries.map { it.p to it.label })),
				)
			},
		)
	}

	private fun byClass(preds: List<Pred>): JsonObject = JsonObject(
		preds.groupBy { it.o.scenario.klass }.mapValues { (_, group) ->
			val withCause = group.filter { it.o.scenario.cause != null }
			obj(
				"scenarios" to JsonInt(group.size.toLong()),
				"top1" to hits(withCause.map { it.hit(1) }),
				"mrr" to mean(withCause.map { it.reciprocal() }),
			)
		},
	)

	/** What `known` and `plausible` rest on; needs no label, so it holds for any pair. */
	fun honesty(entries: List<Entry>): JsonObject {
		val ruled = entries.filter { it.ruled }
		return obj(
			"known" to JsonInt(entries.count { it.tier == Tier.KNOWN }.toLong()),
			"known_without_verified_rule" to
				JsonInt(entries.count { it.tier == Tier.KNOWN && !it.verified }.toLong()),
			"plausible" to JsonInt(entries.count { it.tier == Tier.PLAUSIBLE }.toLong()),
			"rule_matches" to JsonInt(ruled.size.toLong()),
			"rule_matches_inferred_only" to JsonInt(ruled.count { !it.verified }.toLong()),
			"inferred_only_printed_known" to
				JsonInt(ruled.count { !it.verified && it.tier == Tier.KNOWN }.toLong()),
		)
	}

	/** [honesty] plus how often the tiers named the injected cause. */
	fun gates(entries: List<Entry>): JsonObject {
		val known = entries.filter { it.tier == Tier.KNOWN }
		val plausible = entries.filter { it.tier == Tier.PLAUSIBLE }
		return JsonObject(
			honesty(entries).fields + mapOf(
				"known_hit" to count(known.count { it.label }, known.size),
				"plausible_hit" to count(plausible.count { it.label }, plausible.size),
				"wrong_known" to JsonInt(known.count { !it.label }.toLong()),
				"wrong_plausible" to JsonInt(plausible.count { !it.label }.toLong()),
			),
		)
	}

	/** Candidates as entries without a probability, for checks that need no model. */
	fun entries(obs: List<Observed>): List<Entry> = obs.flatMap { o ->
		o.ranked.map {
			Entry(
				it.path,
				it.label,
				it.decoy,
				it.tier,
				it.bundled,
				0,
				it.rules.isNotEmpty(),
				it.verifiedRule,
			)
		}
	}

	private fun tierSections(preds: List<Pred>): Map<String, JsonValue> {
		val withTop = preds.filter { it.ranked.isNotEmpty() }
		val topTiers = withTop.groupBy { it.ranked.first().tier!! }
		val candTiers = preds.flatMap { it.ranked }.groupBy { it.tier!! }
		fun byTier(m: Map<Tier, List<Any>>) = JsonObject(
			Tier.entries.associate { it.name.lowercase() to JsonInt((m[it]?.size ?: 0).toLong()) },
		)
		val tierRates = JsonObject(
			Tier.entries.associate { t ->
				val e = candTiers[t].orEmpty()
				t.name.lowercase() to rateJson(Rate(e.count { it.label }, e.size))
			},
		)
		val wrongTop = withTop.count {
			val top = it.ranked.first()
			confident(top.tier) && !top.label
		}
		val confidentEntries = preds.flatMap { it.ranked }.filter { confident(it.tier) }
		val bundles = preds.filter { it.o.scenario.klass == "bundle" && it.rank != null }
		val decoys = preds.filter {
			it.o.scenario.klass == "decoy" && it.rank != null && it.ranked.any { e -> e.decoy }
		}
		val controls = preds.filter { it.o.scenario.klass == "control" }
		val manual = preds.filter { !it.o.scenario.controllable }
		return mapOf(
			"top_tier" to byTier(topTiers),
			"candidate_tiers" to byTier(candTiers),
			"cause_rate_by_tier" to tierRates,
			"tier_gates" to gates(preds.flatMap { it.ranked }),
			"false_confidence" to obj(
				"scenarios" to count(wrongTop, preds.size),
				"candidates" to count(confidentEntries.count { !it.label }, confidentEntries.size),
			),
			"bundle_honesty" to obj(
				"bundle_class_cause_bundled" to count(
					bundles.count { p -> p.ranked.first { it.label }.bundled },
					bundles.size,
				),
				"cause_bundled_in_other_classes" to count(
					preds.count { p ->
						p.o.scenario.klass != "bundle" && p.rank != null &&
							p.ranked.first { it.label }.bundled
					},
					preds.count { it.o.scenario.klass != "bundle" && it.rank != null },
				),
			),
			"decoys" to obj(
				"cause_above_every_decoy" to count(
					decoys.count { p ->
						val at = p.ranked.indexOfFirst { it.label }
						p.ranked.withIndex().none { (i, e) -> e.decoy && i < at }
					},
					decoys.size,
				),
			),
			"controls" to obj(
				"scenarios" to JsonInt(controls.size.toLong()),
				"confident_candidate" to count(
					controls.count { p -> p.ranked.any { confident(it.tier) } },
					controls.size,
				),
				"no_candidates" to JsonInt(controls.count { it.ranked.isEmpty() }.toLong()),
				"mean_top_probability" to mean(controls.mapNotNull { it.ranked.firstOrNull()?.p }),
			),
			"manual" to obj(
				"scenarios" to JsonInt(manual.size.toLong()),
				"top1" to hits(manual.map { it.hit(1) }),
				"tier_above_difference" to count(
					manual.count { p -> p.ranked.any { confident(it.tier) } },
					manual.size,
				),
			),
		)
	}

	fun model(obs: List<Observed>, m: Model): JsonObject = obj(
		"id" to JsonString(m.id),
		"note" to JsonString(m.note),
		"in_sample" to metrics(inSample(obs, m), m.tiered),
		"cross_validated" to metrics(crossValidated(obs, m), m.tiered),
	)
}
