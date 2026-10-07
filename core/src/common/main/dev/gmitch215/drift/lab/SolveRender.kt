package dev.gmitch215.drift.lab

import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.math.FixedPoint

/** Plain text for a [SolveResult] at three levels; the case directory holds the data. */
object SolveRender {
	fun render(r: SolveResult, detail: Detail): String {
		val out = ReproduceRender.Out()
		val experiments = r.runs.count { it.kind != "pilot" }
		out.text("${r.verdict.kind.label}: ${r.verdict.statement}")
		out.text(
			"${r.hypotheses} candidate changes, $experiments experiments run in containers " +
				"(${r.trials} trials, ${r.seconds} seconds), not on the original host.",
		)
		if (detail == Detail.SUMMARY) return out.finish()
		out.blank()
		out.text("Experiments (failures of trials, raw counts):")
		for (run in r.runs) out.text(line(run), 2)
		val confirmed = r.verdict.kind == VerdictKind.CONFIRMED ||
			r.verdict.kind == VerdictKind.BUNDLE
		if (confirmed) {
			out.text("Mechanism: ${r.verdict.mechanism ?: "unexplained (no rule or probe agreed)"}")
		}
		if (r.verdict.notIsolated.isNotEmpty()) {
			out.text("Not isolated: ${r.verdict.notIsolated.joinToString(", ")}")
		}
		r.verdict.next?.let { out.text("Best next experiment: $it") }
		out.blank()
		for (h in SolveCertificate.honesty) out.text(h)
		if (detail == Detail.DETAIL) return out.finish()
		out.blank()
		out.text("Preregistered rules (sha256 of the rule fixed before the first trial):")
		for (run in r.runs) {
			run.spec?.rule?.let { out.text("${run.id}: ${it.sha256()}", 2) }
		}
		return out.finish()
	}

	private fun line(run: ExperimentRun): String {
		val flips = if (run.flips.isEmpty()) "" else " [${run.flips.joinToString(", ")}]"
		val p = run.p?.let { ", p ${FixedPoint.format(it.micro())}" }.orEmpty()
		val outcome = run.outcome?.let { ", ${it.id}" }.orEmpty()
		return "${run.id} ${run.kind}$flips: ${run.control.label} ${run.control.failures} of " +
			"${run.control.trials.size}, ${run.treatment.label} ${run.treatment.failures} of " +
			"${run.treatment.trials.size}$outcome$p"
	}
}
