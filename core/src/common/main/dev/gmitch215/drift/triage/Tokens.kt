package dev.gmitch215.drift.triage

import dev.gmitch215.drift.diff.Version
import dev.gmitch215.drift.diff.VersionScheme

/** Character scanning shared by the extractors; no regex, so every target reads text alike. */
internal object Tokens {
    private val preWords =
        listOf("alpha", "beta", "preview", "snapshot", "nightly", "dev", "pre", "rc", "ea")

    fun isDigit(c: Char) = c in '0'..'9'

    fun isAlnum(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    fun lower(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in 'A'..'Z') c + 32 else c)
        return sb.toString()
    }

    /** True when no letter or digit follows index [end] of [text]. */
    fun endsAt(text: String, end: Int) = end >= text.length || !isAlnum(text[end])

    /** The first of [words] that starts at [at] and ends there, or null. */
    fun gapWordAt(low: String, at: Int, words: List<String>): String? =
        words.firstOrNull { low.startsWith(it, at) && endsAt(low, at + it.length) }

    /** True when [word] sits at [at] in [lowerText] with no letter or digit glued before it. */
    fun wordAt(lowerText: String, at: Int, word: String): Boolean {
        if (!lowerText.startsWith(word, at)) return false
        return at == 0 || !isAlnum(lowerText[at - 1])
    }

    /** True when [word] appears in [lowerText] on word boundaries. */
    fun hasWord(lowerText: String, word: String): Boolean {
        var i = lowerText.indexOf(word)
        while (i >= 0) {
            val end = i + word.length
            val left = i == 0 || !isAlnum(lowerText[i - 1])
            val right = end == lowerText.length || !isAlnum(lowerText[end])
            if (left && right) return true
            i = lowerText.indexOf(word, i + 1)
        }
        return false
    }

    /** Index of the last whole-word hit of any of [words] before [limit], or -1. */
    fun lastWord(lowerText: String, words: List<String>, limit: Int): Int {
        var best = -1
        for (w in words) {
            var i = lowerText.indexOf(w)
            while (i in 0 until limit) {
                val end = i + w.length
                val left = i == 0 || !isAlnum(lowerText[i - 1])
                val right = end >= lowerText.length || !isAlnum(lowerText[end])
                if (left && right && i > best) best = i
                i = lowerText.indexOf(w, i + 1)
            }
        }
        return best
    }

    private fun digitsEnd(s: String, from: Int): Int {
        var i = from
        while (i < s.length && isDigit(s[i])) i++
        return i
    }

    /**
     * A dotted version at [from] (a leading `v` is skipped) as the token and its end index, or
     * null. A token keeps its pre-release word and a `_NNN` update number; `+build` and a Ruby
     * `pNNN` patch level (`3.0.2p107`) are skipped.
     * A lone number of four digits or more (a year) and a date are not versions, and the text
     * must parse with [Version.parse].
     */
    fun version(
        s: String,
        from: Int,
        scheme: VersionScheme = VersionScheme.GENERIC,
    ): Pair<String, Int>? {
        var start = from
        if (start + 1 < s.length && (s[start] == 'v' || s[start] == 'V') && isDigit(s[start + 1])) {
            start++
        }
        if (start >= s.length || !isDigit(s[start])) return null
        if (start > 0 && (isDigit(s[start - 1]) || s[start - 1] == '.')) return null
        var end = digitsEnd(s, start)
        var segments = 1
        while (end + 1 < s.length && s[end] == '.' && isDigit(s[end + 1])) {
            end = digitsEnd(s, end + 1)
            segments++
        }
        if (segments == 1 && end - start >= 4) return null
        if (segments == 1 && end + 1 < s.length && s[end] == '-' && isDigit(s[end + 1])) return null
        var token = end
        if (end + 1 < s.length && s[end] == '_' && isDigit(s[end + 1])) {
            end = digitsEnd(s, end + 1)
            token = end
        } else if (segments >= 3 && end + 1 < s.length && s[end] == 'p' && isDigit(s[end + 1])) {
            token = end
            end = digitsEnd(s, end + 1)
        } else {
            val pre = preAt(s, end)
            if (pre > end) {
                end = pre
                token = end
            }
        }
        if (end + 1 < s.length && s[end] == '+' && isDigit(s[end + 1])) end = digitsEnd(s, end + 1)
        if (end < s.length && isAlnum(s[end])) return null
        val text = s.substring(start, token)
        return if (Version.parse(text, scheme) != null) text to end else null
    }

    private fun preAt(s: String, end: Int): Int {
        if (end >= s.length || s[end] !in "-.") return end
        val lower = lower(s.substring(end + 1, minOf(s.length, end + 12)))
        val word = preWords.firstOrNull { lower.startsWith(it) } ?: return end
        var i = end + 1 + word.length
        if (i + 1 < s.length && s[i] == '.' && isDigit(s[i + 1])) i++
        return digitsEnd(s, i)
    }
}
