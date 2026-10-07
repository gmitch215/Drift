package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.scan.atlas.ColumnKey
import dev.gmitch215.drift.scan.atlas.Dataset
import dev.gmitch215.drift.scan.atlas.ProbeComparison
import dev.gmitch215.drift.scan.atlas.ProbeEntry
import dev.gmitch215.drift.scan.atlas.label
import dev.gmitch215.drift.scan.probe.kotlin.Classification

data class AtlasState(
	val dataset: Dataset,
	val version: String = dataset.versions.first(),
	val divergentOnly: Boolean = false,
	val level: DetailLevel = DetailLevel.SUMMARY,
	val device: Device? = null,
	val selected: String? = null,
)

data class CellView(val target: String, val short: String, val lines: List<String>)

/** Targets of one family, in the order the matrix shows them. */
data class TargetGroup(val name: String, val targets: List<String>)

/** The family a column belongs to, read from its label (`jvm-17` is JVM, `wasm-webkit` Wasm). */
fun family(target: String): String = when {
	target == "jvm" || target.startsWith("jvm-") -> "JVM"
	target == "wasm" || target.startsWith("wasm-") -> "Wasm"
	target == "android" || target == "ios" -> "Mobile"
	target == "macos" || target.startsWith("linux") || target.startsWith("mingw") -> "Native"
	else -> "Other"
}

private val FAMILY_ORDER = listOf("JVM", "Native", "Wasm", "Mobile", "Other")

fun groupTargets(targets: List<String>): List<TargetGroup> = targets.groupBy { family(it) }
	.map { (name, own) -> TargetGroup(name, own.sorted()) }
	.sortedBy { FAMILY_ORDER.indexOf(it.name) }

data class RowView(
	val id: String,
	val area: String,
	val name: String,
	val divergent: Boolean,
	val badge: String,
	val cells: List<CellView>,
	val device: CellView?,
	val deviceVerdict: String?,
	val notes: List<String>,
)

