package dev.gmitch215.drift.scan.atlas

object Show {
	/** Header of every repro draft; the tool only prints text and never contacts anyone. */
	const val NOTICE = "GENERATED DRAFT, NOT REVIEWED"

	fun matrix(dataset: Dataset): String = buildString {
		val labels = dataset.columns.map { it.label }
		appendLine(
			"atlas: ${dataset.probes.size} probes, ${dataset.columns.size} columns " +
				"(${labels.joinToString(", ")})",
		)
		appendLine()
		val idWidth = maxOf("probe".length, dataset.probes.maxOf { it.id.length })
		appendLine(
			pad("probe", idWidth) + "  " + pad("classification", CLASS_WIDTH) + "  " +
				labels.joinToString("  "),
		)
		var unavailable = false
		for (p in dataset.probes) {
			val seen = mutableListOf<String>()
			val cells = dataset.columns.mapIndexed { i, key ->
				val cell = p.cell(key)
				if (cell == null) {
					pad("-", labels[i].length)
				} else {
					if (cell.hash !in seen) seen += cell.hash
					val mark = if (cell.status.name == "OK") "" else "!"
					if (mark.isNotEmpty()) unavailable = true
					pad(letter(seen.indexOf(cell.hash)) + mark, labels[i].length)
				}
			}
			appendLine(
				(
					pad(p.id, idWidth) + "  " + pad(p.classification.label, CLASS_WIDTH) + "  " +
					cells.joinToString("  ")
				).trimEnd(),
			)
		}
		appendLine()
		appendLine(
			"a letter repeated on a row marks a byte-identical transcript" +
				if (unavailable) "; ! marks a probe that was unavailable" else "",
		)
		appendLine("divergent probes: ${dataset.divergentCount} of ${dataset.probes.size}")
		for ((c, n) in dataset.byClassification) {
			appendLine("  ${c.label}: ${n.divergent} of ${n.probes}")
		}
		if (dataset.versions.size > 1) {
			appendLine("divergent probes per kotlin version:")
			for (v in dataset.versions) appendLine("  $v: ${dataset.divergentIn(v)}")
		}
	}

	/** A draft a person can review; it states what was measured and nothing more. */
	fun repro(dataset: Dataset, id: String): String {
		val p = dataset.probe(id) ?: throw AtlasException(NoSuchProbe(id))
		return buildString {
			appendLine(NOTICE)
			wrap(
				"drift atlas show wrote this from recorded transcripts. A person must read it, " +
					"rerun the source by hand and rewrite it in their own words before anything " +
					"is filed. Drift does not file, post or contact anyone.",
			).forEach { appendLine(it) }
			appendLine()
			appendLine("probe: ${p.id}")
			appendLine("question: ${p.question}")
			appendLine("classification: ${p.classification.label}")
			appendLine("basis: ${p.info.basis}")
			if (p.references.isEmpty()) appendLine("references: none")
			for (r in p.references) appendLine("reference: ${r.url} \"${r.quote}\"")
			val issues = if (p.issues.isEmpty()) {
				"none"
			} else {
				p.issues.joinToString(", ") { "${it.id} (${it.state} when read)" }
			}
			appendLine("linked issues: $issues")
			p.info.searched?.let { s ->
				wrap("searched: $s", indent = "  ").forEach { appendLine(it) }
			}
			p.info.repro?.let { appendLine("repro draft: $it") }
			for (v in p.versions) {
				val targets = p.cells.filter { it.key.version == v }.map { it.key.target }
				appendLine("measured: kotlin $v on ${targets.joinToString(", ")}")
			}
			appendLine()
			appendLine("source:")
			for (line in p.source.lines()) {
				appendLine(("    " + ascii(line.replace("\t", "    "))).trimEnd())
			}
			appendLine()
			if (!p.divergent) {
				appendLine("no version measured has differing results; nothing to report")
			}
			for (v in p.versions) results(this, p, v)
			if (p.changedAcrossVersions.isNotEmpty()) {
				appendLine(
					"changes between versions on: ${p.changedAcrossVersions.joinToString(", ")}",
				)
				appendLine()
			}
			appendLine("notes from the research, also unreviewed:")
			wrap(p.note, indent = "  ").forEach { appendLine(it) }
			appendLine()
			appendLine("before filing:")
			appendLine("  search the tracker for this behavior and for the linked issues")
			appendLine("  check the reference page for the contract and cite the sentence")
			appendLine("  rerun the source on the newest release and on each target by hand")
			appendLine("  write the report yourself, with the versions and the controls above")
		}
	}

	private fun results(out: StringBuilder, p: ProbeEntry, version: String) {
		val groups = p.groups(version)
		if (groups.size == 1) {
			out.appendLine("kotlin $version: identical on ${groups[0].joinToString(", ")}")
			out.appendLine()
			return
		}
		out.appendLine("kotlin $version: ${groups.size} different results")
		val lines = groups.map { g -> p.cell(ColumnKey(g.first(), version))!!.lines }
		val differing = differingLines(lines)
		val total = lines.maxOf { it.size }
		out.appendLine("differing lines: ${differing.size} of $total")
		for (i in differing) {
			out.appendLine("  line ${i + 1}")
			groups.forEachIndexed { n, g ->
				val text = ascii(lines[n].getOrNull(i) ?: "(no line)")
				out.appendLine("    ${g.joinToString(", ")}: $text")
			}
		}
		out.appendLine("controls: ${total - differing.size} lines are the same on every target")
		out.appendLine()
	}

	private fun letter(index: Int): String = if (index < 26) ('A' + index).toString() else "#$index"

	private const val CLASS_WIDTH = 16
}
