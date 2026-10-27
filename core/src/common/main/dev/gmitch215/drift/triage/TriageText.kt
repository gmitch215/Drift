package dev.gmitch215.drift.triage

/** The text form of a [TriageResult]: tables for facts, rules and claims, then the draft rule. */
object TriageText {
    fun render(r: TriageResult): String {
        val sb = StringBuilder()
        sb.append("Triage of issue text ").append(r.body.take(SHORT)).append('\n')
        if (r.author != null) sb.append("Author ").append(r.author).append('\n')
        if (r.truncated) sb.append("Only the first ${Triage.MAX_BODY} characters were read\n")
        sb.append('\n')
        section(sb, "Facts", r.facts.size)
        table(
            sb,
            r.facts.map {
                listOf(
                    it.key,
                    clip(it.value),
                    it.side.id,
                    it.confidence.toString(),
                    it.via.id,
                    it.span.toString(),
                )
            },
        )
        for (f in r.facts.filter { it.alternatives.isNotEmpty() }) {
            val also = f.alternatives.joinToString(", ")
            sb.append("  ${f.key} (${f.side.id}) also read as $also\n")
        }
        sb.append('\n')
        section(sb, "Rules", r.rules.size)
        table(
            sb,
            r.rules.map {
                listOf(
                    it.ruleId,
                    it.grade.id,
                    it.tier?.name?.lowercase() ?: "-",
                    it.confidence.toString(),
                    it.severity.id,
                )
            },
        )
        for (h in r.rules) {
            val why = listOfNotNull(
                h.facts.takeIf { it.isNotEmpty() }?.let { "facts ${it.joinToString(", ")}" },
                h.symptoms.takeIf { it.isNotEmpty() }
                    ?.let { s -> "text ${s.joinToString(", ") { "\"$it\"" }}" },
            )
            sb.append("  ${h.ruleId}: ${why.joinToString("; ")}\n")
        }
        sb.append('\n')
        section(sb, "Claims", r.claims.size)
        for (c in r.claims) {
            sb.append("  line ${c.line} (${c.confidence}): ${c.excerpt}\n")
            sb.append("    says: ${c.says}\n")
            sb.append("    Drift: ${c.knowledge}\n")
            sb.append("    source: ${c.source}\n")
        }
        sb.append('\n')
        r.notEnvironment?.let {
            sb.append("Not environment (${it.signal.id}) at line ${it.line}: ${it.text}\n\n")
        }
        if (r.ask.isNotEmpty()) {
            section(sb, "Ask", r.ask.size)
            for (a in r.ask) {
                sb.append("  ${a.family}: ${a.piped()}\n")
                sb.append("    ${a.sentence()}\n")
            }
            sb.append('\n')
        }
        val d = r.draft
        if (d == null) {
            sb.append("Draft rule: none\n")
        } else {
            sb.append("Draft rule ${d.id}: ${d.disposition.id} (confidence ${d.confidence})\n")
            for (n in d.notes) sb.append("  note: $n\n")
            sb.append(d.yaml)
        }
        return sb.toString()
    }

    private fun section(sb: StringBuilder, name: String, count: Int) {
        sb.append(name).append(" (").append(count).append(")\n")
    }

    private fun table(sb: StringBuilder, rows: List<List<String>>) {
        if (rows.isEmpty()) return
        val widths = rows.first().indices.map { c -> rows.maxOf { it[c].length } }
        for (row in rows) {
            sb.append("  ")
            row.forEachIndexed { c, cell ->
                sb.append(if (c == row.lastIndex) cell else cell.padEnd(widths[c] + 2))
            }
            sb.append('\n')
        }
    }

    private fun clip(v: String) = if (v.length > MAX_VALUE) v.take(MAX_VALUE) + "..." else v

    private const val SHORT = 12
    private const val MAX_VALUE = 48
}
