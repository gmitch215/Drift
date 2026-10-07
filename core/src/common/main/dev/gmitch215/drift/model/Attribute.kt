package dev.gmitch215.drift.model

import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string

data class Attribute(
	val path: String,
	val value: String,
	val source: String,
	val stability: Stability = Stability.STATIC,
) {
	fun toJson(): JsonObject = obj(
		"path" to JsonString(path),
		"value" to JsonString(value),
		"source" to JsonString(source),
		"stability" to JsonString(stability.name.lowercase()),
	)

	companion object {
		fun fromJson(json: JsonValue): Attribute {
			val o = json.obj()
			val stability = o.require("stability").string()
			return Attribute(
				path = o.require("path").string(),
				value = o.require("value").string(),
				source = o.require("source").string(),
				stability = Stability.entries.firstOrNull { it.name.lowercase() == stability }
					?: throw JsonException("unknown stability $stability"),
			)
		}
	}
}
