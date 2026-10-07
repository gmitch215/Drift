package dev.gmitch215.drift.case

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string

/**
 * A case directory in memory: relative path to canonical text, sorted by path. Every file is
 * generated output, so the same inputs give the same bytes. The archive writer and `verify`
 * of 6d4 start from [archiveEntries] and [check]; they add `certificate.json`, `confirmed/`,
 * `reproduce/` and `report.html` beside these files and extend the chain over `results/`.
 */
class CaseFile(files: Map<String, String>) {
	val files: Map<String, String> =
		files.entries.sortedBy { it.key }.associate { it.key to it.value }

	fun text(path: String): String? = files[path]

	/** Throws [JsonException] on a missing or malformed file; [CaseStore.read] has checked both. */
	fun json(path: String): JsonObject =
		CanonicalJson.parse(files[path] ?: throw JsonException("missing $path")).obj()

	/** The files in archive order, manifest last. */
	fun archiveEntries(): List<Pair<String, String>> =
		files.entries.filter { it.key != MANIFEST }.map { it.key to it.value } +
			listOfNotNull(files[MANIFEST]?.let { MANIFEST to it })

	/** Every integrity problem: edited files against the manifest, then the first broken link. */
	fun check(): List<CaseProblem> = try {
		hashes() + listOfNotNull(chain())
	} catch (e: JsonException) {
		listOf(MalformedFile(MANIFEST, e.message ?: "unreadable"))
	}

	private fun hashes(): List<CaseProblem> {
		val manifest = json(MANIFEST).require("files").array()
		return manifest.mapNotNull {
			val entry = it.obj()
			val path = entry.require("path").string()
			val expected = entry.require("sha256").string()
			val text = files[path]
			if (text == null) {
				MissingFile(path)
			} else {
				val actual = Sha256.hex(text)
				if (actual == expected) null else HashMismatch(path, expected, actual)
			}
		}
	}

	private fun chain(): CaseProblem? {
		val head = json(CASE).require("head").string()
		var prev = Chain.GENESIS
		var last = "observations"
		val paths = files.keys.filter { it.startsWith(OBSERVATIONS) }
		for ((i, path) in paths.withIndex()) {
			last = path
			val record = try {
				json(path)
			} catch (e: JsonException) {
				return MalformedFile(path, e.message ?: "unreadable")
			}
			val stored = (record["hash"] as? JsonString)?.value
			val before = (record["prev"] as? JsonString)?.value
			when {
				(record["seq"] as? JsonInt)?.value != i + 1L ->
					return ChainBroken(path, i, "sequence number is not ${i + 1}")

				before != prev -> return ChainBroken(path, i, "previous hash does not match")

				stored != Chain.link(prev, Chain.content(record)) ->
					return ChainBroken(path, i, "content does not match its hash")
			}
			prev = stored
		}
		if (prev == head) return null
		return ChainBroken(last, paths.size, "head differs from case.json")
	}

	companion object {
		const val SCHEMA = 1L
		const val CASE = "case.json"
		const val MANIFEST = "manifest.json"
		const val OBSERVATIONS = "observations/"

		fun schemaOf(path: String, text: JsonObject): CaseProblem? {
			val found = (text["schema"] as? JsonInt)?.value
			return if (found == SCHEMA) null else UnsupportedSchema(path, found, SCHEMA)
		}

		fun manifest(files: Map<String, String>): String = CanonicalJson.encode(
			obj(
				"schema" to JsonInt(SCHEMA),
				"files" to JsonArray(
					files.keys.filter { it != MANIFEST }.sorted().map {
						obj(
							"path" to JsonString(it),
							"sha256" to JsonString(Sha256.hex(files.getValue(it))),
						)
					},
				),
			),
		)
	}
}
