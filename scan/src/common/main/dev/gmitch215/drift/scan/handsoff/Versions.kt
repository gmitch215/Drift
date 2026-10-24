package dev.gmitch215.drift.scan.handsoff

/** What a version string says: an exact version, a constraint, or a name that moves. */
internal sealed interface Version {
    /** Dotted numbers, as a prefix when written `20` or `20.x`. */
    data class Exact(val parts: List<Int>) : Version

    data object Constraint : Version

    /** `latest`, `stable` or an LTS alias: the answer depends on the day and the machine. */
    data object Moving : Version
}

internal object Versions {
    private val moving = setOf(
        "node", "latest", "stable", "current", "system", "lts", "lts/*", "*", "x", "default",
    )
    private val exact = Regex("^(\\d+(?:\\.\\d+)*)(?:\\.[xX*])?$")
    private val comparator =
        Regex("^(>=|<=|==|!=|~=|>|<|=|\\^|~)?\\s*v?(\\d+(?:\\.\\d+)*)(?:\\.[xX*])?$")

    /** Drops a leading `v` or `go`, a trailing vendor suffix and build metadata. */
    fun normalize(raw: String): String {
        var v = raw.trim()
        v = v.removePrefix("v").removePrefix("go")
        v = v.substringBefore('+')
        val vendor = Regex("^[a-z][a-z0-9_]*-(\\d.*)$").find(v)
        if (vendor != null) v = vendor.groupValues[1]
        val tail = Regex("^(\\d+(?:\\.\\d+)*)-[A-Za-z][A-Za-z0-9._-]*$").find(v)
        if (tail != null) v = tail.groupValues[1]
        return v
    }

    fun classify(raw: String): Version {
        val v = normalize(raw)
        if (v.isEmpty() || v.lowercase() in moving || v.lowercase().startsWith("lts/")) {
            return Version.Moving
        }
        exact.find(v)?.let { m ->
            return Version.Exact(m.groupValues[1].split('.').map { it.toInt() })
        }
        return Version.Constraint
    }

    /** Whether two exact versions agree on the components both state. */
    fun agree(a: Version.Exact, b: Version.Exact): Boolean {
        val n = minOf(a.parts.size, b.parts.size)
        return a.parts.take(n) == b.parts.take(n)
    }

    /** True or false when [constraint] is a form this reads, null when it is not. */
    fun satisfies(exact: Version.Exact, constraint: String): Boolean? {
        val alternatives = constraint.split("||").map { it.trim() }
        var understood = false
        for (alternative in alternatives) {
            val tokens = alternative.replace(Regex("(>=|<=|==|!=|~=|[<>=^~])\\s+"), "$1")
                .split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            var all = true
            for (token in tokens) {
                val m = comparator.find(token) ?: return null
                val bound = m.groupValues[2].split('.').map { it.toInt() }
                if (m.groupValues[1] == "^" && bound.first() == 0) return null
                all = all && holds(exact.parts, m.groupValues[1], bound)
            }
            understood = true
            if (all) return true
        }
        return if (understood) false else null
    }

    private fun holds(have: List<Int>, op: String, bound: List<Int>): Boolean {
        val cmp = compare(have, bound)
        val shared = minOf(have.size, bound.size)
        return when (op) {
            ">=" -> cmp >= 0

            ">" -> cmp > 0

            "<=" -> cmp <= 0 || have.take(shared) == bound.take(shared)

            "<" -> cmp < 0 && have.take(shared) != bound.take(shared)

            "", "=", "==" -> have.take(shared) == bound.take(shared)

            "!=" -> have.take(shared) != bound.take(shared)

            "^" -> cmp >= 0 && have.first() == bound.first()

            "~" -> cmp >= 0 && have.take(minOf(bound.size, 2)) == bound.take(2)

            "~=" -> cmp >= 0 && have.take(maxOf(1, bound.size - 1)) ==
                bound.take(maxOf(1, bound.size - 1))

            else -> false
        }
    }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
