package dev.gmitch215.drift.lab

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.math.FixedPoint
import dev.gmitch215.drift.plan.Experiment
import dev.gmitch215.drift.plan.Fisher
import dev.gmitch215.drift.plan.Frame
import dev.gmitch215.drift.plan.Hypothesis
import dev.gmitch215.drift.plan.InterventionClass
import dev.gmitch215.drift.plan.Trials

enum class Outcome(val id: String) {
	SUPPORTED("supported"),
	REFUTED("refuted"),
	INCONCLUSIVE("inconclusive"),
}

/** [Counts.failures] of [Counts.trials] trials of one arm. */
data class Counts(val failures: Int, val trials: Int) {
	init {
		require(trials >= 1 && failures in 0..trials) { "counts out of range" }
	}
}

/**
 * The decision rule, fixed before any trial runs. The treatment arm is SUPPORTED when it fails more
 * often than the control with an exact one-sided Fisher p at most [DecisionRule.alpha]; otherwise
 * it is REFUTED when the chance that a treatment arm failing at the [DecisionRule.rate] seen on the
 * failing side would fail as seldom as observed is at most [DecisionRule.alpha] (a binomial lower
 * tail, floored, so it can only be too high and never calls a refutation early); otherwise it is
 * INCONCLUSIVE. SUPPORTED is decided first. [DecisionRule.perArm] and [DecisionRule.threshold] are
 * the planned trials and the failures that a clean control needs.
 */
data class DecisionRule(val perArm: Int, val threshold: Int, val alpha: Long, val rate: Long) {
	fun evaluate(treatment: Counts, control: Counts): Outcome {
		require(treatment.trials + control.trials <= Fisher.MAX_TOTAL) { "too many trials" }
		val p = Fisher.oneSided(
			treatment.failures,
			treatment.trials - treatment.failures,
			control.failures,
			control.trials - control.failures,
		)
		if (p.atMost(alpha)) return Outcome.SUPPORTED
		val lowerTail =
			FixedPoint.ONE - Trials.power(treatment.trials, treatment.failures + 1, rate)
		if (lowerTail <= alpha) return Outcome.REFUTED
		return Outcome.INCONCLUSIVE
	}

	fun toJson(): JsonObject {
		val alphaText = FixedPoint.format(alpha)
		return obj(
			"test" to JsonString(
				"one-sided Fisher exact, treatment failures against control failures",
			),
			"alpha" to JsonInt(alpha),
			"rate" to JsonInt(rate),
			"perArm" to JsonInt(perArm.toLong()),
			"threshold" to JsonInt(threshold.toLong()),
			"supported" to JsonString("the exact one-sided p-value is at most $alphaText"),
			"refuted" to JsonString(
				"not supported, and the chance of at most the observed treatment failures at the " +
					"failure rate ${FixedPoint.format(rate)} per trial is at most $alphaText",
			),
			"inconclusive" to JsonString("any other result"),
		)
	}

	fun json(): String = CanonicalJson.encode(toJson())

	fun sha256(): String = Sha256.hex(json())

	companion object {
		fun of(sizing: Sizing.Sufficient): DecisionRule = DecisionRule(
			requireNotNull(sizing.plan.perArm),
			requireNotNull(sizing.plan.threshold),
			sizing.plan.alpha,
			sizing.plan.rate,
		)
	}
}

/** One arm of a spec; the hashes are null for an arm nobody can run from here. */
data class ArmSpec(
	val role: String,
	val label: String,
	val command: String?,
	val interventions: List<Intervention>,
	val files: Map<String, String?>,
	val capsuleSha256: String?,
	val dockerfileSha256: String?,
	val runScriptSha256: String?,
	val manifestSha256: String?,
) {
	fun toJson(): JsonObject = obj(
		"role" to JsonString(role),
		"label" to JsonString(label),
		"command" to (command?.let(::JsonString) ?: JsonNull),
		"interventions" to JsonArray(interventions.map { it.toJson() }),
		"files" to JsonObject(files.mapValues { it.value?.let(::JsonString) ?: JsonNull }),
		"capsuleSha256" to hash(capsuleSha256),
		"dockerfileSha256" to hash(dockerfileSha256),
		"runScriptSha256" to hash(runScriptSha256),
		"manifestSha256" to hash(manifestSha256),
	)

	private fun hash(value: String?) = value?.let(::JsonString) ?: JsonNull
}

/**
 * Everything 6d3 needs to run one experiment, and everything a reader needs to check it was decided
 * in advance: the arms with their interventions and file hashes, the predicted diff and the verdict
 * it allows, the trials, and the [rule] with its hash. It holds no result, runs nothing and touches
 * no network. A manual experiment is the same file with no runnable arms and the printed
 * [ExperimentSpec.instructions]. [executable] is false unless every member can be set and the
 * budget reaches significance.
 */
