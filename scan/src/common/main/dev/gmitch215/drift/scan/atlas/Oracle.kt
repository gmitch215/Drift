package dev.gmitch215.drift.scan.atlas

import dev.gmitch215.drift.model.ProbeStatus

/**
 * What can be said about a divergent line. Only [DOCUMENTED_CONTRACT] and [CRASH] are
 * [reportable]; the other two are shown and never offered for filing.
 */
enum class OracleKind(val label: String, val reportable: Boolean, val meaning: String) {
    DOCUMENTED_CONTRACT("documented-contract", true, "a quoted sentence states the expected value"),
    CROSS_VERSION_CHANGE(
        "cross-version-change",
        false,
        "a target prints another value on another version",
    ),
    CRASH(
        "crash",
        true,
        "a column failed instead of printing values, counted once per probe",
    ),
    NO_CORRECTNESS_CLAIM("no-correctness-claim", false, "no quote says which value is right"),
}

/**
 * One divergent line of a probe at one Kotlin version. [line] is [Oracle.WHOLE_PROBE] when a column
 * failed instead of printing values. [results] groups the targets by what they printed, [changed]
 * lists the targets that print something else on another version, and [contract] is the reference
 * whose quote states the expected value.
 */
class OracleLine(
    val probe: ProbeEntry,
    val version: String,
    val line: String,
    val kind: OracleKind,
    val contract: Reference?,
    val changed: List<String>,
    val results: List<Pair<List<String>, String>>,
) {
    val reportable: Boolean get() = kind.reportable
}

/**
 * Gives every divergent line of the dataset one [OracleKind], from the transcripts and the
 * `decides` lists of the references alone. The same input gives the same text on every target.
 */
object Oracle {
    const val WHOLE_PROBE = "(whole probe)"

    fun lines(dataset: Dataset, version: String): List<OracleLine> =
        dataset.probes.flatMap { lines(it, version) }

    /**
     * A column that failed makes one entry for the probe, whatever its other lines say: a
     * [OracleKind.CRASH], unless the research notes the whole probe as a documented or
     * platform-defined limit, which is [OracleKind.NO_CORRECTNESS_CLAIM]. Among the columns that
     * printed values, a line is decided by a quote that names it, else by a change of value on a
     * target measured on another version, else it carries no claim.
     */
    fun lines(p: ProbeEntry, version: String): List<OracleLine> {
        val cells = p.cells.filter { it.key.version == version }
        val (valued, failed) = cells.partition { it.status == ProbeStatus.OK }
        val out = mutableListOf<OracleLine>()
        if (failed.isNotEmpty()) {
            val results = failed.groupBy { it.lines.first() }
                .map { (text, own) -> own.map { it.key.target } to text }
            val limit = p.info.lines.any { it.line == NOTED_WHOLE && it.cause in LIMITS }
            val kind = if (limit) OracleKind.NO_CORRECTNESS_CLAIM else OracleKind.CRASH
            out += OracleLine(p, version, WHOLE_PROBE, kind, null, emptyList(), results)
        }
        for (i in differingLines(valued.map { it.lines })) {
            val text = valued.firstNotNullOf { it.lines.getOrNull(i) }
            val line = text.substringBefore(" = ")
            val contract = p.references.firstOrNull { line in it.decides }
            val changed = valued.filter { c -> changed(p, c, i) }.map { it.key.target }.distinct()
            val kind = when {
                contract != null -> OracleKind.DOCUMENTED_CONTRACT
                changed.isNotEmpty() -> OracleKind.CROSS_VERSION_CHANGE
                else -> OracleKind.NO_CORRECTNESS_CLAIM
            }
            val results = valued.groupBy { it.lines.getOrNull(i) ?: NO_LINE }
                .map { (value, own) -> own.map { it.key.target } to value }
            out += OracleLine(p, version, line, kind, contract, changed.sorted(), results)
        }
        return out
    }

    private fun changed(p: ProbeEntry, cell: DatasetCell, index: Int): Boolean {
        val value = cell.lines.getOrNull(index)
        return p.cells.any {
            it.key.target == cell.key.target && it.key.version != cell.key.version &&
                it.status == ProbeStatus.OK && it.lines.getOrNull(index) != value
        }
    }

    fun counts(lines: List<OracleLine>): Map<OracleKind, Int> =
        OracleKind.entries.associateWith { k -> lines.count { it.kind == k } }

    /** One entry per kind, with the quote and page for a contract and the targets for the rest. */
    fun describe(lines: List<OracleLine>, indent: String = ""): List<String> = buildList {
        for (kind in OracleKind.entries) {
            val own = lines.filter { it.kind == kind }
            if (own.isEmpty()) continue
            val mark = if (kind.reportable) " (reportable)" else ""
            if (kind == OracleKind.DOCUMENTED_CONTRACT) {
                for ((contract, same) in own.groupBy { it.contract!! }) {
                    addAll(wrap("${kind.label}$mark: ${names(same)}", indent = indent))
                    addAll(wrap("expected: \"${ascii(contract.quote)}\"", indent = "$indent  "))
                    add("$indent  source: ${contract.url}")
                }
                continue
            }
            val (failed, values) = own.partition { it.line == WHOLE_PROBE }
            if (values.isNotEmpty()) {
                addAll(wrap("${kind.label}$mark: ${names(values)}", indent = indent))
                val changed = values.flatMap { it.changed }.distinct().sorted()
                if (changed.isNotEmpty()) {
                    addAll(wrap("changed on: ${changed.joinToString(", ")}", indent = "$indent  "))
                }
            }
            if (failed.isEmpty()) continue
            addAll(wrap("${kind.label}$mark: $WHOLE_PROBE", indent = indent))
            val on = failed.flatMap { f -> f.results.flatMap { it.first } }.distinct().sorted()
            addAll(wrap("failed on: ${on.joinToString(", ")}", indent = "$indent  "))
            failed.flatMap { f -> f.results.map { it.second } }.distinct().forEach {
                addAll(wrap("failure: ${ascii(it)}", indent = "$indent  "))
            }
        }
    }

    private fun names(lines: List<OracleLine>): String = lines.joinToString(", ") { it.line }

    private const val NO_LINE = "(no line)"

    private const val NOTED_WHOLE = "whole probe"

    private val LIMITS = setOf("a", "b")
}
