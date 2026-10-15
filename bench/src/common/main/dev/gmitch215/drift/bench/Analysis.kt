package dev.gmitch215.drift.bench

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.math.FixedPoint
import dev.gmitch215.drift.math.IntSqrt
import dev.gmitch215.drift.rank.Tier

/**
 * What the dev pairs support beyond a point estimate: template-clustered intervals, the
 * baselines the shipped order is compared with, the sample sizes the claims would need, bundle
 * honesty against the declared members and the false-CONFIRMED bounds.
 */
object Analysis {
    const val RESAMPLES = 10_000
    private const val TAIL = 250

    /** The Lab repeats on the 45 dev scenarios; the certificates are not in the repository. */
    const val SOLVE_RUNS = 180
    const val SOLVE_CONFIRMED = 59
    const val SOLVE_FALSE_CONFIRMED = 0
    const val SOLVE_STUCK_FIRST_RUN = 21
    const val SOLVE_SCENARIOS = 45

    const val Z_ONE_SIDED = 1_645_000L
    const val Z_TWO_SIDED = 1_960_000L
    const val Z_POWER = 842_000L
    private const val M = Metrics.MICRO

    private val BASELINES = listOf(
        Models.random,
        Models.diffOrder,
        Models.diffDimensionOrder,
        Models.dimensionPrior,
        Models.rulesOnly,
    )

    val BASELINE_IDS: List<String> = BASELINES.map { it.id }

    fun run(obs: List<Observed>, seed: Long): JsonObject {
        val shipped = Evaluate.inSample(obs, Models.handSet)
        val others = BASELINES.map { it to Evaluate.inSample(obs, it) }
        return obj(
            "bootstrap" to bootstrap(shipped, others, seed),
            "power" to power(obs, shipped),
            "bundle_honesty" to bundleHonesty(shipped),
            "false_confirmed" to falseConfirmed(),
        )
    }

    // #region bootstrap
    private fun cells(preds: List<Pred>, templates: List<String>): Array<LongArray> {
        val byTemplate = preds.filter { it.o.scenario.cause != null }
            .groupBy { it.o.scenario.template }
        return Array(templates.size) { i ->
            val group = byTemplate[templates[i]].orEmpty()
            longArrayOf(
                group.size.toLong(),
                group.sumOf { it.hit(1) },
                group.sumOf { it.hit(3) },
                group.sumOf { it.reciprocal() },
            )
        }
    }

    private fun mean(sum: Long, n: Long) = Metrics.divRound(sum, n)

    private fun mean(sum: Long, n: Int) = if (n == 0) 0 else mean(sum, n.toLong())

    private fun interval(values: LongArray, point: Long): JsonObject {
        val sorted = values.sortedArray()
        return obj(
            "micro" to JsonInt(point),
            "low" to JsonInt(sorted[TAIL]),
            "high" to JsonInt(sorted[sorted.size - 1 - TAIL]),
        )
    }

    private val METRICS = listOf("top1", "top3", "mrr")

    /**
     * Percentile intervals (2.5 to 97.5) over [RESAMPLES] draws of whole templates with
     * replacement, the same draws for every model so the differences from the shipped order
     * are paired. Scenarios without a cause do not enter.
     */
    fun bootstrap(
        shipped: List<Pred>,
        others: List<Pair<Model, List<Pred>>>,
        seed: Long,
    ): JsonObject {
        val templates = shipped.filter { it.o.scenario.cause != null }
            .map { it.o.scenario.template }.distinct().sorted()
        val series = listOf(Models.handSet to shipped) + others
        val grid = series.map { (_, preds) -> cells(preds, templates) }
        val values = Array(series.size) { Array(METRICS.size) { LongArray(RESAMPLES) } }
        val rng = SplitMix64(seed)
        val tot = LongArray(4)
        for (b in 0 until RESAMPLES) {
            val draw = IntArray(templates.size) { rng.below(templates.size) }
            for (m in series.indices) {
                tot.fill(0)
                for (t in draw) for (j in 0 until 4) tot[j] += grid[m][t][j]
                for (j in METRICS.indices) values[m][j][b] = mean(tot[j + 1], tot[0])
            }
        }
        fun point(m: Int, j: Int) = mean(grid[m].sumOf { it[j + 1] }, grid[m].sumOf { it[0] })
        val models = series.indices.map { m ->
            val fields = linkedMapOf<String, JsonValue>(
                "id" to JsonString(series[m].first.id),
                "scenarios" to JsonInt(grid[m].sumOf { it[0] }),
            )
            METRICS.forEachIndexed { j, name ->
                fields[name] = interval(values[m][j], point(m, j))
            }
            if (m > 0) {
                fields["shipped_minus"] = JsonObject(
                    METRICS.mapIndexed { j, name ->
                        val delta = LongArray(RESAMPLES) { values[0][j][it] - values[m][j][it] }
                        name to interval(delta, point(0, j) - point(m, j))
                    }.toMap(),
                )
            }
            JsonObject(fields)
        }
        return obj(
            "unit" to JsonString("template"),
            "templates" to JsonInt(templates.size.toLong()),
            "scenarios" to JsonInt(grid[0].sumOf { it[0] }),
            "resamples" to JsonInt(RESAMPLES.toLong()),
            "seed" to JsonInt(seed),
            "interval" to JsonString("percentile, 2.5 to 97.5"),
            "shipped" to JsonString(Models.handSet.id),
            "models" to JsonArray(models),
        )
    }
    // #endregion

