package dev.gmitch215.drift.json

sealed interface JsonValue

data object JsonNull : JsonValue

data class JsonBool(val value: Boolean) : JsonValue

data class JsonInt(val value: Long) : JsonValue

data class JsonString(val value: String) : JsonValue

data class JsonArray(val items: List<JsonValue>) : JsonValue

data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue {
	operator fun get(key: String): JsonValue? = fields[key]
}

class JsonException(message: String) : Exception(message)

fun obj(vararg fields: Pair<String, JsonValue>): JsonObject = JsonObject(mapOf(*fields))

fun JsonValue.string(): String =
    (this as? JsonString)?.value ?: throw JsonException("expected string")

fun JsonValue.long(): Long = (this as? JsonInt)?.value ?: throw JsonException("expected integer")

fun JsonValue.array(): List<JsonValue> =
    (this as? JsonArray)?.items ?: throw JsonException("expected array")

fun JsonValue.obj(): JsonObject = this as? JsonObject ?: throw JsonException("expected object")

fun JsonObject.require(key: String): JsonValue =
    fields[key] ?: throw JsonException("missing field $key")

fun jsonOrNull(value: String?): JsonValue = if (value == null) JsonNull else JsonString(value)
