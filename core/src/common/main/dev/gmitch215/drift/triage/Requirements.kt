package dev.gmitch215.drift.triage

/**
 * Reads a stated requirement and the version found against it from one line: `requires PHP
 * >= 8.1.0. You are running 7.4.33`, `Expected version ">=18". Got "16.20.0"`, `3.8.10 not in
 * '>=3.9'`. The requirement becomes `tool.<name>.required` (operator kept, a constraint and not
 * a version of an environment) and the version found becomes the failing `tool.<name>.version`.
 */
internal class Requirements(private val ex: Extractor) {
    private val doc = ex.doc

    private class Bound(val field: VersionField, val value: String, val col: Int, val length: Int)

    fun run(i: Int) {
        val low = doc.lower[i]
        val raw = doc.lines[i]
        val bounds = mutableListOf<Bound>()
        for (phrase in Names.requirePhrases) {
            var at = low.indexOf(phrase)
            while (at >= 0) {
                if (Tokens.wordAt(low, at, phrase) && Tokens.endsAt(low, at + phrase.length)) {
                    bound(raw, low, at + phrase.length, phrase in Names.requireUnnamed)?.let {
                        bounds +=
                            it
                    }
                }
                at = low.indexOf(phrase, at + 1)
            }
        }
        val first = bounds.firstOrNull() ?: return
        for (b in bounds) {
            val key = b.field.key.removeSuffix(".version") + ".required"
            ex.emit(i, key, b.value, b.col, b.length, Via.PATTERN, side = Side.FAILING)
        }
        for (phrase in Names.runningPhrases) {
            var at = low.indexOf(phrase)
            while (at >= 0) {
                if (Tokens.wordAt(low, at, phrase) && Tokens.endsAt(low, at + phrase.length)) {
                    found(i, raw, low, at + phrase.length, first.field)
                }
                at = low.indexOf(phrase, at + 1)
            }
        }
    }

    private fun skip(low: String, from: Int): Int {
        var j = from
        while (j < low.length && low[j] in " \t:\"'`(") j++
        return j
    }

    private fun fill(low: String, j: Int) = Names.requireFill.firstOrNull {
        Tokens.wordAt(low, j, it) && Tokens.endsAt(low, j + it.length)
    }

    private fun bound(raw: String, low: String, from: Int, borrow: Boolean): Bound? {
        var j = from
        var field: VersionField? = null
        var words = 0
        while (true) {
            j = skip(low, j)
            val named = ex.nameAt(low, j)
            val word = fill(low, j)
            if (named != null && field == null) {
                field = named.first
                j = named.second
            } else if (word != null && ++words <= MAX_FILL) {
                j += word.length
            } else {
                break
            }
        }
        val op = Names.requireOps.firstOrNull { low.startsWith(it, j) } ?: ""
        j += op.length
        while (j < low.length && low[j] in " \"'`") j++
        val f = field ?: (if (borrow && op.isNotEmpty()) lineTool(low) else null) ?: return null
        val v = Tokens.version(raw, j, f.scheme) ?: return null
        return Bound(f, op + v.first, j, v.first.length)
    }

    private fun found(i: Int, raw: String, low: String, from: Int, fallback: VersionField) {
        var j = skip(low, from)
        var field = fallback
        ex.nameAt(low, j)?.let {
            field = it.first
            j = skip(low, it.second)
        }
        for (w in Names.gapWords) {
            if (Tokens.wordAt(low, j, w) &&
                Tokens.endsAt(low, j + w.length)
            ) {
                j = skip(low, j + w.length)
            }
        }
        val v = Tokens.version(raw, j, field.scheme) ?: return
        if (!v.first.contains('.')) return
        val col = if (raw[j] == 'v' || raw[j] == 'V') j + 1 else j
        ex.emitVersion(i, field, v.first, col, Via.PATTERN, null, Side.FAILING)
    }

    /** The one tool the line names, or null when it names none or several. */
    private fun lineTool(low: String): VersionField? {
        val seen = ex.names.filter { (n, _) -> Tokens.hasWord(low, n) }.map { it.second }.toSet()
        return seen.singleOrNull()
    }

    private companion object {
        const val MAX_FILL = 4
    }
}
