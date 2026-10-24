package dev.gmitch215.drift.stats

import dev.gmitch215.drift.model.CgroupDelta
import dev.gmitch215.drift.model.Sample

/**
 * What the cgroup counters say when two sets of samples differ: throttled CPU time and memory
 * events summed over the samples of each side. A side that has no reading says nothing.
 */
object Cgroup {
    private class Counter(val phrase: String, val of: (CgroupDelta) -> Long?)

    private val COUNTERS = listOf(
        Counter("CPU throttled") { it.throttled },
        Counter("Memory reached its cgroup limit") { it.memoryMax },
        Counter("A memory shortage was reported") { it.oom },
        Counter("The kernel killed a process for memory") { it.oomKill },
    )

    const val CHECK_FIRST =
        "Throttling by the container is a cause to check before blaming the code."

    fun lines(a: List<Sample>, b: List<Sample>): List<String> {
        val da = a.mapNotNull { it.cgroup }
        val db = b.mapNotNull { it.cgroup }
        if (da.isEmpty() && db.isEmpty()) return emptyList()
        if (da.isEmpty() || db.isEmpty()) {
            val has = if (da.isEmpty()) "B" else "A"
            return listOf("Only $has has cgroup counters, so throttling cannot be compared.")
        }
        val out = ArrayList<String>()
        for ((i, counter) in COUNTERS.withIndex()) {
            val x = da.mapNotNull(counter.of).takeIf { it.isNotEmpty() }?.sum()
            val y = db.mapNotNull(counter.of).takeIf { it.isNotEmpty() }?.sum()
            if (x == null || y == null || x == y) continue
            out += "${counter.phrase} ${times(x, y)}."
            if (i == 0) out += CHECK_FIRST
        }
        return out
    }

    private fun times(a: Long, b: Long): String = when {
        a == 0L -> "${count(b)} in B, none in A"
        b == 0L -> "${count(a)} in A, none in B"
        else -> "${count(a)} in A and ${count(b)} in B"
    }

    private fun count(n: Long) = if (n == 1L) "1 time" else "$n times"
}
