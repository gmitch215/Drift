package dev.gmitch215.drift.diff

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.jsonOrNull
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Dimension
import dev.gmitch215.drift.model.ProbeResult
import dev.gmitch215.drift.model.ProbeStatus
import dev.gmitch215.drift.model.Stability

data class AttributeChange(val path: String, val before: String?, val after: String?)

/** [UNAVAILABLE] means a side has no usable transcript: missing evidence, never a pass. */
enum class ProbeChangeStatus { SAME, DIFFERENT, UNAVAILABLE }

data class ProbeChange(
	val id: String,
	val before: String?,
	val after: String?,
	val status: ProbeChangeStatus,
) {
	fun toJson(): JsonObject = obj(
		"id" to JsonString(id),
		"before" to jsonOrNull(before),
		"after" to jsonOrNull(after),
		"status" to JsonString(status.name.lowercase()),
	)
}

/**
 * Everything that differs between two capsules; `changes` is static, `ignored` is not, and
 * `probes` lists every probe id with its status.
 */
data class DiffResult(
	val changes: List<AttributeDiff>,
	val ignored: List<AttributeDiff>,
	val probes: List<ProbeChange>,
) {
	/** True when nothing differs; an unavailable probe is missing evidence, not a difference. */
	val isEmpty: Boolean
		get() = changes.isEmpty() && ignored.isEmpty() &&
			probes.none { it.status == ProbeChangeStatus.DIFFERENT }

	fun toJson(): JsonObject = obj(
		"changes" to JsonArray(changes.map { it.toJson() }),
		"ignored" to JsonArray(ignored.map { it.toJson() }),
		"probes" to JsonArray(probes.map { it.toJson() }),
	)
}

object CapsuleDiff {
	fun attributes(a: Capsule, b: Capsule): List<AttributeChange> {
		val left = a.attributes.filter { it.stability == Stability.STATIC }.associate {
			it.path to
				it.value
		}
		val right = b.attributes.filter { it.stability == Stability.STATIC }.associate {
			it.path to
				it.value
		}
		return (left.keys + right.keys).sorted()
			.filter { left[it] != right[it] }
			.map { AttributeChange(it, left[it], right[it]) }
	}

	/** Always `green` against `red`: direction decides which side a rule or a ranking reads. */
	fun diff(green: Capsule, red: Capsule): DiffResult {
		val left = green.attributes.associateBy { it.path }
		val right = red.attributes.associateBy { it.path }
		val order = compareBy<AttributeDiff>(
			{ it.dimension?.ordinal ?: Int.MAX_VALUE },
			{ it.path },
		)
		val (changes, ignored) = (left.keys + right.keys)
			.mapNotNull { attribute(it, left[it], right[it]) }
			.sortedWith(order)
			.partition { it.stability == Stability.STATIC }
		return DiffResult(changes, ignored, probes(green, red))
	}

	private fun attribute(path: String, a: Attribute?, b: Attribute?): AttributeDiff? {
		val before = a?.let { Normalize.value(path, it.value) }
		val after = b?.let { Normalize.value(path, it.value) }
		if (before == after) return null
		val kind = when {
			before == null -> DiffKind.ADDED
			after == null -> DiffKind.REMOVED
			else -> DiffKind.CHANGED
		}
		return AttributeDiff(
			path = path,
			dimension = Dimension.of(path),
			kind = kind,
			before = before,
			after = after,
			stability = listOfNotNull(a, b).maxOf { it.stability },
			order = if (before != null && after != null) {
				// equal cores with different text (a date, a build suffix) are not equal values
				Version.order(before, after, VersionScheme.of(path))
					.takeIf { it != Order.EQUAL } ?: Order.UNORDERED
			} else {
				null
			},
		)
	}

	private fun probes(green: Capsule, red: Capsule): List<ProbeChange> {
		val left = green.probes.associateBy { it.id }
		val right = red.probes.associateBy { it.id }
		return (left.keys + right.keys).sorted().map { id ->
			val before = left[id].usable()
			val after = right[id].usable()
			val status = when {
				before == null || after == null -> ProbeChangeStatus.UNAVAILABLE
				before == after -> ProbeChangeStatus.SAME
				else -> ProbeChangeStatus.DIFFERENT
			}
			ProbeChange(id, before, after, status)
		}
	}

	private fun ProbeResult?.usable(): String? =
		this?.takeIf { it.status == ProbeStatus.OK }?.transcript
}
