package dev.gmitch215.drift.plan

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj

/** Cost points per whole unit, in micro-points, and why. */
data class CostWeight(val id: String, val perUnit: Long, val reason: String)

/**
 * Every number the planner chooses by hand, in one place, like the ranker's weights: reasoned
 * guesses, not calibrated. They rank experiments against each other; a cost point is not money
 * and an information gain from an uncalibrated prior is not a measured quantity.
 */
object PlanWeights {
	const val ID = "plan-hand-set-1"
	const val CALIBRATED = false

	val minute = CostWeight("cost.minute", 1_000_000, "wall-clock time somebody waits")

	val runnerMinute = CostWeight(
		"cost.runner-minute",
		2_000_000,
		"hosted runner minutes are billed and queue behind other jobs",
	)

	val trial = CostWeight("cost.trial", 500_000, "per-trial setup and reading the result")

	/** A dispatched workflow waits for a runner before its first trial. */
	const val QUEUE_MINUTES = 5L

	/** A manual arm needs a person and hardware Drift does not control. */
	const val MANUAL_MINUTES = 120L

	/** Mass kept for "none of these": the list holds only what changed between two captures. */
	const val UNKNOWN_PRIOR = 250_000L

	const val ALPHA = 50_000L

	const val POWER = 800_000L

	fun points(cost: Cost): Long = cost.minutes * minute.perUnit +
			cost.runnerMinutes * runnerMinute.perUnit +
			cost.trials * trial.perUnit

	fun toJson(): JsonObject {
		fun weight(w: CostWeight) = obj(
			"id" to JsonString(w.id),
			"perUnit" to JsonInt(w.perUnit),
			"reason" to JsonString(w.reason),
		)
		return obj(
			"id" to JsonString(ID),
			"calibrated" to JsonBool(CALIBRATED),
			"costs" to JsonArray(listOf(minute, runnerMinute, trial).map(::weight)),
			"queueMinutes" to JsonInt(QUEUE_MINUTES),
			"manualMinutes" to JsonInt(MANUAL_MINUTES),
			"noiseFloor" to JsonInt(OutcomeTable.NOISE_FLOOR),
		)
	}
}
