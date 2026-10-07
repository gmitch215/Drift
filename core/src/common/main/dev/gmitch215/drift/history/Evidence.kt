package dev.gmitch215.drift.history

import dev.gmitch215.drift.diff.Normalize
import dev.gmitch215.drift.math.FixedPoint
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import dev.gmitch215.drift.model.Stability
import dev.gmitch215.drift.spectrum.Element
import dev.gmitch215.drift.spectrum.Run as SpectrumRun

enum class ExclusionReason {
	/** A pass that did not execute the failing step. */
	VACUOUS,

	/** A pass whose steps are unknown. */
	NO_STEP_EVIDENCE,

	/** Cancelled or unknown outcome. */
	NO_RESULT,

	/** No static attribute in the capsule, so the run cannot score any element. */
	NO_ATTRIBUTES,
}

data class Exclusion(val runId: String, val reason: ExclusionReason)

/**
 * What one included run contributes: [RunEvidence.attributes] distinct static paths, and
 * [RunEvidence.coverage] that count over [Evidence.universe] in micro-units (1_000_000 is every
 * path any run saw).
 */
data class RunEvidence(
	val runId: String,
	val failing: Boolean,
	val attributes: Int,
	val coverage: Long,
)

/**
 * The spectrum input for one failure, with every run left out listed in [Evidence.excluded]. Sparse
 * capsules score weakly, so [Evidence.runs] carries each run's attribute count and coverage for 6a.
 */
data class Evidence(
	val input: List<SpectrumRun>,
	val runs: List<RunEvidence>,
	val excluded: List<Exclusion>,
	val universe: Int,
) {
	companion object {
		/**
		 * Reds count as failing; a pass counts only when [VacuousGreen] says it executed
		 * [failing]. Elements are the static attributes of the capsule, in the order given.
		 */
		fun of(runs: List<Run>, failing: FailingStep): Evidence {
			val included = ArrayList<Pair<Run, Set<Element>>>()
			val excluded = ArrayList<Exclusion>()
			for (run in runs) {
				val reason = when {
					run.outcome == RunOutcome.PASS -> when (VacuousGreen.assess(run, failing)) {
						Validity.VACUOUS -> ExclusionReason.VACUOUS
						Validity.EXECUTED -> null
						else -> ExclusionReason.NO_STEP_EVIDENCE
					}

					run.outcome == RunOutcome.FAIL -> null

					else -> ExclusionReason.NO_RESULT
				}
				val elements = elements(run)
				when {
					reason != null -> excluded += Exclusion(run.id, reason)

					elements.isEmpty() ->
						excluded += Exclusion(run.id, ExclusionReason.NO_ATTRIBUTES)

					else -> included += run to elements
				}
			}
			val universe = included.flatMap { (_, e) -> e.map { it.path } }.toSet().size
			return Evidence(
				input = included.map { (r, e) -> SpectrumRun(r.outcome == RunOutcome.FAIL, e) },
				runs = included.map { (r, e) ->
					val count = e.map { it.path }.toSet().size
					RunEvidence(
						r.id,
						r.outcome == RunOutcome.FAIL,
						count,
						count * FixedPoint.ONE / universe,
					)
				},
				excluded = excluded,
				universe = universe,
			)
		}

		private fun elements(run: Run): Set<Element> =
			run.capsule?.attributes.orEmpty().filter { it.stability == Stability.STATIC }
				.map { Element(it.path, Normalize.value(it.path, it.value)) }.toSet()
	}
}
