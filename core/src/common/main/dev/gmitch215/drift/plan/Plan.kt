package dev.gmitch215.drift.plan

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj

/**
 * A candidate cause flipped as one unit; [Hypothesis.members] are attribute paths or `step:` ids.
 */
data class Hypothesis(val id: String, val members: List<String>) {
	fun toJson(): JsonObject = obj(
		"id" to JsonString(id),
		"members" to JsonArray(members.map(::JsonString)),
	)
}

enum class FrameKind { SHA, ENVIRONMENT }

/**
 * What the two sides of the comparison are. [Frame.passing] and [Frame.failing] are labels used in
 * the printed instructions; [Frame.environment] is where the arms run. The sides may be two commits
 * in one place or one commit in two places (a local run against a CI run).
 */
data class Frame(
	val kind: FrameKind,
	val passing: String,
	val failing: String,
	val environment: Environment,
) {
	fun toJson(): JsonObject = obj(
		"kind" to JsonString(kind.name.lowercase()),
		"passing" to JsonString(passing),
		"failing" to JsonString(failing),
		"environment" to JsonString(environment.id),
	)

	companion object {
		fun shas(green: String, red: String, environment: Environment = Environment.CI) =
			Frame(FrameKind.SHA, green, red, environment)

		fun environments(local: String, ci: String) =
			Frame(FrameKind.ENVIRONMENT, local, ci, Environment.LOCAL)
	}
}

/**
 * Per-run inputs. [Options.failures] of [Options.runs] is the observed failure rate of the red
 * side; [Options.trialMinutes] is the wall time of one trial of the failing command.
 * [Options.couplings] maps an attribute to the hypothesis it always moves with, for one change that
 * shows as several. A target `step:NAME` without a job names the first added step called NAME. The
 * default merges the provisioner build date with its version and the Node and npm versions with
 * `Set up Node`.
 */
data class Options(
	val trialMinutes: Long,
	val failures: Int,
	val runs: Int,
	val alpha: Long = PlanWeights.ALPHA,
	val power: Long = PlanWeights.POWER,
	val maxPerArm: Int = Trials.MAX_PER_ARM,
	val unknown: Long = PlanWeights.UNKNOWN_PRIOR,
	val couplings: Map<String, String> = COUPLINGS,
) {
	init {
		require(trialMinutes >= 1) { "a trial takes at least a minute" }
	}

	companion object {
		val COUPLINGS = mapOf(
			"ci.provisioner.build-date" to "ci.provisioner.version",
			"tool.node.version" to "step:Set up Node",
			"tool.npm.version" to "step:Set up Node",
		)
	}

	val rate: Long get() = Trials.rate(failures, runs)

	fun toJson(): JsonObject = obj(
		"trialMinutes" to JsonInt(trialMinutes),
		"failures" to JsonInt(failures.toLong()),
		"runs" to JsonInt(runs.toLong()),
		"alpha" to JsonInt(alpha),
		"power" to JsonInt(power),
		"maxPerArm" to JsonInt(maxPerArm.toLong()),
		"unknown" to JsonInt(unknown),
		"couplings" to JsonObject(couplings.mapValues { JsonString(it.value) }),
	)
}

enum class Decision { NEXT, ALTERNATIVE, REJECTED, MANUAL }

/**
 * [Planned.perCost] is micro-bits per cost point; [Planned.points] is the weighted cost in
 * micro-points.
 */
data class Planned(
	val experiment: Experiment,
	val gain: Long,
	val points: Long,
	val perCost: Long,
	val decision: Decision,
	val reason: String,
	val branches: List<Branch>,
) {
	fun toJson(ids: List<String>): JsonObject = obj(
		"experiment" to experiment.toJson(ids),
		"gain" to JsonInt(gain),
		"points" to JsonInt(points),
		"perCost" to JsonInt(perCost),
		"decision" to JsonString(decision.name.lowercase()),
		"reason" to JsonString(reason),
		"outcomes" to JsonArray(branches.map { it.toJson() }),
	)

	companion object {
		/** Most information per cost first, then larger gain, lower cost, smaller id. */
		val RANK: Comparator<Planned> = compareBy(
			{ -it.perCost },
			{ -it.gain },
			{ it.points },
			{ it.experiment.id },
		)
	}
}

enum class Status { READY, STUCK }

enum class StuckCode(val id: String) {
	NO_HYPOTHESES("no-hypotheses"),
	UNCONTROLLABLE("uncontrollable"),
	BUDGET("budget"),
	NO_INFORMATION("no-information"),
}

data class StuckReason(val code: StuckCode, val detail: String) {
	fun toJson(): JsonObject = obj(
		"code" to JsonString(code.id),
		"detail" to JsonString(detail),
	)
}

/** Hypotheses no controllable experiment can tell apart, after every proposed one has run. */
data class Unseparated(val ids: List<String>, val reason: String) {
	fun toJson(): JsonObject = obj(
		"ids" to JsonArray(ids.map(::JsonString)),
		"reason" to JsonString(reason),
	)
}

data class Excluded(val id: String, val reason: String) {
	fun toJson(): JsonObject = obj("id" to JsonString(id), "reason" to JsonString(reason))
}

/**
 * Experiments ranked by information gain per cost, with the posterior for every outcome of each.
 * [Plan.experiments] lists the ranked ones first, then the rejected, then the manual ones. The full
 * detail is always present; how much to show is the renderer's choice.
 */
data class Plan(
	val frame: Frame,
	val options: Options,
	val trials: TrialPlan,
	val hypotheses: List<Hypothesis>,
	val prior: Belief,
	val status: Status,
	val stuck: List<StuckReason>,
	val experiments: List<Planned>,
	val unseparated: List<Unseparated>,
	val excluded: List<Excluded>,
) {
	val next: Planned? get() = experiments.firstOrNull { it.decision == Decision.NEXT }

	fun toJson(): JsonObject {
		val ids = prior.masses.map { it.id }
		return obj(
			"weights" to PlanWeights.toJson(),
			"frame" to frame.toJson(),
			"options" to options.toJson(),
			"trials" to trials.toJson(),
			"hypotheses" to JsonArray(hypotheses.map { it.toJson() }),
			"prior" to prior.toJson(),
			"status" to JsonString(status.name.lowercase()),
			"stuck" to JsonArray(stuck.map { it.toJson() }),
			"experiments" to JsonArray(experiments.map { it.toJson(ids) }),
			"unseparated" to JsonArray(unseparated.map { it.toJson() }),
			"excluded" to JsonArray(excluded.map { it.toJson() }),
		)
	}

	fun json(): String = CanonicalJson.encode(toJson())
}
