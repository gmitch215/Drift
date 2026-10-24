package dev.gmitch215.drift.symptom

import dev.gmitch215.drift.symptom.Signature.asciiLower

/**
 * The failure a ranking is conditioned on: the text symptoms (signature, signal, transport) as
 * one lowercase text and the exit codes, taken from [symptoms] as read from a log.
 *
 * A phrase matches by plain substring on the lowercase text, at the start of a word, so the
 * result is the same on every target and "oom" does not match "boom". [matchesText] reads a
 * symptom written in prose (a rule's) and counts only its distinctive tokens: a token of four or
 * more characters that holds an underscore, a digit, an inner dot, or a lowercase letter next to
 * an inner capital (`PatternSyntaxException`). A symptom with no such token matches nothing,
 * which keeps a prose symptom from matching on common words.
 */
class FailureSignature(symptoms: List<Symptom>) {
    private val text = symptoms.filter { it.kind in TEXT }
        .joinToString("\n") { asciiLower(it.value) }

    private val exits = symptoms.filter { it.kind == SymptomKind.EXIT }.map { it.value }.toSet()

    val isEmpty: Boolean get() = text.isEmpty() && exits.isEmpty()

    /** [phrase] must be lowercase ASCII. */
    fun mentions(phrase: String): Boolean = startsWord(text, phrase)

    fun hasExit(code: String): Boolean = code in exits

    fun matchesText(symptom: String): Boolean = distinctive(symptom).any { startsWord(text, it) }

    private fun startsWord(haystack: String, phrase: String): Boolean {
        if (phrase.isEmpty()) return false
        var at = haystack.indexOf(phrase)
        while (at >= 0) {
            if (at == 0 || !Signature.isAlnum(haystack[at - 1])) return true
            at = haystack.indexOf(phrase, at + 1)
        }
        return false
    }

    private fun distinctive(symptom: String): List<String> {
        val out = ArrayList<String>()
        val token = StringBuilder()

        fun flush() {
            val t = token.toString().trim('.')
            token.clear()
            if (isDistinctive(t)) out.add(asciiLower(t))
        }
        for (c in symptom) {
            if (Signature.isAlnum(c) || c == '_' || c == '.') token.append(c) else flush()
        }
        flush()
        return out
    }

    private fun isDistinctive(t: String): Boolean {
        if (t.length < 4) return false
        val inner = t.substring(1)
        val mixed = t.any { it in 'a'..'z' } && inner.any { it in 'A'..'Z' }
        return t.any { it == '_' || it in '0'..'9' } || '.' in t || mixed
    }

    private companion object {
        val TEXT = setOf(
            SymptomKind.SIGNATURE,
            SymptomKind.SIGNAL,
            SymptomKind.TRANSPORT,
            SymptomKind.SLOWER,
            SymptomKind.MEMORY,
        )
    }
}
