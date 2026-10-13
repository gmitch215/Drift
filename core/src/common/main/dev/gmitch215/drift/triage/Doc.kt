package dev.gmitch215.drift.triage

/**
 * An issue body split into lines, with fenced code blocks and heading sections marked. A heading
 * is a Markdown `#` line or a line that is only bold text, which covers a GitHub issue form
 * (`### Label`) and a GitLab description template (`## Label` or `**Label**`).
 */
internal class Doc(text: String) {
    val lines: List<String> = text.split('\n')
    val lower: List<String> = lines.map(Tokens::lower)
    val inCode = BooleanArray(lines.size)
    val fence = BooleanArray(lines.size)

    /** Heading text of line `i`, or null when the line is not a heading. */
    val heading = arrayOfNulls<String>(lines.size)

    /** Heading line of the section that holds line `i`, or -1. */
    val sectionStart = IntArray(lines.size) { -1 }

    init {
        var open = NONE
        var current = -1
        for (i in lines.indices) {
            val t = lines[i].trim()
            val mark = when {
                t.startsWith("```") -> '`'
                t.startsWith("~~~") -> '~'
                else -> NONE
            }
            if (mark != NONE && (open == NONE || open == mark)) {
                fence[i] = true
                open = if (open == NONE) mark else NONE
                sectionStart[i] = current
                continue
            }
            inCode[i] = open != NONE
            if (open == NONE) {
                val h = headingText(t)
                if (h != null) {
                    heading[i] = h
                    current = i
                    continue
                }
            }
            sectionStart[i] = current
        }
    }

    /** Lowercase label of the section that holds line [i]. */
    fun sectionLabel(i: Int): String? = sectionStart[i].takeIf { it >= 0 }?.let { heading[it] }

    /** End (exclusive) of the section that starts at heading line [h]. */
    fun sectionEnd(h: Int): Int {
        var i = h + 1
        while (i < lines.size && heading[i] == null) i++
        return i
    }

    /** True when line [i] sits under a heading that names an environment block. */
    fun inBag(i: Int): Boolean {
        val label = sectionLabel(i) ?: return false
        return Names.bagLabels.any { label.contains(it) }
    }

    private fun headingText(t: String): String? {
        if (t.startsWith("#")) {
            val body = t.trimStart('#')
            val hashes = t.length - body.length
            if (hashes in 1..6 && body.startsWith(" ")) {
                return clean(body.trim().trimEnd('#'))
            }
        }
        for (m in listOf("**", "__")) {
            if (t.length > 4 && t.startsWith(m) && t.endsWith(m) && t.length <= 80) {
                val inner = t.substring(2, t.length - 2)
                if (!inner.contains(m)) return clean(inner.trimEnd(':'))
            }
        }
        return null
    }

    companion object {
        private const val NONE = ' '

        /** A label or heading in a form to compare: lowercase, no Markdown marks, one space. */
        fun clean(s: String): String {
            val sb = StringBuilder()
            for (c in Tokens.lower(s)) {
                when {
                    c in "*`|>" -> Unit

                    c == '\t' || c == ' ' || c == '_' || c == '-' ->
                        if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')

                    else -> sb.append(c)
                }
            }
            return sb.toString().trim().trimEnd(':').trim()
        }
    }
}
