package dev.gmitch215.drift.build

/** Static HTML for the Atlas site: no scripts, no external resources, same input same bytes. */
object SiteRender {
	const val DRAFT_LABEL = "Draft for human review; not filed."

	private const val HEX = "0123456789abcdef"

	private const val TRACKER = "https://youtrack.jetbrains.com/issue/"

	private val CAUSES = mapOf(
		"a" to "documented difference",
		"b" to "platform-defined",
		"c" to "Kotlin issue",
		"d" to "hardware",
		"e" to "JDK version",
		"f" to "build tool",
		"g" to "unexplained",
	)

	private val STYLE = """
		|:root{color-scheme:light dark;--fg:#1a1a1a;--bg:#fff;--muted:#555;--line:#c8c8c8;
		|--mark:#ffe58a;--code:#f3f3f3;--link:#0b57d0}
		|@media (prefers-color-scheme:dark){:root{--fg:#ececec;--bg:#161616;--muted:#a8a8a8;
		|--line:#444;--mark:#6b5300;--code:#222;--link:#8ab4f8}}
		|body{margin:0;font:16px/1.5 system-ui,sans-serif;color:var(--fg);background:var(--bg)}
		|header,main,footer{max-width:72rem;margin:0 auto;padding:.75rem 1rem}
		|a{color:var(--link)}
		|.skip{position:absolute;left:-999px}.skip:focus{left:1rem;top:.5rem}
		|nav a{margin-right:1rem}
		|pre,code{font:14px/1.4 ui-monospace,monospace;background:var(--code)}
		|pre{padding:.5rem;overflow-x:auto;white-space:pre}
		|table{border-collapse:collapse;display:block;overflow-x:auto;max-width:100%}
		|th,td{border:1px solid var(--line);padding:.25rem .5rem;text-align:left;vertical-align:top}
		|tr.differs{background:var(--mark)}
		|mark{background:var(--mark);color:inherit}
		|blockquote{margin:.5rem 0;padding-left:1rem;border-left:3px solid var(--line)}
		|.draft{border:2px solid var(--fg);padding:.5rem}
		|.muted{color:var(--muted)}
	""".trimMargin()

	/** Printable ASCII only: any other UTF-16 unit becomes \uXXXX, as `drift atlas show` prints. */
	fun ascii(text: String): String = buildString {
		for (c in text) {
			if (c.code in 0x20..0x7e) {
				append(c)
			} else {
				append("\\u")
				for (shift in intArrayOf(12, 8, 4, 0)) append(HEX[(c.code shr shift) and 15])
			}
		}
	}

	/** Keeps readable text and escapes controls, format characters and unusual spaces. */
	fun visible(text: String): String = buildString {
		var i = 0
		while (i < text.length) {
			val cp = text.codePointAt(i)
			val type = Character.getType(cp)
			val hidden = cp != '\n'.code && cp != '\t'.code && (
				Character.isISOControl(cp) || type == Character.FORMAT.toInt() ||
					type == Character.LINE_SEPARATOR.toInt() ||
					type == Character.PARAGRAPH_SEPARATOR.toInt() ||
					(type == Character.SPACE_SEPARATOR.toInt() && cp != ' '.code)
				)
			if (hidden) {
				append("\\u").append(cp.toString(16).padStart(4, '0'))
			} else {
				appendCodePoint(cp)
			}
			i += Character.charCount(cp)
		}
	}

	fun escape(text: String): String = buildString {
		for (c in text) {
			when (c) {
				'&' -> append("&amp;")
				'<' -> append("&lt;")
				'>' -> append("&gt;")
				'"' -> append("&quot;")
				else -> append(c)
			}
		}
	}

	private fun page(title: String, root: String, body: String): String = """
		|<!doctype html>
		|<html lang="en">
		|<head>
		|<meta charset="utf-8">
		|<meta name="viewport" content="width=device-width, initial-scale=1">
		|<title>${escape(title)}</title>
		|<style>
		|$STYLE
		|</style>
		|</head>
		|<body>
		|<a class="skip" href="#main">Skip to content</a>
		|<header>
		|<nav aria-label="Atlas">
		|<a href="$root">Interactive Atlas</a>
		|<a href="${root}probe/">All probes</a>
		|<a href="${root}data/dataset.json">dataset.json</a>
		|<a href="${root}data/classification.json">classification.json</a>
		|</nav>
		|</header>
		|<main id="main">
		|$body
		|</main>
		|<footer class="muted">
		|<p>Results are recorded measurements for the listed Kotlin versions. A difference between
		|targets is a result, not a diagnosis.</p>
		|</footer>
		|</body>
		|</html>
		|
	""".trimMargin()

