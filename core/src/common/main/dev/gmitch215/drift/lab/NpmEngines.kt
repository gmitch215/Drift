package dev.gmitch215.drift.lab

/** The Node versions each npm major runs on, from the `engines.node` field of its releases. */
internal object NpmEngines {
    private class Clause(val major: Int, val minor: Int, val patch: Int, val open: Boolean)

    private fun caret(major: Int, minor: Int, patch: Int = 0) = Clause(major, minor, patch, false)

    private fun atLeast(major: Int, minor: Int, patch: Int = 0) = Clause(major, minor, patch, true)

    private val ranges: Map<Int, List<Clause>> = mapOf(
        8 to listOf(caret(12, 13), caret(14, 15), atLeast(16, 0)),
        9 to listOf(caret(14, 17), caret(16, 13), atLeast(18, 0)),
        10 to listOf(caret(18, 17), atLeast(20, 5)),
        11 to listOf(caret(20, 17), atLeast(22, 9)),
        12 to listOf(caret(22, 22, 2), caret(24, 15), atLeast(26, 0)),
    )

    /** False only when [npm]'s major is known and [node] is outside its range. */
    fun supports(npm: String, node: String): Boolean {
        val clauses = ranges[npm.substringBefore('.').toIntOrNull()] ?: return true
        val n = node.split('.').map { it.toIntOrNull() ?: return true }
        val have = Triple(n.getOrElse(0) { 0 }, n.getOrElse(1) { 0 }, n.getOrElse(2) { 0 })
        return clauses.any {
            val floor = Triple(it.major, it.minor, it.patch)
            val above = compare(have, floor) >= 0
            if (it.open) above else have.first == it.major && above
        }
    }

    private fun compare(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int =
        compareValuesBy(a, b, { it.first }, { it.second }, { it.third })
}
