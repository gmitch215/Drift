package dev.gmitch215.drift.symptom

import dev.gmitch215.drift.redact.Redactor

/**
 * Turns an error message into text that is equal for the same failure on two runs.
 *
 * Matching is plain character scanning over ASCII classes, never regex, so the result is
 * identical on every target. Rules, tried left to right at each position:
 * - a path under `/tmp/`, `/var/tmp/`, `/var/folders/` or `/private/var/` becomes `<tmp>`
 * - `yyyy-mm-dd`, optionally followed by `T` or a space and `hh:mm:ss[.fff][Z]`, and a bare
 *   `hh:mm:ss[.fff][Z]` become `<time>`
 * - a UUID becomes `<uuid>`; `0x` hex and any run of 12 or more hex digits become `<hex>`
 * - a number glued to a unit (`ms`, `s`, `sec`, `min`, `h` and their long forms) becomes `<dur>`
 * - digits after `pid`, `process`, `attempt`, `retry`, `try`, `port`, `line`, `uid`, `tid` or `#`
 *   become `<n>`, as do `[3/5]` and `(3/5)` (as `<n>/<n>`) and `name[1234]`
 * - `:digits[:digits]` after a letter, `>`, `]` or an IPv4 address (ports, line and column)
 *   becomes `:<n>`
 *
 * Secrets are masked by [Redactor] first, runs of whitespace collapse to one space and the
 * result is cut at [MAX_LENGTH] characters.
 */
object Signature {
	const val MAX_LENGTH = 200

	private val tmpRoots = listOf("/tmp/", "/var/tmp/", "/var/folders/", "/private/var/")
	private val counterWords =
		setOf("pid", "process", "attempt", "retry", "try", "port", "line", "uid", "tid")
	private val units = listOf(
		"ms", "seconds", "secs", "sec", "minutes", "mins", "min", "hours", "hrs", "hr",
		"s", "m", "h",
	)

	fun normalize(message: String): String {
		val s = Redactor.redactText(message)
		val out = StringBuilder()
		var i = 0
		while (i < s.length) {
			val hit = at(s, i)
			if (hit == null) {
				out.append(s[i])
				i++
			} else {
				out.append(hit.first)
				i = hit.second
			}
		}
		return clip(collapse(out), MAX_LENGTH)
	}

	internal fun asciiLower(s: String): String {
		if (s.none { it in 'A'..'Z' }) return s
		return CharArray(s.length) { if (s[it] in 'A'..'Z') s[it] + 32 else s[it] }.concatToString()
	}