	fun notFound(): String = page(
		"Not found | Drift Atlas",
		"./",
		"<h1>Page not found</h1>\n<p>Start from the interactive Atlas or the list of probes.</p>",
	)

	private fun id(probe: SiteProbe) = escape(probe.id)

	fun index(
		dataset: SiteDataset,
		columns: List<SiteColumn>,
		measured: String,
		transcripts: Map<Pair<String, String>, String>,
	): String {
		val body = StringBuilder()
		body.append("<h1>Drift Atlas probes</h1>\n")
		body.append(
			"<p>${dataset.probes.size} Kotlin probes, ${dataset.columns.size} measured columns, " +
				"${dataset.probes.count { it.divergent }} probes that differ between targets " +
				"on at least one Kotlin version (${dataset.versions.joinToString(", ") { escape(it) }}). " +
				"Classifications and issue states were read on ${escape(measured)}.</p>\n",
		)
		body.append("<table>\n<caption>Classification counts</caption>\n")
		body.append("<tr><th scope=\"col\">Classification</th><th scope=\"col\">Probes</th>")
		body.append("<th scope=\"col\">Differing</th></tr>\n")
		for (c in listOf("documented", "platform-defined", "unclassified")) {
			body.append(
				"<tr><th scope=\"row\">$c</th><td>${dataset.count(c)}</td>" +
					"<td>${dataset.count(c, true)}</td></tr>\n",
			)
		}
		body.append("</table>\n<h2>Probes</h2>\n<table>\n<caption>Every probe</caption>\n")
		body.append("<tr><th scope=\"col\">Probe</th><th scope=\"col\">Classification</th>")
		body.append("<th scope=\"col\">Differs on Kotlin</th><th scope=\"col\">Issues</th></tr>\n")
		for (p in dataset.probes) {
			val differs = p.versions.filter { it.divergent }.joinToString(", ") { escape(it.version) }
			body.append(
				"<tr><th scope=\"row\"><a href=\"${id(p)}.html\">${id(p)}</a></th>" +
					"<td>${escape(p.classification)}</td>" +
					"<td>${differs.ifEmpty { "identical" }}</td>" +
					"<td>${p.issues.joinToString(", ") { escape(it.id) }}</td></tr>\n",
			)
		}
		body.append("</table>\n<h2>Columns</h2>\n<table>\n<caption>How each column was measured")
		body.append("</caption>\n<tr><th scope=\"col\">Column</th><th scope=\"col\">Kotlin</th>")
		body.append("<th scope=\"col\">Transcript</th><th scope=\"col\">How</th></tr>\n")
		for ((target, version) in dataset.columns) {
			val path = transcripts[target to version]
			val link = if (path == null) "" else "<a href=\"../data/transcripts/$path\">transcript</a>"
			body.append(
				"<tr><th scope=\"row\">${escape(target)}</th><td>${escape(version)}</td><td>$link</td>" +
					"<td>${escape(columns.firstOrNull { it.target == target }?.how.orEmpty())}</td></tr>\n",
			)
		}
		body.append("</table>")
		return page("Probes | Drift Atlas", "../", body.toString())
	}

	fun probe(
		p: SiteProbe,
		columns: List<SiteColumn>,
		measured: String,
		transcripts: Map<Pair<String, String>, String>,
		reproText: String?,
	): String {
		val b = StringBuilder()
		b.append("<h1>${id(p)}</h1>\n<p>${escape(p.question)}</p>\n")
		b.append("<p><strong>${escape(p.classification)}</strong> (basis: ${escape(p.basis)})")
		if (p.divergent) {
			b.append(
				"; differs between targets",
			)
		} else {
			b.append("; identical on every column")
		}
		b.append("</p>\n<h2>Source</h2>\n<pre><code>")
		b.append(escape(visible(p.source.replace("\t", "    "))))
		b.append("</code></pre>\n")
		for (v in p.versions) results(b, p, v, transcripts)
		classification(b, p, measured)
		if (p.issues.isNotEmpty()) issues(b, p, measured)
		if (p.searched != null) {
			b.append("<h2>What was searched</h2>\n<p>${escape(p.searched)}</p>\n")
		}
		if (reproText != null) {
			b.append("<h2>Repro draft</h2>\n<div class=\"draft\">\n")
			b.append("<p><strong>${escape(DRAFT_LABEL)}</strong></p>\n<pre>")
			b.append(escape(visible(reproText)))
			b.append("</pre>\n</div>\n")
		}
		b.append("<h2>Kotlin versions measured</h2>\n<p>")
		b.append(p.versions.joinToString(", ") { escape(it.version) })
		b.append("</p>\n<h2>How it was measured</h2>\n<ul>\n")
		for (target in p.cells.map { it.target }.distinct()) {
			val how = columns.firstOrNull { it.target == target }?.how.orEmpty()
			b.append("<li><strong>${escape(target)}</strong>: ${escape(how)}</li>\n")
		}
		b.append("</ul>")
		return page("${p.id} | Drift Atlas", "../", b.toString())
	}

