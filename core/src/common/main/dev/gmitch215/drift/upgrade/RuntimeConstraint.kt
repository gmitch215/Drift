package dev.gmitch215.drift.upgrade

import dev.gmitch215.drift.diff.Version

/** The range syntax a lockfile writes a runtime constraint in. */
enum class Dialect { COMPOSER, NPM, PYTHON }

private typealias Test = (Version) -> Boolean

/**
 * A version constraint as lockfiles write it (`^7.2 || ~8.0, <8.1`, `>=6.9.0`, `>=3.8,<4.0`).
 * `||` and `|` separate alternatives; commas and spaces join bounds. A pre-release tag in a
 * bound is read only after `>=`, `>`, `<`, `<=`, `!=` and `==`; the stability flags of Composer
 * are dropped.
 */
class RuntimeConstraint private constructor(private val alternatives: List<List<Test>>) {
    fun allows(version: Version): Boolean = alternatives.any { tests -> tests.all { it(version) } }

    companion object {
        private val flags = Regex("@[A-Za-z]+")
        private val hyphen = Regex("(\\S+)\\s+-\\s+(\\S+)")
        private val atom = Regex(
            "(\\^|~=|~|>=|<=|===|==|!=|>|<|=)?\\s*(v?[0-9*xX][0-9A-Za-z.*+_-]*)",
        )
        private val wildcards = setOf("*", "x", "X")

        /** `null` when [text] is not a constraint this parser reads. */
        fun parse(text: String, dialect: Dialect): RuntimeConstraint? {
            val cleaned = flags.replace(text, "").trim()
            if (cleaned.isEmpty()) return null
            val parts = cleaned.split("||", "|")
            return RuntimeConstraint(parts.map { alternative(it, dialect) ?: return null })
        }

        private fun alternative(text: String, dialect: Dialect): List<Test>? {
            val joined = hyphen.replace(text.replace(',', ' ')) {
                ">=${it.groupValues[1]} <=${it.groupValues[2]}"
            }
            if (atom.replace(joined, " ").isNotBlank()) return null
            val tests = mutableListOf<Test>()
            for (m in atom.findAll(joined)) {
                tests += bound(m.groupValues[1], m.groupValues[2].removePrefix("v"), dialect)
                    ?: return null
            }
            return tests
        }

        private fun bound(op: String, token: String, dialect: Dialect): List<Test>? {
            val parts = token.split('.')
            val wild = parts.any { it in wildcards }
            val numbers = parts.takeWhile { it !in wildcards }
            if (numbers.isEmpty()) return emptyList()
            val nums = numbers.map { it.toLongOrNull() ?: return tagged(op, token) }
            val npmPartial = dialect == Dialect.NPM && nums.size < 3
            val next = bump(nums, nums.size - 1)
            return when (op) {
                "^" -> between(nums, bump(nums, caretAt(nums)))

                "~", "~=" -> between(nums, bump(nums, tildeAt(nums, dialect, op)))

                ">=" -> listOf(atLeast(nums))

                "<" -> listOf(below(nums))

                ">" -> listOf(if (npmPartial) atLeast(next) else above(nums))

                "<=" -> listOf(if (npmPartial) below(next) else atMost(nums))

                "!=" -> listOf(if (wild) outside(nums, next) else differs(nums))

                "", "=", "==", "===" -> if (wild || (npmPartial && op != "==")) {
                    between(nums, next)
                } else {
                    listOf(equals(nums))
                }

                else -> null
            }
        }

        private fun tagged(op: String, token: String): List<Test>? {
            val limit = Version.parse(token) ?: return null
            return when (op) {
                ">=" -> listOf { v -> v.compareCore(limit) >= 0 }
                ">" -> listOf { v -> v.compareCore(limit) > 0 }
                "<" -> listOf { v -> v.compareCore(limit) < 0 }
                "<=" -> listOf { v -> v.compareCore(limit) <= 0 }
                "!=" -> listOf { v -> v.compareCore(limit) != 0 }
                "", "=", "==", "===" -> listOf { v -> v.compareCore(limit) == 0 }
                else -> null
            }
        }

        private fun version(nums: List<Long>) = Version(nums, null, "")

        private fun atLeast(nums: List<Long>): Test = { v -> v.compareCore(version(nums)) >= 0 }

        private fun above(nums: List<Long>): Test = { v -> v.compareCore(version(nums)) > 0 }

        private fun below(nums: List<Long>): Test = { v -> v.compareCore(version(nums)) < 0 }

        private fun atMost(nums: List<Long>): Test = { v -> v.compareCore(version(nums)) <= 0 }

        private fun outside(low: List<Long>, high: List<Long>): Test =
            { v -> v.compareCore(version(low)) < 0 || v.compareCore(version(high)) >= 0 }

        private fun equals(nums: List<Long>): Test = { v -> v.compareCore(version(nums)) == 0 }

        private fun differs(nums: List<Long>): Test = { v -> v.compareCore(version(nums)) != 0 }

        private fun between(low: List<Long>, high: List<Long>) = listOf(atLeast(low), below(high))

        private fun bump(nums: List<Long>, at: Int): List<Long> =
            nums.take(at + 1).mapIndexed { i, n -> if (i == at) n + 1 else n }

        // the first non-zero number is the one that may not change; all zeros bumps the last
        private fun caretAt(nums: List<Long>) =
            nums.indexOfFirst { it != 0L }.let { if (it < 0) nums.size - 1 else it }

        // composer and `~=` bump the second to last number, npm and poetry bump the minor
        private fun tildeAt(nums: List<Long>, dialect: Dialect, op: String) =
            if (dialect == Dialect.COMPOSER || op == "~=") {
                maxOf(nums.size - 2, 0)
            } else {
                minOf(1, nums.size - 1)
            }
    }
}
