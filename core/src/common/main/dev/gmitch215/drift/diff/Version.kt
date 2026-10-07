package dev.gmitch215.drift.diff

import kotlin.math.sign

enum class Order { LESS, EQUAL, GREATER, UNORDERED }

enum class VersionScheme {
	GENERIC,
	JAVA,
	;

	companion object {
		fun of(path: String) = if (path.startsWith("tool.java.")) JAVA else GENERIC
	}
}

data class PreRelease(val rank: Int, val number: Long)

data class Version(val nums: List<Long>, val pre: PreRelease?, val revision: String) {
	/** Compares the numbers, then the pre-release; the revision text is ignored. */
	fun compareCore(other: Version): Int {
		for (i in 0 until maxOf(nums.size, other.nums.size)) {
			val c = nums.getOrElse(i) { 0L }.compareTo(other.nums.getOrElse(i) { 0L })
			if (c != 0) return c.sign
		}
		val a = pre
		val b = other.pre
		return when {
			a == null && b == null -> 0
			a == null -> 1
			b == null -> -1
			a.rank != b.rank -> a.rank.compareTo(b.rank).sign
			else -> a.number.compareTo(b.number).sign
		}
	}

	/** Total order: [compareCore], then the revision text. */
	fun compare(other: Version): Int {
		val c = compareCore(other)
		return if (c != 0) c else revision.compareTo(other.revision).sign
	}

	companion object {
		private const val MAX_DIGITS = 18

		private val words = listOf(
			"dev" to 0,
			"nightly" to 0,
			"snapshot" to 0,
			"alpha" to 1,
			"beta" to 2,
			"preview" to 3,
			"pre" to 3,
			"rc" to 3,
			"ea" to 1,
		)

		/** Total: anything that is not a dotted numeric version gives `null`. */
		fun parse(text: String, scheme: VersionScheme = VersionScheme.GENERIC): Version? {
			var token = text.dropWhile(::space).takeWhile { !space(it) }
			val skip = if (token.startsWith("go")) {
				2
			} else if (token.startsWith("v")) {
				1
			} else {
				0
			}
			if (skip > 0 && token.length > skip && digit(token[skip])) token = token.substring(skip)

			var end = digits(token, 0)
			if (end == 0 || token.getOrNull(end) == ':') return null
			val segments = mutableListOf(token.substring(0, end))
			while (end + 1 < token.length && token[end] in "._" && digit(token[end + 1])) {
				val next = digits(token, end + 1)
				segments += token.substring(end + 1, next)
				end = next
			}
			if (segments.any { it.length > MAX_DIGITS }) return null
			var nums = segments.map { it.toLong() }

			val rest = token.substring(end)
			var pre: PreRelease? = null
			var revision = ""
			if (rest.isNotEmpty()) {
				val marker = marker(rest)
				when {
					marker != null -> {
						pre = marker.first
						revision = rest.substring(marker.second)
					}

					rest[0] in "-+~._(" -> revision = rest

					else -> return null
				}
			}
			if (scheme == VersionScheme.JAVA && nums.size >= 2 && nums[0] == 1L && nums[1] <= 8L) {
				nums = nums.drop(1)
			}
			return Version(nums, pre, revision)
		}

		/** Core order of two values; text that is not a version is only ever equal to itself. */
		fun order(a: String, b: String, scheme: VersionScheme = VersionScheme.GENERIC): Order {
			val x = parse(a, scheme)
			val y = parse(b, scheme)
			if (x == null || y == null) {
				return if (x == null && y == null && a == b) Order.EQUAL else Order.UNORDERED
			}
			return when (x.compareCore(y)) {
				-1 -> Order.LESS
				1 -> Order.GREATER
				else -> Order.EQUAL
			}
		}

		private fun marker(rest: String): Pair<PreRelease, Int>? {
			var i = if (rest[0] in "-.~_") 1 else 0
			val word = words.firstOrNull { rest.startsWith(it.first, i, ignoreCase = true) }
			if (word != null) {
				i += word.first.length
				if (i + 1 < rest.length && rest[i] == '.' && digit(rest[i + 1])) i++
				val end = digits(rest, i)
				val number = rest.substring(i, end).takeIf { it.length in 1..MAX_DIGITS }
				return if (end < rest.length && letter(rest[end])) {
					null
				} else {
					PreRelease(word.second, number?.toLong() ?: 0L) to end
				}
			}
			val rank = when (rest[0].lowercaseChar()) {
				'a' -> 1
				'b' -> 2
				'c' -> 3
				else -> return null
			}
			val end = digits(rest, 1)
			if (end == 1 || (end < rest.length && letter(rest[end]))) return null
			val number = rest.substring(1, end).takeIf { it.length <= MAX_DIGITS }
			return PreRelease(rank, number?.toLong() ?: 0L) to end
		}

		private fun digits(s: String, from: Int): Int {
			var i = from
			while (i < s.length && digit(s[i])) i++
			return i
		}

		private fun digit(c: Char) = c in '0'..'9'

		private fun letter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

		private fun space(c: Char) = c == ' ' || c in '\t'..'\r'
	}
}
