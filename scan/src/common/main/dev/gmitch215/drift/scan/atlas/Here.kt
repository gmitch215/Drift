package dev.gmitch215.drift.scan.atlas

import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.model.ProbeStatus

/** This device against the recorded columns of one Kotlin version, as `atlas here` prints it. */
class Here(private val comparison: Comparison, private val version: String) {
    /** True when a probe ran here and its transcript matches no recorded column. */
    val differsEverywhere: Boolean
        get() = comparison.rows.any { it.verdict == Verdict.DIFFERENT && ran(it.probe.id) }

    fun render(): String = buildString {
        append(comparison.render(Detail.SUMMARY))
        append('\n')
        for (row in comparison.rows) {
            if (row.verdict == Verdict.SAME || row.deviceLines == null) continue
            block(this, row)
        }
        if (comparison.notInDataset.isNotEmpty()) {
            appendLine("not in the dataset: ${comparison.notInDataset.joinToString(", ")}")
        }
    }

    private fun ran(id: String): Boolean = comparison.device.cells[id]?.status == ProbeStatus.OK

    private fun block(out: StringBuilder, row: ProbeComparison) {
        val phrase = when (row.verdict) {
            Verdict.MIXED -> "matches some columns"
            else -> "matches no column"
        }
        out.appendLine("${row.probe.id} [${row.probe.classification.label}] $phrase")
        val matches = row.matches.joinToString(", ") { it.label }.ifEmpty { "none" }
        out.appendLine("  matches: $matches")
        out.appendLine("  differs: ${row.differs.joinToString(", ") { it.label }}")
        if (!ran(row.probe.id)) {
            val kind = OracleKind.NO_CORRECTNESS_CLAIM.label
            out.appendLine("  kind: $kind (the probe did not run here)")
            return
        }
        val deviceLines = row.deviceLines ?: return
        val oracle = Oracle.lines(row.probe, version)
        val groups = row.differs.groupBy { row.probe.cell(it)!!.hash }.values
        for (group in groups) {
            val theirs = row.probe.cell(group.first())!!.lines
            val label = group.joinToString(", ") { it.label }
            for (i in differingLines(listOf(deviceLines, theirs))) {
                val mine = deviceLines.getOrNull(i) ?: "(no line)"
                val kind = kind(row.probe, oracle, mine)
                val reportable = if (kind.reportable) " (reportable)" else ""
                val other = theirs.getOrNull(i) ?: "(no line)"
                out.appendLine("  line ${i + 1} (this device): ${ascii(mine)}")
                out.appendLine("  line ${i + 1} ($label): ${ascii(other)}")
                out.appendLine("  kind: ${kind.label}$reportable")
            }
        }
    }

    /** A quoted contract decides the line; else the dataset oracle for the same line name. */
    private fun kind(p: ProbeEntry, oracle: List<OracleLine>, text: String): OracleKind {
        val name = text.substringBefore(" = ")
        if (p.references.any { name in it.decides }) return OracleKind.DOCUMENTED_CONTRACT
        return oracle.firstOrNull { it.line == name }?.kind ?: OracleKind.NO_CORRECTNESS_CLAIM
    }
}
