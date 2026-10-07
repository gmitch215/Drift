package dev.gmitch215.drift.bench

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue

/** Typed reads over a JsonObject; scalars may be strings (YAML failsafe) or JSON numbers. */
class Fields(val where: String, val json: JsonObject) {
	fun missing(key: String) = TemplateException(Problem.MISSING_FIELD, where, "missing $key")

	fun has(key: String): Boolean = json[key].let { it != null && it != JsonNull }

	private fun scalar(key: String): String? = when (val v = json[key]) {
		null, JsonNull -> null
		is JsonString -> v.value
		is JsonInt -> v.value.toString()
		is JsonBool -> v.value.toString()
		else -> throw TemplateException(Problem.BAD_TYPE, "$where.$key", "expected a scalar")
	}

	fun str(key: String): String = scalar(key) ?: throw missing(key)

	fun strOrNull(key: String): String? = scalar(key)

	fun int(key: String): Int {
		val text = str(key)
		return text.toIntOrNull()
			?: throw TemplateException(Problem.BAD_VALUE, "$where.$key", "not an integer: $text")
	}

	fun long(key: String): Long {
		val text = str(key)
		return text.toLongOrNull()
			?: throw TemplateException(Problem.BAD_VALUE, "$where.$key", "not an integer: $text")
	}

	fun bool(key: String): Boolean = when (val text = str(key)) {
		"true" -> true
		"false" -> false
		else -> throw TemplateException(Problem.BAD_VALUE, "$where.$key", "not a boolean: $text")
	}

	fun strings(key: String): Map<String, String> {
		val v = json[key]
		if (v == null || v == JsonNull) return emptyMap()
		val o = v as? JsonObject
			?: throw TemplateException(Problem.BAD_TYPE, "$where.$key", "expected a map")
		return o.fields.mapValues { (k, x) -> scalarOf("$where.$key.$k", x) }
	}

	fun all(): Map<String, String> = json.fields.mapValues { (k, x) -> scalarOf("$where.$k", x) }

	fun list(key: String): List<String> {
		val v = json[key]
		if (v == null || v == JsonNull) return emptyList()
		val a = v as? JsonArray
			?: throw TemplateException(Problem.BAD_TYPE, "$where.$key", "expected a list")
		return a.items.mapIndexed { i, x -> scalarOf("$where.$key[$i]", x) }
	}

	fun obj(key: String): Fields? = when (val v = json[key]) {
		null, JsonNull -> null
		is JsonObject -> Fields("$where.$key", v)
		else -> throw TemplateException(Problem.BAD_TYPE, "$where.$key", "expected a map")
	}

	fun objs(key: String): List<Fields> {
		val v = json[key]
		if (v == null || v == JsonNull) return emptyList()
		val a = v as? JsonArray
			?: throw TemplateException(Problem.BAD_TYPE, "$where.$key", "expected a list")
		return a.items.mapIndexed { i, x ->
			Fields(
				"$where.$key[$i]",
				x as? JsonObject
					?: throw TemplateException(
						Problem.BAD_TYPE,
						"$where.$key[$i]",
						"expected a map",
					),
			)
		}
	}

	private fun scalarOf(at: String, v: JsonValue): String = when (v) {
		is JsonString -> v.value
		is JsonInt -> v.value.toString()
		is JsonBool -> v.value.toString()
		else -> throw TemplateException(Problem.BAD_TYPE, at, "expected a scalar")
	}
}