	internal fun isAlnum(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

	internal fun clip(s: String, max: Int): String {
		if (s.length <= max) return s
		return s.substring(0, if (s[max - 1].isHighSurrogate()) max - 1 else max)
	}

	private fun isDigit(c: Char) = c in '0'..'9'

	private fun isHex(c: Char) = isDigit(c) || c in 'a'..'f' || c in 'A'..'F'

	private fun isSpace(c: Char) = c == ' ' || c.code in 9..13

	private fun collapse(s: CharSequence): String {
		val out = StringBuilder()
		var gap = false
		for (c in s) {
			if (isSpace(c)) {
				gap = out.isNotEmpty()
			} else {
				if (gap) out.append(' ')
				gap = false
				out.append(c)
			}
		}
		return out.toString()
	}

	private fun at(s: String, i: Int): Pair<String, Int>? {
		val c = s[i]
		return when {
			c == '/' -> tmpEnd(s, i)?.let { "<tmp>" to it }
			c == ':' -> colonEnd(s, i)?.let { ":<n>" to it }
			i == 0 || !isAlnum(s[i - 1]) -> if (isHex(c)) token(s, i) else null
			else -> null
		}
	}

	private fun token(s: String, i: Int): Pair<String, Int>? {
		timeEnd(s, i)?.let { return "<time>" to it }
		uuidEnd(s, i)?.let { return "<uuid>" to it }
		hexEnd(s, i)?.let { return "<hex>" to it }
		if (!isDigit(s[i])) return null
		durationEnd(s, i)?.let { return "<dur>" to it }
		return counter(s, i)
	}

	private fun digitsEnd(s: String, from: Int): Int {
		var j = from
		while (j < s.length && isDigit(s[j])) j++
		return j
	}

	private fun digitsAt(s: String, i: Int, n: Int) =
		i + n <= s.length && (i until i + n).all { isDigit(s[it]) }

	private fun tmpEnd(s: String, i: Int): Int? {
		val before = if (i > 0) s[i - 1] else ' '
		if (isAlnum(before) || before == '.' || before == '-' || before == '_') return null
		if (tmpRoots.none { s.startsWith(it, i) }) return null
		var j = i
		while (j < s.length && s[j] !in " \t'\"),]<>;") j++
		return j
	}

	private fun colonEnd(s: String, i: Int): Int? {
		val before = if (i > 0) s[i - 1] else ' '
		if (!isAlnum(before) && before != '>' && before != ']') return null
		if (isDigit(before) && !dottedQuadBefore(s, i)) return null
		var j = digitsEnd(s, i + 1)
		if (j == i + 1 || j - i - 1 > 6 || (j < s.length && isAlnum(s[j]))) return null
		if (j < s.length && s[j] == ':') {
			val k = digitsEnd(s, j + 1)
			if (k > j + 1 && k - j - 1 <= 6 && (k == s.length || !isAlnum(s[k]))) j = k
		}
		return j
	}

	private fun dottedQuadBefore(s: String, colon: Int): Boolean {
		var j = colon - 1
		var dots = 0
		while (j >= 0 && (isDigit(s[j]) || s[j] == '.')) {
			if (s[j] == '.') dots++
			j--
		}
		return dots == 3
	}

	private fun clockEnd(s: String, i: Int): Int? {
		if (!digitsAt(s, i, 2) || s.getOrNull(i + 2) != ':' || !digitsAt(s, i + 3, 2)) return null
		if (s.getOrNull(i + 5) != ':' || !digitsAt(s, i + 6, 2)) return null
		var j = i + 8
		if ((s.getOrNull(j) == '.' || s.getOrNull(j) == ',') && j + 1 < s.length &&
			isDigit(s[j + 1])
		) {
			j = digitsEnd(s, j + 1)
		}
		if (s.getOrNull(j) == 'Z') j++
		return j
	}

	private fun timeEnd(s: String, i: Int): Int? {
		clockEnd(s, i)?.let { return it }
		if (!digitsAt(s, i, 4) || s.getOrNull(i + 4) != '-' || !digitsAt(s, i + 5, 2)) return null
		if (s.getOrNull(i + 7) != '-' || !digitsAt(s, i + 8, 2)) return null
		val j = i + 10
		if (s.getOrNull(j) == 'T' || s.getOrNull(j) == ' ') clockEnd(s, j + 1)?.let { return it }
		return j
	}

	private fun uuidEnd(s: String, i: Int): Int? {
		if (i + 36 > s.length) return null
		for (k in 0 until 36) {
			val dash = k == 8 || k == 13 || k == 18 || k == 23
			if (if (dash) s[i + k] != '-' else !isHex(s[i + k])) return null
		}
		return if (i + 36 == s.length || !isAlnum(s[i + 36])) i + 36 else null
	}

	private fun hexEnd(s: String, i: Int): Int? {
		var j = i
		if (s.startsWith("0x", i) || s.startsWith("0X", i)) j += 2
		val from = j
		while (j < s.length && isHex(s[j])) j++
		val long = j - i >= 12
		val prefixed = from > i && j > from
		if (!(long || prefixed) || (j < s.length && isAlnum(s[j]))) return null
		return j
	}

	private fun durationEnd(s: String, i: Int): Int? {
		var j = digitsEnd(s, i)
		if (s.getOrNull(j) == '.' && j + 1 < s.length && isDigit(s[j + 1])) j = digitsEnd(s, j + 1)
		val unit = units.firstOrNull {
			s.startsWith(it, j) && (j + it.length == s.length || !isAlnum(s[j + it.length]))
		}
		return unit?.let { j + it.length }
	}

	private fun counter(s: String, i: Int): Pair<String, Int>? {
		val before = if (i > 0) s[i - 1] else ' '
		val end = digitsEnd(s, i)
		if (before == '#') return "<n>" to end
		if (before == '[' || before == '(') {
			val close = if (before == '[') ']' else ')'
			if (s.getOrNull(end) == '/') {
				val other = digitsEnd(s, end + 1)
				if (other > end + 1 && s.getOrNull(other) == close) return "<n>/<n>" to other
			}
			val named = i >= 2 && isAlnum(s[i - 2])
			if (before == '[' && named && s.getOrNull(end) == ']') return "<n>" to end
			return null
		}
		var j = i - 1
		while (j >= 0 && s[j] in " =:\"'") j--
		if (j == i - 1) return null
		var k = j
		while (k >= 0 && (s[k] in 'a'..'z' || s[k] in 'A'..'Z')) k--
		return if (asciiLower(s.substring(k + 1, j + 1)) in counterWords) "<n>" to end else null
	}
}
