package dev.gmitch215.drift.scan.atlas

import dev.gmitch215.drift.diff.Version
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.model.ProbeStatus
import dev.gmitch215.drift.scan.probe.kotlin.Classification
import dev.gmitch215.drift.scan.probe.kotlin.KotlinCatalog
import dev.gmitch215.drift.scan.probe.kotlin.KotlinProbe

data class ColumnKey(val target: String, val version: String) {
	val label: String get() = "$target@$version"
}

/** Numeric order for versions that parse (2.5.0-Beta1 before 2.5.0), text order otherwise. */
internal fun versionOrder(a: String, b: String): Int {
	val x = Version.parse(a)
	val y = Version.parse(b)
	val c = if (x != null && y != null) x.compare(y) else 0
	return if (c != 0) c else a.compareTo(b)
}

private val columnOrder = Comparator<ColumnKey> { a, b ->
	val c = a.target.compareTo(b.target)
	if (c != 0) c else versionOrder(a.version, b.version)
}

val Classification.label: String
	get() = when (this) {
		Classification.DOCUMENTED -> "documented"
		Classification.PLATFORM_DEFINED -> "platform-defined"
		Classification.UNCLASSIFIED -> "unclassified"
	}

class DatasetCell(val key: ColumnKey, val status: ProbeStatus, val lines: List<String>) {
	val transcript: String get() = lines.joinToString("\n") + "\n"
	val hash: String get() = Sha256.hex(transcript)
}

class ProbeEntry(
	val id: String,
	val question: String,
	val source: String,
	val info: ClassificationEntry,
	cells: List<DatasetCell>,
) {
	val classification: Classification get() = info.classification
	val references: List<Reference> get() = info.references
	val issues: List<IssueRef> get() = info.issues
	val note: String get() = info.note

	val cells: List<DatasetCell> = cells.sortedWith(compareBy(columnOrder) { it.key })
	val versions: List<String> = this.cells.map {
		it.key.version
	}.distinct().sortedWith(::versionOrder)

	/** Targets of one Kotlin version grouped by identical transcript, never across versions. */
	fun groups(version: String): List<List<String>> {
		val byHash = linkedMapOf<String, MutableList<String>>()
		for (c in cells) {
			if (c.key.version != version) continue
			byHash.getOrPut(c.hash) { mutableListOf() } += c.key.target
		}
		return byHash.values.toList()
	}

	val divergentVersions: List<String> get() = versions.filter { groups(it).size > 1 }

	val divergent: Boolean get() = divergentVersions.isNotEmpty()

	/** Targets whose transcript is not the same on every version measured. */
	val changedAcrossVersions: List<String>
		get() = cells.groupBy { it.key.target }
			.filter { (_, own) -> own.map { it.hash }.distinct().size > 1 }.keys.sorted()

	fun cell(key: ColumnKey): DatasetCell? = cells.firstOrNull { it.key == key }

	fun toJson(): JsonObject = obj(
		"id" to JsonString(id),
		"question" to JsonString(question),
		"source" to JsonString(source),
		"classification" to Classifications.toJson(info),
		"divergence" to obj(
			"divergent" to JsonBool(divergent),
			"changedAcrossVersions" to strings(changedAcrossVersions),
			"versions" to JsonArray(
				versions.map { v ->
					obj(
						"version" to JsonString(v),
						"divergent" to JsonBool(groups(v).size > 1),
						"groups" to JsonArray(groups(v).map { strings(it) }),
					)
				},
			),
		),
		"cells" to JsonArray(
			cells.map {
				obj(
					"target" to JsonString(it.key.target),
					"version" to JsonString(it.key.version),
					"status" to JsonString(it.status.name.lowercase()),
					"hash" to JsonString(it.hash),
					"lines" to strings(it.lines),
				)
			},
		),
	)
}

class ClassCount(val probes: Int, val divergent: Int)

/**
 * The Kotlin probe results of every recorded column. Rows hold one cell per target and Kotlin
 * version; divergence is judged inside one version and versions never merge.
 */
class Dataset(val probes: List<ProbeEntry>) {
	val columns: List<ColumnKey> =
		probes.flatMap { p -> p.cells.map { it.key } }.distinct().sortedWith(columnOrder)
	val targets: List<String> = columns.map { it.target }.distinct().sorted()
	val versions: List<String> = columns.map { it.version }.distinct().sortedWith(::versionOrder)
	val divergentCount: Int = probes.count { it.divergent }
	val byClassification: Map<Classification, ClassCount> =
		Classification.entries.associateWith { c ->
			val own = probes.filter { it.classification == c }
			ClassCount(own.size, own.count { it.divergent })
		}

	fun probe(id: String): ProbeEntry? = probes.firstOrNull { it.id == id }

	fun divergentIn(version: String): Int = probes.count { version in it.divergentVersions }

