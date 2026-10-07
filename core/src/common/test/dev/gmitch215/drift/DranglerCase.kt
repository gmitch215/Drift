package dev.gmitch215.drift

import dev.gmitch215.drift.case.CaseBuilder
import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.history.History
import dev.gmitch215.drift.history.HistoryKey
import dev.gmitch215.drift.history.RunOrder
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.plan.Frame
import dev.gmitch215.drift.plan.Options
import dev.gmitch215.drift.plan.Plan
import dev.gmitch215.drift.plan.Planner
import dev.gmitch215.drift.rank.Ranker
import dev.gmitch215.drift.rank.Ranking

object DranglerCase {
	private val history = History.of(
		HistoryKey("e2e", FixtureRuns.JOB),
		(FixtureRuns.reds + FixtureRuns.greens).map { FixtureRuns.run(it) },
		RunOrder.RUN_ID,
	)
	private val transition = history.transition!!

	val ranking: Ranking = Ranker.rank(transition, history.runs, FixtureRuns.JOB)

	val plan: Plan = run {
		val ms = transition.red.steps.filter { it.job == FixtureRuns.JOB }
			.sumOf { it.durationMs ?: 0L }
		val options = Options(
			trialMinutes = (ms + 59_999) / 60_000,
			failures = 3,
			runs = 3,
		)
		Planner.from(
			ranking,
			Frame.shas(transition.green.id, transition.red.id),
			options,
			transition.red,
		)
	}

	fun case(runs: List<Run> = history.runs): CaseFile = CaseBuilder.build(
		"drangler",
		transition.green.capsule!!,
		transition.red.capsule!!,
		ranking,
		plan,
		runs,
	)
}
