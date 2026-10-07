package dev.gmitch215.drift.case

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString

/**
 * The hash chain over observation records. An entry's hash covers the previous hash and the
 * canonical content (the record without its `prev` and `hash` fields).
 */
object Chain {
	val GENESIS = "0".repeat(64)

	fun link(prev: String, content: JsonObject): String =
		Sha256.hex(prev + "\n" + CanonicalJson.encode(content))

	fun content(record: JsonObject): JsonObject = JsonObject(record.fields - "prev" - "hash")

	/** Adds `prev` and `hash` to each record in order; returns the records and the head hash. */
	fun seal(contents: List<JsonObject>): Pair<List<JsonObject>, String> {
		var prev = GENESIS
		val sealed = contents.map {
			val hash = link(prev, it)
			val record = JsonObject(
				it.fields + mapOf(
					"prev" to JsonString(prev),
					"hash" to JsonString(hash),
				),
			)
			prev = hash
			record
		}
		return sealed to prev
	}
}
