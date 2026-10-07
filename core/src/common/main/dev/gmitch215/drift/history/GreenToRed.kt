package dev.gmitch215.drift.history

import dev.gmitch215.drift.diff.AttributeDiff
import dev.gmitch215.drift.diff.CapsuleDiff
import dev.gmitch215.drift.diff.DiffKind
import dev.gmitch215.drift.diff.ProbeChange
import dev.gmitch215.drift.diff.ProbeChangeStatus
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.jsonOrNull
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.model.Step

/**
 * [NO_CAPSULE]: a side has no capsule, so attribute changes are unknown.
 *
 * [UNEXPLAINED]: nothing static changed (volatile differences do not count).
 *
 * [SINGLE]: one observed change, a candidate and not a proven cause.
 *
 * [BUNDLE]: several changes at once, so no single one can be named the cause.
 */
enum class TransitionKind { NO_CAPSULE, UNEXPLAINED, SINGLE, BUNDLE }

data class StepChange(val job: String?, val name: String, val kind: DiffKind) {
	fun toJson(): JsonObject = obj(
		"job" to jsonOrNull(job),
		"name" to JsonString(name),
		"kind" to JsonString(kind.name.lowercase()),
	)
}

/**
 * What changed between the last green and the first red. [GreenToRedResult.changes] are static
 * attribute diffs, [GreenToRedResult.ignored] volatile ones, [GreenToRedResult.probes] probes that
 * differ, [GreenToRedResult.steps] steps added or removed from the job;
 * [GreenToRedResult.background] counts attributes present in either capsule that did not change.
 */
data class GreenToRedResult(
	val green: String,
	val red: String,
	val kind: TransitionKind,
	val changes: List<AttributeDiff>,
	val ignored: List<AttributeDiff>,
	val probes: List<ProbeChange>,
	val steps: List<StepChange>,
	val background: Int,
) {
	fun toJson(): JsonObject = obj(
		"green" to JsonString(green),
		"red" to JsonString(red),
		"kind" to JsonString(kind.name.lowercase()),
		"changes" to JsonArray(changes.map { it.toJson() }),
		"ignored" to JsonArray(ignored.map { it.toJson() }),
		"probes" to JsonArray(probes.map { it.toJson() }),
		"steps" to JsonArray(steps.map { it.toJson() }),
		"background" to JsonInt(background.toLong()),
	)
}

object GreenToRed {
	/** [job] limits the step comparison to one job; null compares every step. */
	fun of(transition: Transition, job: String? = null): GreenToRedResult {
		val green = transition.green
		val red = transition.red
		val steps = steps(green.steps, red.steps, job)
		val before = green.capsule
		val after = red.capsule
		if (before == null || after == null) {
			return GreenToRedResult(
				green.id,
				red.id,
				TransitionKind.NO_CAPSULE,
				emptyList(),
				emptyList(),
				emptyList(),
				steps,
				0,
			)
		}
		val diff = CapsuleDiff.diff(before, after)
		val probes = diff.probes.filter { it.status == ProbeChangeStatus.DIFFERENT }
		val paths = (before.attributes.map { it.path } + after.attributes.map { it.path }).toSet()
		val seen = diff.changes.size + probes.size + steps.size
		val kind = when {
			seen == 0 -> TransitionKind.UNEXPLAINED
			seen == 1 -> TransitionKind.SINGLE
			else -> TransitionKind.BUNDLE
		}
		return GreenToRedResult(
			green.id,
			red.id,
			kind,
			diff.changes,
			diff.ignored,
			probes,
			steps,
			paths.size - diff.changes.size - diff.ignored.size,
		)
	}

	private fun steps(green: List<Step>, red: List<Step>, job: String?): List<StepChange> {
		fun counts(steps: List<Step>) = steps.filter { job == null || it.job == job }
			.groupingBy { it.job to it.name }.eachCount()
		val left = counts(green)
		val right = counts(red)
		return (left.keys + right.keys).flatMap { key ->
			val delta = (right[key] ?: 0) - (left[key] ?: 0)
			val kind = if (delta > 0) DiffKind.ADDED else DiffKind.REMOVED
			List(if (delta < 0) -delta else delta) { StepChange(key.first, key.second, kind) }
		}.sortedWith(compareBy({ it.job ?: "" }, { it.name }, { it.kind }))
	}
}
