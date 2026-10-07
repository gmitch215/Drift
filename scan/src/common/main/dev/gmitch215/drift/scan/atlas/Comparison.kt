package dev.gmitch215.drift.scan.atlas

import dev.gmitch215.drift.case.Detail

enum class Verdict { SAME, MIXED, DIFFERENT, NOT_MEASURED }

/** [ProbeComparison.deviceLines] is null when this device has no result for the probe. */
class ProbeComparison(
	val probe: ProbeEntry,
	val deviceLines: List<String>?,
	val matches: List<ColumnKey>,
	val differs: List<ColumnKey>,
) {
	val verdict: Verdict = when {
		deviceLines == null -> Verdict.NOT_MEASURED
		differs.isEmpty() -> Verdict.SAME
		matches.isEmpty() -> Verdict.DIFFERENT
		else -> Verdict.MIXED
	}
}

/** One device transcript against every column of a dataset. Only the rendering has levels. */
class Comparison(val dataset: Dataset, val device: Column) {
	val rows: List<ProbeComparison> = dataset.probes.map { p ->
		val own = device.cells[p.id]
		if (own == null) {
			ProbeComparison(p, null, emptyList(), emptyList())
		} else {
			val (matches, differs) = p.cells.partition { it.hash == own.hash }
			ProbeComparison(
				p,
				own.transcript.removeSuffix("\n").split('\n'),
				matches.map { it.key },
				differs.map { it.key },
			)
		}
	}

	val notInDataset: List<String> =
		(device.cells.keys - dataset.probes.map { it.id }.toSet()).sorted()

	val measured: Int get() = rows.count { it.verdict != Verdict.NOT_MEASURED }

	fun count(verdict: Verdict): Int = rows.count { it.verdict == verdict }

	fun render(detail: Detail): String = buildString {
		summary(this)
		if (detail == Detail.SUMMARY) return@buildString
		append('\n')
		val open = rows.filter { it.verdict != Verdict.SAME }
		for (row in open) block(this, row, detail == Detail.FULL)
		if (notInDataset.isNotEmpty()) {
			val ids = notInDataset.joinToString(", ")
			wrap("not in the dataset: $ids").forEach { appendLine(it) }
		}
		if (detail == Detail.FULL) {
			val same = rows.filter { it.verdict == Verdict.SAME }.map { it.probe.id }
			appendLine("matches every column:" + if (same.isEmpty()) " none" else "")
			wrap(same.joinToString(", "), indent = "  ").forEach { appendLine(it) }
		}
	}

	private fun summary(out: StringBuilder) {
		out.appendLine(
			"this device: ${device.target}, kotlin ${device.version}, $measured probes measured",
		)
		out.appendLine(
			"dataset: ${dataset.probes.size} probes, ${dataset.columns.size} columns: " +
				dataset.columns.joinToString(", ") { it.label },
		)
		if (device.version !in dataset.versions) {
			out.appendLine("note: no dataset column was measured on kotlin ${device.version}")
		}
		out.appendLine("matches every column: ${count(Verdict.SAME)}")
		out.appendLine("matches some columns: ${count(Verdict.MIXED)}")
		out.appendLine("matches no column: ${count(Verdict.DIFFERENT)}")
		out.appendLine("not measured here: ${count(Verdict.NOT_MEASURED)}")
		if (notInDataset.isNotEmpty()) out.appendLine("not in the dataset: ${notInDataset.size}")
		out.appendLine("per column:")
		for (key in dataset.columns) {
			val same = rows.count { key in it.matches }
			out.appendLine("  ${key.label}: $same of $measured probes match")
		}
	}

	private fun block(out: StringBuilder, row: ProbeComparison, lines: Boolean) {
		val verdict = when (row.verdict) {
			Verdict.MIXED -> "matches some columns"
			Verdict.DIFFERENT -> "matches no column"
			else -> "not measured here"
		}
		out.appendLine("${row.probe.id} [${row.probe.classification.label}] $verdict")
		if (row.deviceLines == null) return
		val matches = row.matches.joinToString(", ") { it.label }.ifEmpty { "none" }
		out.appendLine("  matches: $matches")
		out.appendLine("  differs: ${row.differs.joinToString(", ") { it.label }}")
		if (!lines) return
		val groups = row.differs.groupBy { row.probe.cell(it)!!.hash }.values
		for (group in groups) {
			val theirs = row.probe.cell(group.first())!!.lines
			val label = group.joinToString(", ") { it.label }
			for (i in differingLines(listOf(row.deviceLines, theirs))) {
				val mine = row.deviceLines.getOrNull(i) ?: "(no line)"
				out.appendLine("  line ${i + 1} (this device): ${ascii(mine)}")
				val other = ascii(theirs.getOrNull(i) ?: "(no line)")
				out.appendLine("  line ${i + 1} ($label): $other")
			}
		}
	}
}