    // #region power
    private fun sd(p: Long) = IntSqrt.floor(p * (M - p))

    private fun square(r: Long) = r * r / M

    /** Sample size, in micro-units, to tell [p1] from [p0] with the two powers [za] and [zb]. */
    fun sampleSize(p0: Long, p1: Long, za: Long, zb: Long): Long {
        require(p0 != p1) { "no difference to detect" }
        val num = (za * sd(p0) + zb * sd(p1)) / M
        return square(num * M / kotlin.math.abs(p1 - p0))
    }

    /** Per-arm sample size, in micro-units, of an unpaired two-proportion comparison. */
    fun twoSample(p1: Long, p2: Long, za: Long, zb: Long): Long {
        require(p1 != p2) { "no difference to detect" }
        val pooled = (p1 + p2) / 2
        val num = (
            za * IntSqrt.floor(2 * pooled * (M - pooled)) +
                zb * IntSqrt.floor(p1 * (M - p1) + p2 * (M - p2))
            ) / M
        return square(num * M / kotlin.math.abs(p1 - p2))
    }

    /** Sample size, in micro-units, for a proportion [p] known to within [half]. */
    fun halfWidth(p: Long, z: Long, half: Long): Long = square(z * sd(p) / half)

    private fun ceil(micro: Long) = (micro + M - 1) / M

    private fun row(
        id: String,
        claim: String,
        formula: String,
        inputs: Map<String, Long>,
        needed: Long,
        now: Long,
        note: String,
    ): JsonObject = obj(
        "id" to JsonString(id),
        "claim" to JsonString(claim),
        "formula" to JsonString(formula),
        "inputs_micro" to JsonObject(inputs.mapValues { JsonInt(it.value) }),
        "needed_micro" to JsonInt(needed),
        "needed" to JsonInt(ceil(needed)),
        "now" to JsonInt(now),
        "note" to JsonString(note),
    )

    private fun share(count: Int, total: Int) = Rate(count, total).micro

    fun power(obs: List<Observed>, shipped: List<Pred>): JsonObject {
        val cause = shipped.filter { it.o.scenario.cause != null }
        val n = cause.size
        val random = Evaluate.inSample(obs, Models.random).filter { it.o.scenario.cause != null }
        val diff = Evaluate.inSample(obs, Models.diffOrder).filter { it.o.scenario.cause != null }
        val randomTop1 = Metrics.divRound(random.sumOf { it.hit(1) }, n.toLong())
        val randomTop3 = Metrics.divRound(random.sumOf { it.hit(3) }, n.toLong())
        val top1 = share(cause.count { it.hit(1) == M }, n)
        val top3 = share(cause.count { it.hit(3) == M }, n)
        val alpha = share(diff.count { it.hit(1) == M }, n)
        val tiers = shipped.flatMap { it.ranked }.groupBy { it.tier }
        fun cut(t: Tier) = tiers[t].orEmpty().let { it.count { e -> e.label } to it.size }
        val (plausibleHits, plausible) = cut(Tier.PLAUSIBLE)
        val (differenceHits, difference) = cut(Tier.DIFFERENCE)
        val bundles = shipped.filter { it.o.scenario.klass == "bundle" && it.rank != null }
        val stuck = share(SOLVE_STUCK_FIRST_RUN, SOLVE_SCENARIOS)
        return obj(
            "alpha_one_sided" to JsonInt(50_000),
            "alpha_two_sided" to JsonInt(50_000),
            "power" to JsonInt(800_000),
            "rows" to JsonArray(
                listOf(
                    row(
                        "top1_vs_random",
                        "top-1 beats a random order",
                        "((1.645 sd(p0) + 0.842 sd(p1)) / (p1 - p0))^2",
                        mapOf("p0" to randomTop1, "p1" to 500_000L),
                        sampleSize(randomTop1, 500_000, Z_ONE_SIDED, Z_POWER),
                        n.toLong(),
                        "p1 is a planning value; the observed top-1 share is $top1",
                    ),
                    row(
                        "top3_vs_random",
                        "top-3 beats a random order",
                        "((1.645 sd(p0) + 0.842 sd(p1)) / (p1 - p0))^2",
                        mapOf("p0" to randomTop3, "p1" to top3),
                        sampleSize(randomTop3, top3, Z_ONE_SIDED, Z_POWER),
                        n.toLong(),
                        "p1 is the observed top-3 share",
                    ),
                    row(
                        "top1_vs_alphabetical",
                        "top-1 beats the alphabetical diff order (unpaired, per arm)",
                        "(1.96 sd(2 pbar) + 0.842 sd(p1,p2))^2 / (p1 - p2)^2",
                        mapOf("p1" to top1, "p2" to alpha),
                        twoSample(top1, alpha, Z_TWO_SIDED, Z_POWER),
                        n.toLong(),
                        "both shares observed",
                    ),
                    row(
                        "plausible_vs_difference",
                        "a plausible candidate is the cause more often than a difference one " +
                            "(per tier, candidates)",
                        "(1.96 sd(2 pbar) + 0.842 sd(p1,p2))^2 / (p1 - p2)^2",
                        mapOf(
                            "p1" to share(plausibleHits, plausible),
                            "p2" to share(differenceHits, difference),
                        ),
                        twoSample(
                            share(plausibleHits, plausible),
                            share(differenceHits, difference),
                            Z_TWO_SIDED,
                            Z_POWER,
                        ),
                        minOf(plausible, difference).toLong(),
                        "candidates in one scenario are correlated; the unit is a template",
                    ),
                    row(
                        "bundle_honesty",
                        "bundle honesty to within 0.1 at 0.8 (bundles)",
                        "1.96^2 p (1 - p) / h^2",
                        mapOf("p" to 800_000L, "half" to 100_000L),
                        halfWidth(800_000, Z_TWO_SIDED, 100_000),
                        bundles.size.toLong(),
                        "p is a planning value; now counts bundle scenarios with a ranked cause",
                    ),
                    row(
                        "stuck_rate",
                        "STUCK rate to within 0.05",
                        "1.96^2 p (1 - p) / h^2",
                        mapOf("p" to stuck, "half" to 50_000L),
                        halfWidth(stuck, Z_TWO_SIDED, 50_000),
                        SOLVE_SCENARIOS.toLong(),
                        "p is the first Lab run: $SOLVE_STUCK_FIRST_RUN of $SOLVE_SCENARIOS",
                    ),
                ),
            ),
        )
    }
    // #endregion

