package dev.gmitch215.drift.symptom

import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.jsonOrNull
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string

enum class SymptomKind { SIGNATURE, EXIT, SIGNAL, STEP, DURATION, TRANSPORT }

/**
 * One piece of evidence from a log. `value` is already normalized (see [Signature]), `line` is the
 * 1-based line of the first occurrence and `count` the number of occurrences with the same job,
 * kind and value. A `DURATION` value is whole milliseconds as text.
 */
data class Symptom(
	val kind: SymptomKind,
	val value: String,
	val line: Int,
	val job: String? = null,
	val count: Int = 1,
) {
	fun toJson(): JsonObject = obj(
		"kind" to JsonString(kind.name.lowercase()),
		"value" to JsonString(value),
		"line" to JsonInt(line.toLong()),
		"job" to jsonOrNull(job),
		"count" to JsonInt(count.toLong()),
	)

	companion object {
		fun fromJson(json: JsonValue): Symptom {
			val o = json.obj()
			val kind = o.require("kind").string()
			return Symptom(
				kind = SymptomKind.entries.firstOrNull { it.name.lowercase() == kind }
					?: throw JsonException("unknown symptom kind $kind"),
				value = o.require("value").string(),
				line = o.require("line").long().toInt(),
				job = o.require("job").let { if (it == JsonNull) null else it.string() },
				count = o.require("count").long().toInt(),
			)
		}
	}
}
