package dev.gmitch215.drift.lab

import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.math.FixedPoint
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import dev.gmitch215.drift.plan.PlanWeights
import dev.gmitch215.drift.plan.TrialPlan
import dev.gmitch215.drift.plan.Trials

/**
 * The most an experiment may spend: [Budget.maxTrials] over both arms and [Budget.maxMinutes] of
 * waiting.
 */
data class Budget(val maxTrials: Int, val maxMinutes: Long) {
	init {
		require(maxTrials >= 0 && maxMinutes >= 0) { "a budget cannot be negative" }
	}
}

enum class Shortage(val id: String) {
	NO_RATE("no-rate"),
	BUDGET("budget"),
	UNREACHABLE("unreachable"),
}

/**
 * Whether an arm pair can reach significance within a budget. [Sufficient] carries the planner's
 * own trial plan; [Insufficient] carries the numbers it would take, never a guess.
 */
sealed interface Sizing {
	fun toJson(): JsonObject

	data class Sufficient(val plan: TrialPlan, val trials: Int, val minutes: Long) : Sizing {
		override fun toJson(): JsonObject = obj(
			"status" to JsonString("sufficient"),
			"plan" to plan.toJson(),
			"trials" to JsonInt(trials.toLong()),
			"minutes" to JsonInt(minutes),
		)
	}

	/**
	 * [Sizing.Insufficient.neededPerArm], [Sizing.Insufficient.shortfallTrials] (over both arms)
	 * and the minutes are null when no count is known: no completed run to take a rate from, or a
	 * rate no count up to the exact test's limit of [Trials.MAX_PER_ARM] per arm reaches at the
	 * target power.
	 */
	data class Insufficient(
		val reason: Shortage,
		val allowedPerArm: Int,
		val neededPerArm: Int?,
		val shortfallTrials: Int?,
		val neededMinutes: Long?,
		val shortfallMinutes: Long?,
		val detail: String,
	) : Sizing {
		override fun toJson(): JsonObject = obj(
			"status" to JsonString("insufficient"),
			"reason" to JsonString(reason.id),
			"allowedPerArm" to JsonInt(allowedPerArm.toLong()),
			"neededPerArm" to number(neededPerArm?.toLong()),
			"shortfallTrials" to number(shortfallTrials?.toLong()),
			"neededMinutes" to number(neededMinutes),
			"shortfallMinutes" to number(shortfallMinutes),
			"detail" to JsonString(detail),
		)

		private fun number(value: Long?) = value?.let(::JsonInt) ?: JsonNull
	}
}

/** Connects the planner's trial count to a failure rate, an alpha and a budget. */
object TrialSizing {
	/** Failures and completed runs: cancelled and unknown runs say nothing about the rate. */
	fun counts(runs: List<Run>): Pair<Int, Int> {
		val failed = runs.count { it.outcome == RunOutcome.FAIL }
		return failed to failed + runs.count { it.outcome == RunOutcome.PASS }
	}

	/** The rate is the planner's Laplace estimate over the completed [runs]. */
	fun fromRuns(
		runs: List<Run>,
		budget: Budget,
		trialMinutes: Long,
		alpha: Long = PlanWeights.ALPHA,
		power: Long = PlanWeights.POWER,
	): Sizing {
		val (failures, completed) = counts(runs)
		if (completed == 0) {
			return Sizing.Insufficient(
				Shortage.NO_RATE,
				allowed(budget, trialMinutes),
				null,
				null,
				null,
				null,
				"no completed run to take a failure rate from",
			)
		}
		return fromRate(Trials.rate(failures, completed), budget, trialMinutes, alpha, power)
	}

	/** [rate], [alpha] and [power] are micro-units; [trialMinutes] is one trial's wall time. */
	fun fromRate(
		rate: Long,
		budget: Budget,
		trialMinutes: Long,
		alpha: Long = PlanWeights.ALPHA,
		power: Long = PlanWeights.POWER,
	): Sizing {
		val allowed = allowed(budget, trialMinutes)
		val within = Trials.plan(rate, alpha, power, allowed)
		val perArm = within.perArm
		if (perArm != null) return Sizing.Sufficient(within, 2 * perArm, 2L * perArm * trialMinutes)
		val needed = Trials.plan(rate, alpha, power, Trials.MAX_PER_ARM).perArm
		val target = "power ${FixedPoint.format(power)} at alpha ${FixedPoint.format(alpha)} " +
			"and failure rate ${FixedPoint.format(rate)}"
		if (needed == null) {
			return Sizing.Insufficient(
				Shortage.UNREACHABLE,
				allowed,
				null,
				null,
				null,
				null,
				"no count up to ${Trials.MAX_PER_ARM} trials per arm reaches $target",
			)
		}
		val neededMinutes = 2L * needed * trialMinutes
		val shortMinutes = (neededMinutes - budget.maxMinutes).coerceAtLeast(0)
		val shortTrials = 2 * (needed - allowed)
		return Sizing.Insufficient(
			Shortage.BUDGET,
			allowed,
			needed,
			shortTrials,
			neededMinutes,
			shortMinutes,
			"the budget allows $allowed trials per arm; $target needs $needed per arm " +
				"(${2 * needed} trials, $neededMinutes minutes): short by $shortTrials trials " +
				"and $shortMinutes minutes",
		)
	}

	/** Trials per arm the budget allows: both arms run in turn, one trial at a time. */
	private fun allowed(budget: Budget, trialMinutes: Long): Int {
		require(trialMinutes >= 1) { "a trial takes at least a minute" }
		val byMinutes = budget.maxMinutes / (2 * trialMinutes)
		return minOf(budget.maxTrials / 2L, byMinutes, Trials.MAX_PER_ARM.toLong()).toInt()
	}
}
