package dev.gmitch215.drift.triage

/**
 * Decides whether a value belongs to the environment where the report says it worked or the one
 * where it failed. The nearest marker before the value in its clause wins; no marker means the
 * failing side, because a report mostly describes what broke.
 */
internal object Sides {
    private val labelPassing = listOf(
        "working version",
        "last working",
        "last good",
        "worked",
        "previous",
        "before",
        "baseline",
        "good version",
        "passing",
    )

    private val passingAfter = listOf(
        "works",
        "worked",
        "working",
        "is fine",
        "passes",
        "passed",
        "fine",
        "does not have this problem",
        "doesn't have this problem",
        "does not have this issue",
        "doesn't have this issue",
        "is not affected",
        "isn't affected",
        "is unaffected",
    )
    private val failingAfter = listOf("fails", "failed", "breaks", "crashes", "is broken", "broken")

    private fun starts(text: String, word: String) =
        text.startsWith(word) && (text.length == word.length || !Tokens.isAlnum(text[word.length]))

    /** A verdict word right after the value (`Node 18 works`) beats the words ahead of it. */
    fun of(low: String, col: Int, end: Int, label: String?): Side {
        if (label != null && labelPassing.any { label.contains(it) }) return Side.PASSING
        if (col > low.length) return Side.FAILING
        val tail = low.substring(minOf(end, low.length)).trimStart(' ', ')', '`', '"', '\'')
        if (passingAfter.any { starts(tail, it) }) return Side.PASSING
        if (failingAfter.any { starts(tail, it) }) return Side.FAILING
        var start = 0
        for (b in Names.clauseBreaks) {
            val at = low.lastIndexOf(b, col)
            if (at >= 0 && at + b.length <= col) start = maxOf(start, at + b.length)
        }
        for (b in listOf("->", "=>", "\u2192")) {
            val at = low.lastIndexOf(b, col)
            if (at >= 0 && at + b.length <= col) start = maxOf(start, at + b.length)
        }
        var clause = low.substring(start, col)
        for (n in Names.negated) clause = clause.replace(n, " ".repeat(n.length))
        val pass = Tokens.lastWord(clause, Names.passing, clause.length)
        val fail = Tokens.lastWord(clause, Names.failing, clause.length)
        val from = Tokens.lastWord(clause, listOf("from"), clause.length)
        if (from > maxOf(pass, fail) && low.indexOf(" to ", col) >= 0) return Side.PASSING
        return if (pass > fail) Side.PASSING else Side.FAILING
    }
}
