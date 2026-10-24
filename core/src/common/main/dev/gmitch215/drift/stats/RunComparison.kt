package dev.gmitch215.drift.stats

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.Sample
import dev.gmitch215.drift.symptom.Performance
import dev.gmitch215.drift.symptom.Spread

/** What one measurement is called, its unit, and how the larger side is described. */
class Metric(val name: String, val unit: String, val verb: String, val what: String)

/**
 * One measurement of run A against run B: the spreads, Mann-Whitney U with its exact p-value,
 * Cliff's delta and the bootstrap interval on the ratio of the medians (B over A). The verdict
 * names a direction only when the interval leaves 1 and the exact p-value, when it is computed,
 * is at most 0.05.
 */
class MetricComparison(
    val metric: Metric,
    val a: Spread,
    val b: Spread,
    val measuredA: Int,
    val measuredB: Int,
    val test: MannWhitney,
    val interval: RatioInterval,
) {
    /** 1 when B is larger, -1 when A is, 0 when the difference does not clear the spread. */
    val direction: Int
        get() = when {
            test.exact?.significant == false -> 0
            interval.low > RATIO_ONE -> 1
            interval.high < RATIO_ONE -> -1
            else -> 0
        }

    private fun count(side: String, spread: Spread, measured: Int) =
        "$side ${spread.count} samples" + if (measured > spread.count) " of $measured runs" else ""

    private fun u(): String {
        val whole = test.twoU / 2
        return if (test.twoU % 2 == 1L) "$whole.5" else "$whole"
    }

    private fun p(): String = test.exact?.let { "exact p ${it.format()}" }
        ?: "exact test not computed above ${MannWhitney.EXACT_LIMIT} samples per side"

    private fun signed(permille: Long) = if (permille > 0) "+$permille" else "$permille"

    fun verdict(): String {
        val range = "the interval of the ratio of B to A is ${interval.format()}"
        val larger = when (direction) {
            1 -> "B" to "A"
            -1 -> "A" to "B"
            else -> null
        }
        if (larger == null) {
            val more = test.exact?.takeIf { it.unreachable }?.let {
                "; with ${a.count} and ${b.count} samples the lowest possible exact p is " +
                    "${it.formatSmallest()}, so more samples are needed"
            }.orEmpty()
            return "No difference larger than the run to run spread (${p()}; $range$more)."
        }
        val big = if (direction == 1) b else a
        val small = if (direction == 1) a else b
        return "${larger.first} ${metric.verb} ${amount(big.median, small.median)} " +
            "${metric.what} than ${larger.second}; ${p()}; $range."
    }

    fun lines(): List<String> = listOf(
        "${metric.name} (${metric.unit}): ${count("A", a, measuredA)}, " +
            count("B", b, measuredB),
        "  A: min ${a.min}, median ${a.median}, max ${a.max}",
        "  B: min ${b.min}, median ${b.median}, max ${b.max}",
        "  ratio of medians, B over A: ${Spread.format(Spread.tenths(b.median, a.median))}",
        "  Mann-Whitney U ${u()}; ${p()}",
        "  Cliff's delta ${signed(test.delta)} permille (${CliffsDelta.label(test.delta)}; " +
            "positive means B is larger)",
        "  bootstrap interval of the ratio, ${Bootstrap.RESAMPLES} resamples, 95 percent: " +
            interval.format(),
        "  ${verdict()}",
    )

    fun toJson(): JsonObject {
        val exact = test.exact
        return obj(
            "name" to JsonString(metric.name),
            "unit" to JsonString(metric.unit),
            "first" to spread(a),
            "second" to spread(b),
            "ratioPermille" to JsonInt(permilleRatio()),
            "twoU" to JsonInt(test.twoU),
            "exactExtreme" to (exact?.let { JsonInt(it.extreme) } ?: JsonNull),
            "exactTotal" to (exact?.let { JsonInt(it.total) } ?: JsonNull),
            "exactP" to (exact?.let { JsonString(it.format()) } ?: JsonNull),
            "deltaPermille" to JsonInt(test.delta),
            "deltaLabel" to JsonString(CliffsDelta.label(test.delta)),
            "intervalLowPermille" to JsonInt(interval.low),
            "intervalHighPermille" to JsonInt(interval.high),
            "direction" to JsonString(
                when (direction) {
                    1 -> "second larger"
                    -1 -> "first larger"
                    else -> "none"
                },
            ),
            "verdict" to JsonString(verdict()),
        )
    }

    /** `2.7 times` from a ratio of 1.1 up, else the excess as a percent, as in `0.4 percent`. */
    private fun amount(big: Long, small: Long): String {
        val tenths = Spread.tenths(big, small)
        if (tenths >= SMALL_RATIO || small == 0L) return "${Spread.format(tenths)} times"
        return "${Spread.format(((big - small) * 1000 + small / 2) / small)} percent"
    }

    private fun permilleRatio(): Long {
        val base = maxOf(a.median, 1L)
        return (b.median * 2000 + base) / (2 * base)
    }

    private fun spread(s: Spread) = obj(
        "count" to JsonInt(s.count.toLong()),
        "min" to JsonInt(s.min),
        "median" to JsonInt(s.median),
        "max" to JsonInt(s.max),
    )

    companion object {
        private const val RATIO_ONE = 1000L
        private const val SMALL_RATIO = 11L

        val WALL = Metric("Wall time", "ms", "is", "slower")
        val CPU = Metric("CPU time", "ms", "used", "more CPU time")
        val MEMORY = Metric("Peak memory", "KiB", "used", "more peak memory")

        fun of(
            metric: Metric,
            a: List<Long>,
            b: List<Long>,
            runsA: Int = a.size,
            runsB: Int = b.size,
        ): MetricComparison? {
            val spreadA = Spread.of(a) ?: return null
            val spreadB = Spread.of(b) ?: return null
            if (minOf(a.size, b.size) < Performance.MIN_SAMPLES) return null
            return MetricComparison(
                metric,
                spreadA,
                spreadB,
                runsA,
                runsB,
                MannWhitney.of(a, b),
                Bootstrap.ratio(a, b),
            )
        }
    }
}