data class AtlasView(
	val version: String,
	val versions: List<String>,
	val groups: List<TargetGroup>,
	val deviceLabel: String?,
	val total: Int,
	val divergent: Int,
	val rows: List<RowView>,
	val deviceSummary: List<String>,
) {
	val targets: List<String> get() = groups.flatMap { it.targets }

	val hidden: Int get() = total - rows.size

	fun toJson(): JsonObject = obj(
		"version" to JsonString(version),
		"versions" to strings(versions),
		"groups" to JsonArray(
			groups.map { obj("name" to JsonString(it.name), "targets" to strings(it.targets)) },
		),
		"deviceLabel" to JsonString(deviceLabel.orEmpty()),
		"total" to JsonInt(total.toLong()),
		"divergent" to JsonInt(divergent.toLong()),
		"deviceSummary" to strings(deviceSummary),
		"rows" to JsonArray(
			rows.map { r ->
				obj(
					"id" to JsonString(r.id),
					"divergent" to JsonBool(r.divergent),
					"badge" to JsonString(r.badge),
					"cells" to JsonArray(
						r.cells.map {
							obj("target" to JsonString(it.target), "short" to JsonString(it.short))
						},
					),
					"device" to JsonString(r.device?.short.orEmpty()),
					"verdict" to JsonString(r.deviceVerdict.orEmpty()),
					"notes" to strings(r.notes),
				)
			},
		),
	)

	fun encode(): String = CanonicalJson.encode(toJson())

	companion object {
		private const val SNIPPET = 20
		private const val NO_LINE = "(no line)"
		private const val HEX = "0123456789abcdef"

		fun of(state: AtlasState): AtlasView {
			val dataset = state.dataset
			val v = state.version
			val groups = groupTargets(dataset.columns.filter { it.version == v }.map { it.target })
			val targets = groups.flatMap { it.targets }
			val device = state.device
			val byId = device?.comparison?.rows?.associateBy { it.probe.id }.orEmpty()
			val all = dataset.probes.map { row(state, it, targets, byId[it.id]) }
			val rows = if (state.divergentOnly) all.filter { it.divergent } else all
			return AtlasView(
				version = v,
				versions = dataset.versions,
				groups = groups,
				deviceLabel = device?.label,
				total = all.size,
				divergent = all.count { it.divergent },
				rows = rows,
				deviceSummary = device?.let { summary(it, v, targets, byId.values) }.orEmpty(),
			)
		}

		fun drill(state: AtlasState, id: String): List<String> {
			val p = state.dataset.probe(id) ?: return emptyList()
			val v = state.version
			val device = state.device
			val cmp = device?.comparison?.rows?.firstOrNull { it.probe.id == id }
			return buildList {
				add(id)
				add(badge(p))
				add(p.question)
				add("source:")
				p.source.lines().forEach { add("  $it") }
				for (key in state.dataset.columns.filter { it.version == v }) {
					add("${key.target} on kotlin $v:")
					p.cell(key)?.lines?.forEach { add("  ${ascii(it)}") }
				}
				val own = cmp?.deviceLines
				if (device != null && own != null) {
					add("${device.label}:")
					own.forEach { add("  ${ascii(it)}") }
				}
				addAll(cited(p))
			}
		}

		internal fun ascii(text: String): String = buildString {
			for (c in text) {
				if (c.code in 0x20..0x7e) {
					append(c)
				} else {
					append("\\u")
					for (shift in intArrayOf(12, 8, 4, 0)) append(HEX[(c.code shr shift) and 15])
				}
			}
		}

		internal fun badge(p: ProbeEntry): String = when (p.classification) {
			Classification.UNCLASSIFIED -> "unclassified: no explanation found"
			else -> p.classification.label
		}

		internal fun cited(p: ProbeEntry): List<String> = buildList {
			add("basis: ${p.info.basis}")
			p.references.forEach { add("reference: ${it.url} \"${it.quote}\"") }
			add("notes: ${p.note}")
			p.info.searched?.let { add("searched: $it") }
			if (p.issues.isNotEmpty()) {
				add("linked issues: ${p.issues.joinToString(", ") { "${it.id} (${it.state})" }}")
			}
		}

		private fun row(
			state: AtlasState,
			p: ProbeEntry,
			targets: List<String>,
			cmp: ProbeComparison?,
		): RowView {
			val v = state.version
			val own = targets.mapNotNull { t -> p.cell(ColumnKey(t, v))?.let { t to it.lines } }
			val deviceLines = cmp?.deviceLines
			val at = firstDifference(own.map { it.second } + listOfNotNull(deviceLines))
			val area = p.id.removePrefix("kotlin.").substringBefore('.')
			val verdict = cmp?.let {
				val m = it.matches.filter { k -> k.version == v }.map { k -> k.target }
				when {
					deviceLines == null -> "not measured"
					m.isEmpty() -> "new data"
					m.size == targets.size -> "matches all"
					else -> "matches ${m.joinToString(", ")}"
				}
			}
			return RowView(
				id = p.id,
				area = area,
				name = p.id.removePrefix("kotlin.$area."),
				divergent = v in p.divergentVersions,
				badge = badge(p),
				cells = own.map { (t, lines) -> CellView(t, short(lines, at), lines) },
				device = deviceLines?.let { CellView("device", short(it, at), it) },
				deviceVerdict = verdict,
				notes = when (state.level) {
					DetailLevel.SUMMARY -> emptyList()
					DetailLevel.DETAIL -> listOf(p.question)
					DetailLevel.FULL -> listOf(p.question) + cited(p)
				},
			)
		}

		private fun firstDifference(variants: List<List<String>>): Int? {
			val size = variants.maxOfOrNull { it.size } ?: return null
			return (0 until size).firstOrNull { i ->
				variants.map { it.getOrNull(i) }.distinct().size > 1
			}
		}

		private fun short(lines: List<String>, at: Int?): String {
			val i = at ?: 0
			val line = lines.getOrNull(i) ?: NO_LINE
			val text = ascii(line.substringAfter(" = ", line))
			val cut = if (text.length > SNIPPET) text.take(SNIPPET - 1) + "~" else text
			return if (at == null) cut else "L${i + 1} $cut"
		}

		private fun summary(
			device: Device,
			version: String,
			targets: List<String>,
			rows: Collection<ProbeComparison>,
		): List<String> {
			val measured = rows.filter { it.deviceLines != null }
			val counts = measured.map { r -> r.matches.count { it.version == version } }
			return buildList {
				add("${device.label}: kotlin ${device.version}, ${measured.size} probes measured")
				if (device.version != version) {
					add("this device runs kotlin ${device.version}; columns are kotlin $version")
				}
				add("matches every column: ${counts.count { it == targets.size }}")
				add("matches some columns: ${counts.count { it in 1 until targets.size }}")
				add("matches no column (new data, not an error): ${counts.count { it == 0 }}")
				for (t in targets) {
					val n = measured.count { r ->
						r.matches.any { it.version == version && it.target == t }
					}
					add("$t: $n of ${measured.size} probes match")
				}
			}
		}

		private fun strings(items: List<String>) = JsonArray(items.map { JsonString(it) })
	}
}
