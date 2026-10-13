package dev.gmitch215.drift.scan.atlas

import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.model.ProbeStatus

enum class ChangeKind { CHANGED, APPEARED, REMOVED }

/** One probe on one target whose transcript differs between two Kotlin versions. */
class ProbeChange(
    val probe: ProbeEntry,
    val target: String,
    val before: DatasetCell,
    val after: DatasetCell,
) {
    val kind: ChangeKind = when {
        before.status == ProbeStatus.OK && after.status != ProbeStatus.OK -> ChangeKind.REMOVED
        before.status != ProbeStatus.OK && after.status == ProbeStatus.OK -> ChangeKind.APPEARED
        else -> ChangeKind.CHANGED
    }

    val lines: List<Int> get() = differingLines(listOf(before.lines, after.lines))
}

/**
 * What changed between two compiler versions on the targets measured on both. A target measured on
 * only one of the versions is listed and never compared.
 */
class VersionDiff(val dataset: Dataset, from: String, to: String, target: String? = null) {
    val from: String = resolve(dataset, from)
    val to: String = resolve(dataset, to)

    val measuredOnBoth: List<String>
    val onlyFrom: List<String>
    val onlyTo: List<String>
    val targets: List<String>
    val changes: List<ProbeChange>

    init {
        if (this.from == this.to) {
            throw AtlasException(BadDiff("--from and --to name the same version ${this.from}"))
        }
        val had = dataset.columns.filter { it.version == this.from }.map { it.target }
        val has = dataset.columns.filter { it.version == this.to }.map { it.target }
        measuredOnBoth = had.filter { it in has }
        onlyFrom = had - measuredOnBoth.toSet()
        onlyTo = has - measuredOnBoth.toSet()
        if (target != null && target !in measuredOnBoth) {
            val known = measuredOnBoth.joinToString(", ").ifEmpty { "none" }
            throw AtlasException(
                BadDiff("no column $target on both ${this.from} and ${this.to}; both have: $known"),
            )
        }
        if (measuredOnBoth.isEmpty()) {
            throw AtlasException(
                BadDiff("no target is measured on both ${this.from} and ${this.to}"),
            )
        }
        targets = if (target == null) measuredOnBoth else listOf(target)
        changes = dataset.probes.flatMap { p ->
            targets.mapNotNull { t ->
                val before = p.cell(ColumnKey(t, this.from))!!
                val after = p.cell(ColumnKey(t, this.to))!!
                if (before.hash == after.hash) null else ProbeChange(p, t, before, after)
            }
        }
    }

    val changedProbes: List<ProbeEntry> get() = changes.map { it.probe }.distinct()

    fun count(kind: ChangeKind, target: String): Int =
        changes.count { it.kind == kind && it.target == target }

    fun render(detail: Detail): String = buildString {
        appendLine(
            "kotlin $from to $to: ${changedProbes.size} of ${dataset.probes.size} probes change",
        )
        appendLine("targets compared: ${targets.joinToString(", ")}")
        if (onlyFrom.isNotEmpty()) {
            wrap("measured on $from only: ${onlyFrom.joinToString(", ")}").forEach {
                appendLine(it)
            }
        }
        if (onlyTo.isNotEmpty()) {
            wrap("measured on $to only: ${onlyTo.joinToString(", ")}").forEach { appendLine(it) }
        }
        appendLine("per target:")
        for (t in targets) {
            val changed = changes.count { it.target == t }
            val appeared = count(ChangeKind.APPEARED, t)
            val removed = count(ChangeKind.REMOVED, t)
            appendLine(
                "  $t: $changed of ${dataset.probes.size} change " +
                    "($appeared became available, $removed became unavailable)",
            )
        }
        if (detail == Detail.SUMMARY) return@buildString
        for (p in changedProbes) {
            appendLine()
            appendLine("${p.id} [${p.classification.label}]")
            val own = changes.filter { it.probe == p }
            for (group in own.groupBy { it.before.hash + it.after.hash }.values) {
                block(this, group)
            }
        }
        if (detail == Detail.FULL) {
            val same = dataset.probes - changedProbes.toSet()
            appendLine()
            appendLine("unchanged on every compared target:" + if (same.isEmpty()) " none" else "")
            wrap(same.joinToString(", ") { it.id }, indent = "  ").forEach { appendLine(it) }
        }
    }

    private fun block(out: StringBuilder, group: List<ProbeChange>) {
        val first = group.first()
        val kind = when (first.kind) {
            ChangeKind.APPEARED -> "became available"
            ChangeKind.REMOVED -> "became unavailable"
            ChangeKind.CHANGED -> "changed"
        }
        out.appendLine("  ${group.joinToString(", ") { it.target }}: $kind")
        if (first.kind != ChangeKind.CHANGED) {
            val gone = if (first.kind == ChangeKind.APPEARED) first.before else first.after
            val version = if (first.kind == ChangeKind.APPEARED) from else to
            wrap("$version: ${ascii(gone.lines.first())}", indent = "    ").forEach {
                out.appendLine(it)
            }
            return
        }
        for (i in first.lines) {
            val was = ascii(first.before.lines.getOrNull(i) ?: "(no line)")
            val now = ascii(first.after.lines.getOrNull(i) ?: "(no line)")
            out.appendLine("    line ${i + 1} ($from): $was")
            out.appendLine("    line ${i + 1} ($to): $now")
        }
    }

    private companion object {
        fun resolve(dataset: Dataset, version: String): String =
            dataset.versions.firstOrNull { it.equals(version, ignoreCase = true) }
                ?: throw AtlasException(
                    BadDiff(
                        "no column in the dataset was measured on kotlin $version; " +
                            "versions: ${dataset.versions.joinToString(", ")}",
                    ),
                )
    }
}
