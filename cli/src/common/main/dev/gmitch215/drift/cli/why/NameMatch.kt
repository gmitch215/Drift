package dev.gmitch215.drift.cli.why

import dev.gmitch215.drift.diff.AttributeDiff
import dev.gmitch215.drift.symptom.Symptom
import dev.gmitch215.drift.symptom.SymptomKind

/**
 * Finds the name of a difference in the error text: a tool, an environment variable, a locked
 * package or a limit. Plain words only; the same text always gives the same match.
 */
internal object NameMatch {
    private val STOP = setOf(
        "the", "and", "for", "not", "set", "version", "limit", "variable", "locked", "package",
        "file", "system", "operating", "error", "failed", "command", "found", "such", "from",
        "with", "this", "that", "are", "was", "you", "your", "no",
    )
    private val TEXT_KINDS = setOf(SymptomKind.SIGNATURE, SymptomKind.SIGNAL, SymptomKind.TRANSPORT)
    private val LIMIT_WORDS = mapOf("nofile" to listOf("files"))

    fun tokens(failure: List<Symptom>?): List<String> =
        failure.orEmpty().filter { it.kind in TEXT_KINDS }.flatMap { words(it.value) }.distinct()

    /** The first token of [tokens] that names [change], or null. */
    fun match(change: AttributeDiff, label: String, tokens: List<String>): String? {
        val names = names(change.path, label)
        return tokens.firstOrNull { it in names }
    }

    private fun names(path: String, label: String): Set<String> {
        val parts = path.split('.')
        val own = when (parts[0]) {
            "tool" -> listOfNotNull(parts.getOrNull(1))

            "env" -> listOf(parts.drop(1).joinToString("."))

            "project" -> listOf(parts.drop(2).joinToString("."))

            "limits" -> {
                val key = parts.getOrNull(1).orEmpty()
                listOf(key) + LIMIT_WORDS[key].orEmpty()
            }

            else -> return emptySet()
        }
        return (own.map { it.lowercase() } + labelWords(label)).filter { usable(it) }.toSet()
    }

    private fun labelWords(label: String) =
        label.lowercase().split(' ', '(', ')', '-').filter { usable(it) }

    private fun usable(word: String) = word.length >= 3 && word !in STOP

    private fun words(text: String): List<String> {
        val out = mutableListOf<String>()
        val run = StringBuilder()

        fun flush() {
            val token = run.toString().trim('.')
            run.clear()
            if (usable(token)) out += token
            val base = token.substringAfterLast('/')
            if (base != token && usable(base)) out += base
        }

        for (c in text) {
            val word = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_./"
            if (word) run.append(c.lowercaseChar()) else flush()
        }
        flush()
        return out
    }
}