class ExperimentSpec private constructor(
	val id: String,
	val frame: Frame,
	val kind: InterventionClass,
	val pairing: Pairing?,
	val arms: List<ArmSpec>,
	val diff: ArmDiff?,
	val verdict: BundleVerdict?,
	val blockers: List<Blocker>,
	val sizing: Sizing,
	val instructions: String,
) {
	val rule: DecisionRule? = (sizing as? Sizing.Sufficient)?.let { DecisionRule.of(it) }

	val executable: Boolean
		get() = kind != InterventionClass.MANUAL && blockers.isEmpty() && rule != null &&
			arms.all { it.capsuleSha256 != null }

	fun toJson(): JsonObject = obj(
		"schema" to JsonInt(SCHEMA),
		"id" to JsonString(id),
		"frame" to frame.toJson(),
		"class" to JsonString(kind.id),
		"executable" to JsonBool(executable),
		"pairing" to (pairing?.let { JsonString(it.id) } ?: JsonNull),
		"arms" to JsonArray(arms.map { it.toJson() }),
		"diff" to (diff?.toJson() ?: JsonNull),
		"verdict" to (verdict?.toJson() ?: JsonNull),
		"blockers" to JsonArray(blockers.map { it.toJson() }),
		"trials" to sizing.toJson(),
		"rule" to (rule?.toJson() ?: JsonNull),
		"ruleSha256" to (rule?.let { JsonString(it.sha256()) } ?: JsonNull),
		"instructions" to JsonString(instructions),
	)

	fun json(): String = CanonicalJson.encode(toJson())

	fun sha256(): String = Sha256.hex(json())

	fun text(): String {
		val out = ReproduceRender.Out()
		val state = if (executable) "executable" else "not executable"
		out.text("Experiment $id, class ${kind.id}, $state")
		out.text(
			"Frame: ${frame.passing} (passing) and ${frame.failing} (failing), " +
				"runs in ${frame.environment.id}",
		)
		for (arm in arms) {
			out.text("${arm.role.replaceFirstChar { it.uppercase() }}: ${arm.label}")
			arm.command?.let { out.text("command: $it", 2) }
			for (i in arm.interventions) out.text("- ${i.describe()}", 2)
		}
		if (blockers.isNotEmpty()) {
			out.text("Not settable from here:")
			for (b in blockers) out.text("${b.member}: ${b.reason}", 2)
		}
		if (diff != null) {
			val ignored = if (diff.ignored.isEmpty()) {
				""
			} else {
				"; ignored: ${diff.ignored.joinToString(", ")}"
			}
			out.text(
				"Predicted difference between the arms (${diff.changed.size}): " +
				diff.changed.joinToString(", ").ifEmpty { "none" } + ignored,
			)
		}
		verdict?.let { out.text("If the effect holds: ${it.kind}. ${it.reason}") }
		when (sizing) {
			is Sizing.Sufficient -> {
				val plan = sizing.plan
				out.text(
					"Trials: ${plan.perArm} per arm (${sizing.trials} in total, " +
						"${sizing.minutes} minutes); with a clean control at least " +
						"${plan.threshold} failures reach " +
						"alpha ${FixedPoint.format(plan.alpha)}.",
				)
			}

			is Sizing.Insufficient -> out.text("Trials: insufficient. ${sizing.detail}")
		}
		rule?.let {
			out.text("Rule, fixed before any trial (sha256 ${it.sha256()}):")
			val json = it.toJson()
			for (key in listOf("supported", "refuted", "inconclusive")) {
				out.text("$key: ${(json[key] as JsonString).value}", 2)
			}
		}
		if (instructions.isNotEmpty()) out.text("Instructions: $instructions")
		return out.finish()
	}

	companion object {
		const val SCHEMA = 1L

		/** The spec of a planner [experiment]; [arms] is null for a manual one. */
		fun from(
			experiment: Experiment,
			frame: Frame,
			arms: ArmSet?,
			sizing: Sizing,
			hypotheses: List<Hypothesis> = emptyList(),
		): ExperimentSpec {
			if (arms != null) {
				return of(experiment.id, frame, arms, sizing, experiment.instructions)
			}
			val labels = experiment.arms.mapIndexed { i, a ->
				ArmSpec(
					if (i == 0) "control" else "treatment",
					a.label,
					null,
					emptyList(),
					emptyMap(),
					null,
					null,
					null,
					null,
				)
			}
			val why = Arms.blockers(experiment, hypotheses, frame.environment).ifEmpty {
				experiment.flips.map { Blocker(it, "no controllable intervention sets it") }
			}
			return ExperimentSpec(
				experiment.id,
				frame,
				InterventionClass.MANUAL,
				Pairing.CONTROL_TREATMENT,
				labels,
				null,
				null,
				why,
				sizing,
				experiment.instructions,
			)
		}

		fun of(
			id: String,
			frame: Frame,
			arms: ArmSet,
			sizing: Sizing,
			instructions: String = "",
		): ExperimentSpec {
			val roles = if (arms.pairing == Pairing.CONTROL_TREATMENT) {
				listOf("control", "treatment")
			} else {
				listOf("a", "b")
			}
			val specs = listOf(arms.left, arms.right).zip(roles) { c, role ->
				val files = c.reproduction?.files()
				ArmSpec(
					role,
					c.label,
					c.command,
					c.interventions,
					c.files,
					c.capsule.hash(),
					files?.getValue("Dockerfile")?.let { Sha256.hex(it) },
					files?.getValue("run.sh")?.let { Sha256.hex(it) },
					files?.getValue("manifest.json")?.let { Sha256.hex(it) },
				)
			}
			return ExperimentSpec(
				id,
				frame,
				arms.kind,
				arms.pairing,
				specs,
				arms.diff,
				arms.verdict,
				arms.blockers,
				sizing,
				instructions,
			)
		}
	}
}
