package dev.gmitch215.drift.bench

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.schema.FailsafeSchema

/** Every scalar stays a string; `Template.listFromJson` does the typed reading. */
object Yaml {
	private val load = Load(
		LoadSettings.builder().setSchema(FailsafeSchema()).setAllowDuplicateKeys(false).build(),
	)

	fun parse(text: String, name: String): JsonValue = try {
		toJson(load.loadFromString(text))
	} catch (e: RuntimeException) {
		throw TemplateException(
			Problem.BAD_VALUE,
			name,
			"not valid YAML: ${e.message?.lineSequence()?.first()}",
		)
	}

	private fun toJson(v: Any?): JsonValue = when (v) {
		null -> JsonNull
		is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
		is List<*> -> JsonArray(v.map { toJson(it) })
		else -> JsonString(v.toString())
	}
}
