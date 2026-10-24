package dev.gmitch215.drift.stats

/**
 * The Mann-Whitney U statistic of two samples of whole numbers, counted from pairs: [greater]
 * pairs have the second value above the first, [less] below, [equal] tied. A tie counts half in
 * [twoU], so every number stays an integer. [exact] is null above [EXACT_LIMIT] samples a side.
 */
data class MannWhitney(
    val first: Int,
    val second: Int,
    val greater: Long,
    val less: Long,
    val equal: Long,
    val exact: ExactP?,
) {
    /** Twice the U of the second side over the first. */
    val twoSecondU: Long get() = 2 * greater + equal

    /** Twice the smaller of the two U values, the one tables and papers quote. */
    val twoU: Long get() = minOf(twoSecondU, 2 * pairs - twoSecondU)

    val pairs: Long get() = first.toLong() * second

    /** Cliff's delta in permille; positive when the second side is larger. */
    val delta: Long get() = CliffsDelta.permille(greater, less, pairs)

    companion object {
        /** `C(60, 30)` is the largest count that has to fit in a `Long`. */
        const val EXACT_LIMIT = 30

        fun of(a: List<Long>, b: List<Long>): MannWhitney {
            val sorted = a.sorted()
            var greater = 0L
            var equal = 0L
            var below = 0
            var through = 0
            for (v in b.sorted()) {
                while (below < sorted.size && sorted[below] < v) below++
                if (through < below) through = below
                while (through < sorted.size && sorted[through] == v) through++
                greater += below
                equal += through - below
            }
            val pairs = a.size.toLong() * b.size
            val twoSecondU = 2 * greater + equal
            val exact = if (a.size in 1..EXACT_LIMIT && b.size in 1..EXACT_LIMIT) {
                exact(a, b, kotlin.math.abs(twoSecondU - pairs))
            } else {
                null
            }
            return MannWhitney(a.size, b.size, greater, pairs - greater - equal, equal, exact)
        }

        /**
         * Counts every way to split the pooled values into groups of the two sizes and keeps the
         * splits whose rank sum lies at least as far from its mean as the observed one, which is
         * [observed] doubled rank units away. Ties take the midrank of their group.
         */
        private fun exact(a: List<Long>, b: List<Long>, observed: Long): ExactP {
            val ranks = doubledMidranks((a + b).sorted())
            val k = minOf(a.size, b.size)
            val top = ranks.sortedDescending().take(k).sum().toInt()
            val counts = Array(k + 1) { LongArray(top + 1) }
            counts[0][0] = 1
            for ((seen, rank) in ranks.withIndex()) {
                val r = rank.toInt()
                for (j in minOf(seen + 1, k) downTo 1) {
                    for (s in r..top) counts[j][s] += counts[j - 1][s - r]
                }
            }
            val mean = k.toLong() * (ranks.size + 1)
            val away = counts[k].indices.filter { counts[k][it] > 0 }
                .associateWith { kotlin.math.abs(it - mean) }
            val widest = away.values.max()
            var total = 0L
            var extreme = 0L
            var smallest = 0L
            for ((s, distance) in away) {
                val count = counts[k][s]
                total += count
                if (distance >= observed) extreme += count
                if (distance == widest) smallest += count
            }
            return ExactP(extreme, total, smallest)
        }

        /** Twice the midrank of each value in [sorted], in the same order. */
        fun doubledMidranks(sorted: List<Long>): List<Long> {
            val out = ArrayList<Long>(sorted.size)
            var i = 0
            while (i < sorted.size) {
                var j = i
                while (j + 1 < sorted.size && sorted[j + 1] == sorted[i]) j++
                repeat(j - i + 1) { out += (i + j + 2).toLong() }
                i = j + 1
            }
            return out
        }
    }
}

/**
 * [extreme] of the [total] splits are at least as extreme as the observed one. [smallest] is the
 * count at the widest possible distance, so `smallest / total` is the lowest p-value these
 * sample sizes and ties can reach.
 */
data class ExactP(val extreme: Long, val total: Long, val smallest: Long) {
    /** True when p is at most 0.05. */
    val significant: Boolean get() = extreme * 20 <= total

    /** True when no split of these samples could reach p of 0.05. */
    val unreachable: Boolean get() = smallest * 20 > total

    fun format(): String = decimals(extreme, total)

    fun formatSmallest(): String = decimals(smallest, total)

    private fun decimals(part: Long, whole: Long): String {
        var scaled = SCALE
        if (part < whole) {
            var rest = part
            scaled = 0
            repeat(DIGITS) {
                rest *= 10
                scaled = scaled * 10 + rest / whole
                rest %= whole
            }
            if (rest * 2 >= whole) scaled++
        }
        if (scaled == 0L) return "below 0.0001"
        val fraction = (scaled % SCALE).toString().padStart(DIGITS, '0')
        return "${scaled / SCALE}.$fraction"
    }

    private companion object {
        const val DIGITS = 4
        const val SCALE = 10_000L
    }
}