	fun toJson(): JsonObject = obj(
		"schema" to JsonInt(SCHEMA),
		"axes" to obj(
			"probes" to strings(probes.map { it.id }),
			"targets" to strings(targets),
			"versions" to strings(versions),
			"columns" to JsonArray(
				columns.map { k ->
					obj(
						"target" to JsonString(k.target),
						"version" to JsonString(k.version),
						"probes" to JsonInt(probes.count { p -> p.cell(k) != null }.toLong()),
					)
				},
			),
		),
		"summary" to obj(
			"probes" to JsonInt(probes.size.toLong()),
			"columns" to JsonInt(columns.size.toLong()),
			"divergent" to JsonInt(divergentCount.toLong()),
			"identical" to JsonInt((probes.size - divergentCount).toLong()),
			"byClassification" to JsonObject(
				byClassification.entries.associate { (c, n) ->
					c.label to obj(
						"probes" to JsonInt(n.probes.toLong()),
						"divergent" to JsonInt(n.divergent.toLong()),
					)
				},
			),
			"byVersion" to JsonObject(
				versions.associateWith { v ->
					obj(
						"columns" to JsonInt(columns.count { it.version == v }.toLong()),
						"divergent" to JsonInt(divergentIn(v).toLong()),
					)
				},
			),
		),
		"probes" to JsonArray(probes.map { it.toJson() }),
	)

	/** Canonical JSON: the same bytes on every target. */
	fun encode(): String = CanonicalJson.encode(toJson())

	companion object {
		const val SCHEMA = 2L

		/**
		 * Needs every column to cover exactly [catalog], so a short transcript cannot pass, and
		 * an entry in [classifications] for every probe.
		 */
		fun build(
			columns: List<Column>,
			catalog: List<KotlinProbe> = KotlinCatalog.all,
			classifications: ClassificationData = Classifications.embedded,
		): Dataset {
			if (columns.isEmpty()) throw AtlasException(NoTranscripts)
			val ids = catalog.map { it.id }.toSet()
			val seen = mutableMapOf<ColumnKey, Column>()
			for (c in columns) {
				val key = ColumnKey(c.target, c.version)
				seen[key]?.let {
					throw AtlasException(DuplicateColumn(key.label, it.source, c.source))
				}
				seen[key] = c
				val unknown = (c.cells.keys - ids).sorted()
				if (unknown.isNotEmpty()) throw AtlasException(UnknownProbe(c.source, unknown))
				val missing = (ids - c.cells.keys).sorted()
				if (missing.isNotEmpty()) throw AtlasException(MissingProbes(c.source, missing))
			}
			val unclassified = (ids - classifications.entries.map { it.id }.toSet()).sorted()
			if (unclassified.isNotEmpty()) {
				throw AtlasException(BadClassification("no entry for ${unclassified.first()}"))
			}
			return Dataset(
				catalog.sortedBy { it.id }.map { p ->
					ProbeEntry(
						id = p.id,
						question = p.question,
						source = p.source,
						info = classifications[p.id]!!,
						cells = columns.map { c ->
							val cell = c.cells.getValue(p.id)
							DatasetCell(
								ColumnKey(c.target, c.version),
								cell.status,
								cell.transcript.removeSuffix("\n").split('\n'),
							)
						},
					)
				},
			)
		}

		fun parse(text: String): Dataset = try {
			fromJson(CanonicalJson.parse(text))
		} catch (e: JsonException) {
			throw AtlasException(BadDataset(e.message ?: "not JSON"))
		}

		/** Reads probes and cells only; the axes and summary are derived again. */
		fun fromJson(json: JsonValue): Dataset = try {
			val root = json.obj()
			val schema = root.require("schema").long()
			if (schema != SCHEMA) {
				throw AtlasException(BadDataset("schema $schema, expected $SCHEMA"))
			}
			val probes = root.require("probes").array().map { probe(it) }
			val dataset = Dataset(probes)
			for (p in probes) {
				if (p.cells.map { it.key } != dataset.columns) {
					throw AtlasException(BadDataset("probe ${p.id} lacks a column"))
				}
			}
			dataset
		} catch (e: JsonException) {
			throw AtlasException(BadDataset(e.message ?: "unreadable"))
		}

		private fun probe(json: JsonValue): ProbeEntry {
			val o = json.obj()
			val id = o.require("id").string()
			return ProbeEntry(
				id = id,
				question = o.require("question").string(),
				source = o.require("source").string(),
				info = try {
					Classifications.fromJson(o.require("classification").obj())
				} catch (e: AtlasException) {
					throw AtlasException(BadDataset(e.message ?: "unreadable classification"))
				},
				cells = o.require("cells").array().map { cell(id, it) },
			)
		}

		private fun cell(id: String, json: JsonValue): DatasetCell {
			val o = json.obj()
			val status = o.require("status").string()
			val cell = DatasetCell(
				ColumnKey(o.require("target").string(), o.require("version").string()),
				ProbeStatus.entries.firstOrNull { it.name.lowercase() == status }
					?: throw AtlasException(BadDataset("unknown status $status")),
				o.require("lines").array().map { it.string() },
			)
			if (cell.hash != o.require("hash").string()) {
				throw AtlasException(BadDataset("$id ${cell.key.label} does not match its hash"))
			}
			return cell
		}
	}
}

private fun strings(items: List<String>): JsonArray = JsonArray(items.map { JsonString(it) })