	private fun results(
		b: StringBuilder,
		p: SiteProbe,
		v: SiteVersion,
		transcripts: Map<Pair<String, String>, String>,
	) {
		val version = escape(v.version)
		b.append("<h2>Results on Kotlin $version</h2>\n")
		val outputs = v.groups.map { g ->
			p.cells.first { it.target == g.first() && it.version == v.version }.lines
		}
		val differing = differing(outputs)
		val reference = v.groups.indices.maxByOrNull { v.groups[it].size } ?: 0
		val verdict = if (v.divergent) {
			"${v.groups.size} different results; ${differing.size} of ${outputs[0].size} lines differ."
		} else {
			"Identical on every column."
		}
		b.append("<p>$verdict</p>\n<table>\n<caption>Result per column, Kotlin $version</caption>\n")
		b.append("<tr><th scope=\"col\">Column</th><th scope=\"col\">Group</th>")
		b.append("<th scope=\"col\">Status</th><th scope=\"col\">Transcript</th></tr>\n")
		v.groups.forEachIndexed { n, g ->
			for (t in g) {
				val cell = p.cells.first { it.target == t && it.version == v.version }
				val apart = v.divergent && n != reference
				val path = transcripts[t to v.version]
				val link = if (path == null) "" else "<a href=\"../data/transcripts/$path\">file</a>"
				b.append(
					"<tr${if (apart) " class=\"differs\"" else ""}><th scope=\"row\">${escape(t)}" +
						"</th><td>${group(n)}${if (apart) " (differs from group ${group(reference)})" else ""}" +
						"</td><td>${escape(cell.status)}</td><td>$link</td></tr>\n",
				)
			}
		}
		b.append("</table>\n")
		v.groups.forEachIndexed { n, g ->
			b.append("<h3>Group ${group(n)}: ${g.joinToString(", ") { escape(it) }}</h3>\n<pre>")
			b.append(
				outputs[n].withIndex().joinToString("\n") { (i, line) ->
					val text = escape(ascii(line))
					if (i in differing) "<mark>$text</mark>" else text
				},
			)
			b.append("</pre>\n")
		}
	}

	private fun classification(b: StringBuilder, p: SiteProbe, measured: String) {
		b.append("<h2>Classification</h2>\n<p>${escape(p.classification)}, basis ${escape(p.basis)}")
		if (p.causes.isNotEmpty()) {
			b.append("; causes: ")
			b.append(p.causes.joinToString(", ") { escape(CAUSES[it] ?: it) })
		}
		b.append(".</p>\n")
		if (p.downgradedFrom != null) {
			b.append("<p>Downgraded from ${escape(p.downgradedFrom)}; the reason is in the notes.</p>\n")
		}
		if (p.classification == "unclassified") {
			b.append("<p>No explanation was found in the sources searched.</p>\n")
		}
		for (r in p.references) {
			b.append("<blockquote><p>${escape(visible(r.quote))}</p>\n")
			b.append("<footer><a href=\"${escape(r.url)}\">${escape(r.url)}</a></footer></blockquote>\n")
		}
		b.append("<h3>Notes</h3>\n<p>${escape(visible(p.note))}</p>\n")
		b.append("<p class=\"muted\">Read on ${escape(measured)}.</p>\n")
	}

	private fun issues(b: StringBuilder, p: SiteProbe, measured: String) {
		b.append("<h2>Issues</h2>\n<table>\n<caption>Tracker state on ${escape(measured)}</caption>\n")
		b.append("<tr><th scope=\"col\">Issue</th><th scope=\"col\">State when read</th>")
		b.append("<th scope=\"col\">Resolved</th><th scope=\"col\">Summary</th></tr>\n")
		for (i in p.issues) {
			b.append(
				"<tr><th scope=\"row\"><a href=\"$TRACKER${escape(i.id)}\">${escape(i.id)}</a></th>" +
					"<td>${escape(i.state)}</td><td>${escape(i.resolved.orEmpty())}</td>" +
					"<td>${escape(visible(i.summary))}</td></tr>\n",
			)
		}
		b.append("</table>\n")
	}

	private fun group(n: Int): String = if (n < 26) ('A' + n).toString() else "#$n"

	private fun differing(variants: List<List<String>>): Set<Int> {
		val size = variants.maxOfOrNull { it.size } ?: 0
		return (0 until size).filter { i -> variants.map { it.getOrNull(i) }.distinct().size > 1 }
			.toSet()
	}
}
