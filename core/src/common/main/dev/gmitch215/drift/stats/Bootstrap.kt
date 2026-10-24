package dev.gmitch215.drift.stats

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.symptom.Spread

/** A 95 percent interval on the ratio of two medians, in permille, from the bootstrap. */
data class RatioInterval(val low: Long, val high: Long) {
    fun format(): String = "${Spread.format(tenths(low))} to ${Spread.format(tenths(high))}"

    /** True when the whole interval sits on one side of a ratio of 1. */
    val excludesOne: Boolean get() = low > PERMILLE || high < PERMILLE

    private fun tenths(permille: Long) = (permille + 50) / 100

    private companion object {
        const val PERMILLE = 1000L
    }
}

/**
 * A percentile bootstrap on the ratio of medians. Each resample draws as many values as the side
 * holds, with replacement, from the sorted samples; the ratio is the median of the second side
 * over the median of the first, in permille, rounded half up. The generator is xorshift64 (shifts
 * 13, 7 and 17) seeded from the SHA-256 of both sorted samples, so the interval is a function of
 * the stored data alone and no clock or system random is read.
 */
object Bootstrap {
    const val RESAMPLES = 2000
    private const val TAIL = RESAMPLES / 40

    fun seed(a: List<Long>, b: List<Long>): Long {
        val text = "drift-compare-1:${a.sorted().joinToString(",")};${b.sorted().joinToString(",")}"
        val seed = Sha256.hex(text).take(16).toULong(16).toLong()
        return if (seed == 0L) 1L else seed
    }

    fun ratio(a: List<Long>, b: List<Long>): RatioInterval {
        val first = a.sorted()
        val second = b.sorted()
        var state = seed(first, second)

        fun next(): Long {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            return state
        }

        fun median(from: List<Long>): Long {
            val draw = List(from.size) { from[((next() ushr 1) % from.size).toInt()] }
            return Spread.of(draw)!!.median
        }

        val ratios = List(RESAMPLES) {
            val base = maxOf(median(first), 1L)
            (median(second) * 2000 + base) / (2 * base)
        }.sorted()
        return RatioInterval(ratios[TAIL], ratios[RESAMPLES - 1 - TAIL])
    }
}
