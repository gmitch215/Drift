package dev.gmitch215.drift.diff

import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.jsonOrNull
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.model.Dimension
import dev.gmitch215.drift.model.Stability

enum class DiffKind { ADDED, REMOVED, CHANGED }

/**
 * One attribute that differs between `green` and `red`; `order` is set for [DiffKind.CHANGED]
 * only. [AttributeDiff.stability] is the less stable of the two sides.
 */
data class AttributeDiff(
	val path: String,
	val dimension: Dimension?,
	val kind: DiffKind,
	val before: String?,
	val after: String?,
	val stability: Stability,
	val order: Order?,
) {
	fun toJson(): JsonObject = obj(
		"path" to JsonString(path),
		"dimension" to jsonOrNull(dimension?.id),
		"kind" to JsonString(kind.name.lowercase()),
		"before" to jsonOrNull(before),
		"after" to jsonOrNull(after),
		"stability" to JsonString(stability.name.lowercase()),
		"order" to jsonOrNull(order?.name?.lowercase()),
	)
}

object Normalize {
	/** Maps the JVM and Kotlin/Native spellings of one operating system to a single name. */
	fun osName(raw: String): String {
		val name = raw.trim().lowercase()
		return when {
			name in setOf("mac", "macos", "macosx", "mac os x", "darwin") -> "macos"
			name.startsWith("windows") || name.startsWith("mingw") -> "windows"
			else -> name
		}
	}

	fun value(path: String, value: String): String = if (path == "os.name") osName(value) else value
}
