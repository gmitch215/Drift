package dev.gmitch215.drift.lab

import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.plan.Fisher
import dev.gmitch215.drift.plan.PValue

enum class TrialStatus { PASS, FAIL, ERROR }

/** One trial with its raw exit code and the last line of its output. */
data class TrialRecord(val index: Int, val status: TrialStatus, val exit: Int, val tail: String) {
	fun toJson(): JsonObject = obj(
		"index" to JsonInt(index.toLong()),
		"status" to JsonString(status.name.lowercase()),
		"exit" to JsonInt(exit.toLong()),
		"tail" to JsonString(tail),
	)
}

/** Every trial of one arm; the counts are derived from [ArmRun.trials], never stored apart. */
class ArmRun(
	val role: String,
	val label: String,
	val trials: List<TrialRecord>,
	val seconds: Long,
	val capsule: Capsule?,
) {
	val failures: Int get() = trials.count { it.status == TrialStatus.FAIL }
	val passes: Int get() = trials.count { it.status == TrialStatus.PASS }
	val errors: Int get() = trials.count { it.status == TrialStatus.ERROR }
	val counts: Counts get() = Counts(failures, failures + passes)

	fun toJson(): JsonObject = obj(
		"role" to JsonString(role),
		"label" to JsonString(label),
		"failures" to JsonInt(failures.toLong()),
		"passes" to JsonInt(passes.toLong()),
		"errors" to JsonInt(errors.toLong()),
		"seconds" to JsonInt(seconds),
		"capsule" to (capsule?.let { JsonString(it.hash()) } ?: JsonNull),
		"trials" to JsonArray(trials.map { it.toJson() }),
	)
}

/**
 * The attributes that differ between two arms: [MeasuredDiff.source] is `measured` when both arms
 * were captured inside their containers and `predicted` otherwise; [MeasuredDiff.probes] lists the
 * probe ids whose transcripts differ.
 */
data class MeasuredDiff(
	val source: String,
	val changed: List<String>,
	val ignored: List<String>,
	val probes: List<String>,
) {
	fun toJson(): JsonObject = obj(
		"source" to JsonString(source),
		"changed" to JsonArray(changed.map(::JsonString)),
		"ignored" to JsonArray(ignored.map(::JsonString)),
		"probes" to JsonArray(probes.map(::JsonString)),
	)
}

/**
 * One executed experiment. [ExperimentRun.kind] is pilot, forward, minimal (a subset tried by the
 * minimal-set search), reverse, replicate or split. A reverse run is evaluated the other way round:
 * its outcome is SUPPORTED when the unchanged failing arm (the `control` role) fails more than the
 * reverted one.
 */
class ExperimentRun(
	val seq: Int,
	val id: String,
	val kind: String,
	val flips: List<String>,
	val spec: ExperimentSpec?,
	val control: ArmRun,
	val treatment: ArmRun,
	val outcome: Outcome?,
	val p: PValue?,
	val diff: MeasuredDiff?,
) {
	val reverse: Boolean get() = kind == "reverse"

	fun toJson(): JsonObject {
		val rule = spec?.rule
		return obj(
			"kind" to JsonString("result"),
			"seq" to JsonInt(seq.toLong()),
			"experiment" to JsonString(id),
			"role" to JsonString(kind),
			"flips" to JsonArray(flips.map(::JsonString)),
			"specSha256" to (spec?.let { JsonString(it.sha256()) } ?: JsonNull),
			"ruleSha256" to (rule?.let { JsonString(it.sha256()) } ?: JsonNull),
			"evaluation" to JsonString(
				if (reverse) {
					"the control arm (unchanged failing side) fails more than the treatment arm"
				} else {
					"the treatment arm fails more than the control arm"
				},
			),
			"arms" to JsonArray(listOf(control.toJson(), treatment.toJson())),
			"outcome" to (outcome?.let { JsonString(it.id) } ?: JsonNull),
			"p" to (p?.let { pJson(it) } ?: JsonNull),
			"diff" to (diff?.toJson() ?: JsonNull),
		)
	}

	companion object {
		fun pJson(p: PValue): JsonObject = obj(
			"numerator" to JsonInt(p.numerator),
			"denominator" to JsonInt(p.denominator),
			"micro" to JsonInt(p.micro()),
		)

		/** The exact one-sided p of [more] failing more than [less]. */
		fun pValue(more: Counts, less: Counts): PValue = Fisher.oneSided(
			more.failures,
			more.trials - more.failures,
			less.failures,
			less.trials - less.failures,
		)
	}
}

enum class VerdictKind(val id: String, val label: String) {
	CONFIRMED("confirmed", "CONFIRMED"),
	BUNDLE("confirmed-bundle", "CONFIRMED EFFECT (bundle)"),
	NARROWED("narrowed", "NARROWED"),
	STUCK("stuck", "STUCK"),
}

enum class StuckWhy(val id: String) {
	UNCONTROLLABLE("uncontrollable"),
	NOT_REPRODUCED("not-reproduced"),
	BUDGET("budget"),
	EXECUTOR("executor"),
	NO_CANDIDATES("no-candidates"),
}

/**
 * The outcome of a solve. [SolveVerdict.set] is the minimal causal set (hypothesis ids),
 * [SolveVerdict.members] the attributes the arms differ in, [SolveVerdict.notIsolated] the members
 * of a bundle that no arm has pulled apart; [SolveVerdict.candidates] and [SolveVerdict.next] are
 * for NARROWED.
 */
data class SolveVerdict(
	val kind: VerdictKind,
	val why: StuckWhy?,
	val statement: String,
	val set: List<String> = emptyList(),
	val members: List<String> = emptyList(),
	val notIsolated: List<String> = emptyList(),
	val mechanism: String? = null,
	val candidates: List<String> = emptyList(),
	val next: String? = null,
) {
	fun toJson(): JsonObject = obj(
		"kind" to JsonString(kind.id),
		"label" to JsonString(kind.label),
		"reason" to (why?.let { JsonString(it.id) } ?: JsonNull),
		"statement" to JsonString(statement),
		"minimalSet" to JsonArray(set.map(::JsonString)),
		"members" to JsonArray(members.map(::JsonString)),
		"notIsolated" to JsonArray(notIsolated.map(::JsonString)),
		"mechanism" to (mechanism?.let { JsonString(it) } ?: JsonString("unexplained")),
		"candidates" to JsonArray(candidates.map(::JsonString)),
		"next" to (next?.let { JsonString(it) } ?: JsonNull),
	)
}

/** What a solve produced: the verdict, the case directory with its certificate, and the spend. */
class SolveResult(
	val verdict: SolveVerdict,
	val case: CaseFile,
	val runs: List<ExperimentRun>,
	val trials: Int,
	val seconds: Long,
	val hypotheses: Int,
)
