package dev.gmitch215.drift.history

import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import dev.gmitch215.drift.model.StepConclusion

/** The step a red run failed in; [FailingStep.job] null matches the step name in any job. */
data class FailingStep(val job: String?, val name: String) {
	companion object {
		fun of(run: Run): FailingStep? {
			val step = run.steps.firstOrNull { it.conclusion == StepConclusion.FAILURE }
			return step?.let { FailingStep(it.job, it.name) }
		}
	}
}

/** [UNKNOWN] is a run with no step evidence, which is never a green. */
enum class Validity {
	EXECUTED,
	VACUOUS,
	UNKNOWN,
	NOT_PASS,
}

object VacuousGreen {
	/**
	 * A pass is [Validity.VACUOUS] when its steps lack the failing step or only mark it skipped,
	 * and [Validity.UNKNOWN] when it has no steps at all or the step has no usable conclusion.
	 */
	fun assess(run: Run, failing: FailingStep): Validity {
		if (run.outcome != RunOutcome.PASS) return Validity.NOT_PASS
		if (run.steps.isEmpty()) return Validity.UNKNOWN
		val matches = run.steps.filter {
			it.name == failing.name && (failing.job == null || it.job == failing.job)
		}
		return when {
			matches.isEmpty() -> Validity.VACUOUS

			matches.any {
				it.conclusion == StepConclusion.SUCCESS || it.conclusion == StepConclusion.FAILURE
			} -> Validity.EXECUTED

			matches.all { it.conclusion == StepConclusion.SKIPPED } -> Validity.VACUOUS

			else -> Validity.UNKNOWN
		}
	}
}