    // #region bundles

    /**
     * The bundle class scored against its declared members: a ranked bundle should hold the
     * cause and the members the template declares, and every other member is a cost.
     */
    fun bundleHonesty(preds: List<Pred>): JsonObject {
        val bundles = preds.filter { it.o.scenario.klass == "bundle" && it.rank != null }
        class Score(val holds: Boolean, val size: Int, val declared: Int, val wanted: Int)
        val scores = bundles.map { p ->
            val s = p.o.scenario
            val wanted = (s.bundle.map { it.path } + s.cause!!.path).toSet()
            val members = p.ranked.filter { it.bundled }.map { it.path }.toSet()
            Score(
                p.ranked.first { it.label }.bundled,
                members.size,
                members.count { it in wanted },
                wanted.size,
            )
        }
        val sized = scores.filter { it.size > 0 }
        return obj(
            "scenarios" to JsonInt(scores.size.toLong()),
            "cause_in_bundle" to Evaluate.count(scores.count { it.holds }, scores.size),
            "mean_bundle_size_micro" to JsonInt(mean(scores.sumOf { it.size } * M, scores.size)),
            "declared_member_precision_micro" to
                JsonInt(mean(sized.sumOf { it.declared * M / it.size }, sized.size)),
            "declared_member_recall_micro" to
                JsonInt(mean(scores.sumOf { it.declared * M / it.wanted }, scores.size)),
        )
    }
    // #endregion

    // #region false confirmed

    /** Exact one-sided 95 percent upper bound, in micro-units, on a rate with no event in [n]. */
    fun zeroEventBound(n: Int): Long {
        require(n > 0) { "need at least one trial" }
        return M - FixedPoint.exp(FixedPoint.ln(50_000) / n)
    }

    /** Smallest [n] whose zero-event bound is at most [bound] micro-units. */
    fun zeroEventSample(bound: Long): Long =
        ceil(FixedPoint.ln(50_000) * M / FixedPoint.ln(M - bound))

    fun falseConfirmed(): JsonObject {
        require(SOLVE_FALSE_CONFIRMED == 0) { "the bounds assume no false CONFIRMED" }
        val needed = zeroEventSample(10_000)
        return obj(
            "source" to JsonString(
                "counts recorded in TECHNICAL_REPORT Repeated runs; the certificates are not " +
                    "in the repository",
            ),
            "false_confirmed" to JsonInt(SOLVE_FALSE_CONFIRMED.toLong()),
            "per_confirmed_verdict" to obj(
                "label" to JsonString("bound on the share of CONFIRMED verdicts that are wrong"),
                "confirmed" to JsonInt(SOLVE_CONFIRMED.toLong()),
                "upper_micro" to JsonInt(zeroEventBound(SOLVE_CONFIRMED)),
                "confirmed_needed_for_1_percent" to JsonInt(needed),
            ),
            "per_run" to obj(
                "label" to JsonString("bound on the share of runs that end in a false CONFIRMED"),
                "runs" to JsonInt(SOLVE_RUNS.toLong()),
                "upper_micro" to JsonInt(zeroEventBound(SOLVE_RUNS)),
                "runs_needed_for_1_percent" to JsonInt(
                    ceil(needed * SOLVE_RUNS * M / SOLVE_CONFIRMED),
                ),
            ),
            "note" to JsonString(
                "the runs are 45 scenarios with four draws each, so they are not independent",
            ),
        )
    }
    // #endregion
}