/**
 * Two runs that carry samples, compared on wall time and, where both sides measured them, CPU
 * time and peak memory. CPU time is left out when both medians are 0 ms, because a ratio of two
 * zeros says nothing. [cgroup] says when the container throttled or ran out of memory on one
 * side and not the other; [limits] are the limits of `drift diagnose`, from the same code.
 */
class RunComparison(
    val metrics: List<MetricComparison>,
    val cgroup: List<String>,
    val limits: List<String>,
) {
    fun lines(): List<String> = metrics.flatMap { it.lines() } + cgroup + limits

    fun toJson(): JsonObject = obj(
        "metrics" to JsonArray(metrics.map { it.toJson() }),
        "cgroup" to JsonArray(cgroup.map(::JsonString)),
        "limits" to JsonArray(limits.map(::JsonString)),
    )

    companion object {
        /** Null when either run has fewer than [Performance.MIN_SAMPLES] samples. */
        fun of(
            first: Run,
            second: Run,
            firstCapsule: Capsule,
            secondCapsule: Capsule,
        ): RunComparison? {
            val a = first.samples
            val b = second.samples
            if (minOf(a.size, b.size) < Performance.MIN_SAMPLES) return null
            val wall = MetricComparison.of(
                MetricComparison.WALL,
                a.map { it.wallMs },
                b.map { it.wallMs },
            )!!
            val metrics = listOfNotNull(
                wall,
                MetricComparison.of(MetricComparison.CPU, cpu(a), cpu(b), a.size, b.size)
                    ?.takeUnless { it.a.median == 0L && it.b.median == 0L },
                MetricComparison.of(
                    MetricComparison.MEMORY,
                    a.mapNotNull { it.peakKb },
                    b.mapNotNull { it.peakKb },
                    a.size,
                    b.size,
                ),
            )
            val limits = Performance.limits(
                first,
                second,
                wall.a,
                wall.b,
                firstCapsule,
                secondCapsule,
            )
            return RunComparison(metrics, Cgroup.lines(a, b), limits)
        }

        private fun cpu(samples: List<Sample>): List<Long> = samples.mapNotNull {
            val user = it.userMs
            val system = it.systemMs
            if (user != null && system != null) user + system else null
        }
    }
}
