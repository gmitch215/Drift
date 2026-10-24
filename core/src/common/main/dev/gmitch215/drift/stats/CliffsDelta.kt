package dev.gmitch215.drift.stats

/**
 * Cliff's delta in permille: the share of pairs where the second side is larger, minus the share
 * where it is smaller, scaled by 1000 and rounded half away from zero. The magnitude labels use
 * the thresholds that Romano and others (2006) published for the absolute value: below 147 is
 * negligible, below 330 small, below 474 medium, and anything else large.
 */
object CliffsDelta {
    const val SMALL = 147L
    const val MEDIUM = 330L
    const val LARGE = 474L

    fun permille(larger: Long, smaller: Long, pairs: Long): Long {
        if (pairs == 0L) return 0
        val diff = (larger - smaller) * 1000
        val half = pairs / 2
        return if (diff >= 0) (diff + half) / pairs else -((-diff + half) / pairs)
    }

    fun label(permille: Long): String {
        val size = if (permille < 0) -permille else permille
        return when {
            size < SMALL -> "negligible"
            size < MEDIUM -> "small"
            size < LARGE -> "medium"
            else -> "large"
        }
    }
}
