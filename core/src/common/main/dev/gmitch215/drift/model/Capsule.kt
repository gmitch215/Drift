package dev.gmitch215.drift.model

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
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

data class Capsule(
	val label: String,
	val attributes: List<Attribute>,
	val probes: List<ProbeResult> = emptyList(),
) {
	fun toJson(): JsonObject = obj(
		"schema" to JsonInt(SCHEMA),
		"label" to JsonString(label),
		"attributes" to JsonArray(attributes.sortedBy { it.path }.map { it.toJson() }),
		"probes" to JsonArray(probes.sortedBy { it.id }.map { it.toJson() }),
	)

	fun canonical(): String = CanonicalJson.encode(toJson())

	fun hash(): String = Sha256.hex(canonical())

	companion object {
		const val SCHEMA = 1L

		fun fromJson(json: JsonValue): Capsule {
			val o = json.obj()
			val schema = o.require("schema").long()
			if (schema != SCHEMA) throw JsonException("unsupported capsule schema $schema")
			return Capsule(
				label = o.require("label").string(),
				attributes = o.require("attributes").array().map(Attribute::fromJson),
				probes = o.require("probes").array().map(ProbeResult::fromJson),
			)
		}

		fun parse(text: String): Capsule = fromJson(CanonicalJson.parse(text))
	}
}
