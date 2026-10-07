package dev.gmitch215.drift.plan

import dev.gmitch215.drift.delta.Ddmin
import dev.gmitch215.drift.delta.Verdict
import dev.gmitch215.drift.history.FailingStep
import dev.gmitch215.drift.math.FixedPoint
import dev.gmitch215.drift.model.Dimension
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.rank.Ranking

/**
 * One-step look-ahead experiment planner. Experiments come from Zeller's ddmin run over the
 * controllable hypotheses: for every possible single culprit, the subsets ddmin would test are
 * proposed, so the pool holds the full bundle, its halves, quarters and single members. Each
 * arm sets a subset to the failing side's values and holds the rest at the passing side's.
 * What cannot be set from here is listed as a manual experiment and never as an automatic one.
 */
object Planner {
	/**
	 * Builds the hypotheses and the prior from a [ranking] and plans from there. Candidates
	 * that couple to the same hypothesis (see [Options.couplings]) take the best member score.
	 * Added steps become hypotheses; a `Post` step joins its main step, and a step that ran
	 * after the failing step of [red] cannot have caused it and is excluded with its reason.
	 */
	fun from(
		ranking: Ranking,
		frame: Frame,
		options: Options,
		red: Run? = null,
		controls: Controls = Controls.standard,
	): Plan {
		val members = mutableMapOf<String, List<String>>()
		val scores = mutableMapOf<String, Long>()
		val steps = ranking.transition?.steps.orEmpty()
		fun target(path: String): String {
			val to = options.couplings[path] ?: return path
			if (!to.startsWith("step:") || '/' in to) return to
			val step = steps.firstOrNull { "step:${it.name}" == to } ?: return path
			return stepId(step.job, step.name)
		}
		for (c in ranking.candidates) {
			val id = target(c.path)
			members[id] = members[id].orEmpty() + c.path
			scores[id] = maxOf(scores[id] ?: c.score, c.score)
		}
		val stepScore = ranking.candidates.minOfOrNull { it.score } ?: 0L
		val excluded = mutableListOf<Excluded>()
		val names = steps.map { it.job to it.name }.toSet()
		val failing = red?.let { FailingStep.of(it) }
		val failedAt = failing?.let { f ->
			red.steps.indexOfFirst { it.name == f.name && it.job == f.job }
		} ?: -1
		for (s in steps) {
			if (s.name.startsWith("Post ") && (s.job to s.name.drop(5)) in names) continue
			val id = stepId(s.job, s.name)
			val at = red?.steps?.indexOfFirst { it.job == s.job && it.name == s.name } ?: -1
			if (failedAt >= 0 && at > failedAt) {
				val why = "ran after the failing step ${failing?.name}, so it cannot have caused it"
				excluded += Excluded(id, why)
				continue
			}
			val post = (s.job to "Post ${s.name}") in names
			val joined = if (post) listOf(stepId(s.job, "Post ${s.name}")) else emptyList()
			members[id] = members[id].orEmpty() + id + joined
			scores[id] = maxOf(scores[id] ?: stepScore, stepScore)
		}
		val hypotheses = members.keys.sorted().map { Hypothesis(it, members.getValue(it).sorted()) }
		val top = scores.values.maxOrNull() ?: 0L
		val weights = hypotheses.associate {
			val gap = (scores.getValue(it.id) - top).coerceAtLeast(-20 * FixedPoint.ONE)
			it.id to FixedPoint.exp(gap).coerceAtLeast(1L)
		}
		val prior = Belief.of(weights, options.unknown)
		return plan(hypotheses, prior, frame, options, controls, excluded)
	}

	/**
	 * Plans for [hypotheses] under [prior]; feed a branch's posterior back in to continue.
	 * [blocked] maps a hypothesis id to why an executor could not set it: it is planned as
	 * manual with that reason, so an experiment the controls allow but the arms cannot run is
	 * never ranked as automatic.
	 */
	fun plan(
		hypotheses: List<Hypothesis>,
		prior: Belief,
		frame: Frame,
		options: Options,
		controls: Controls = Controls.standard,
		excluded: List<Excluded> = emptyList(),
		blocked: Map<String, String> = emptyMap(),
	): Plan {
		val sorted = hypotheses.map { it.copy(members = it.members.sorted()) }.sortedBy { it.id }
		return Round(sorted, prior, frame, options, controls, blocked)
			.plan(excluded.sortedBy { it.id })
	}

	private fun stepId(job: String?, name: String) =
		if (job == null) "step:$name" else "step:$job/$name"

	private class Round(
		val hypotheses: List<Hypothesis>,
		val prior: Belief,
		val frame: Frame,
		val options: Options,
		val controls: Controls,
		val blocked: Map<String, String>,
	) {
		val byId = hypotheses.associateBy { it.id }
		val ids = prior.masses.map { it.id }
		val trials = Trials.plan(options.rate, options.alpha, options.power, options.maxPerArm)
		val perArm = trials.perArm ?: options.maxPerArm
		val classes = hypotheses.associate {
			val why = blocked[it.id]
			it.id to if (why != null) {
				Control(InterventionClass.MANUAL, why)
			} else {
				controls.classify(it.members, frame.environment)
			}
		}
		val controllable = classes.filterValues { it.kind != InterventionClass.MANUAL }
			.keys.sorted()
		val manualIds = classes.keys.filter { it !in controllable }.sorted()

		init {
			require(byId.size == hypotheses.size) { "duplicate hypothesis id" }
			require(byId.keys.all { it in ids }) { "every hypothesis needs a prior mass" }
		}

		fun plan(excluded: List<Excluded>): Plan {
			val executable = pool().mapIndexed { i, flips -> build("e${i + 1}", flips) }
			val scored = executable.map { planned(it, Decision.REJECTED, "") }
			val ranked = if (trials.perArm == null) {
				emptyList()
			} else {
				scored.filter { it.gain > 0 }.sortedWith(Planned.RANK)
			}
			val rankedOut = ranked.mapIndexed { i, p ->
				if (i == 0) {
					val why = "highest information gain per cost among the experiments that can run"
					p.copy(decision = Decision.NEXT, reason = why)
				} else {
					val why = "lower information gain per cost than ${ranked[0].experiment.id}"
					p.copy(decision = Decision.ALTERNATIVE, reason = why)
				}
			}
			val kept = ranked.map { it.experiment.id }.toSet()
			val rejectedOut = scored.filter { it.experiment.id !in kept }
				.map { it.copy(reason = rejection()) }
			val manualOut = manualIds.mapIndexed { i, id ->
				val why = classes.getValue(id).reason
				planned(build("m${i + 1}", listOf(id)), Decision.MANUAL, why)
			}.sortedWith(Planned.RANK)
			val stuck = stuck(ranked.isEmpty(), manualOut)
			return Plan(
				frame,
				options,
				trials,
				hypotheses,
				prior,
				if (stuck.isEmpty()) Status.READY else Status.STUCK,
				stuck,
				rankedOut + rejectedOut + manualOut,
				unseparated(executable, manualOut),
				excluded,
			)
		}

		fun pool(): List<List<String>> {
			val pool = linkedSetOf<List<String>>()
			for (culprit in controllable) {
				val result = Ddmin.run(controllable) {
					if (culprit in it) Verdict.FAIL else Verdict.PASS
				}
				result.sequence.mapTo(pool) { subset -> subset.map { controllable[it] } }
			}
			return pool.sortedWith(compareBy({ it.size }, { it.joinToString("\u0000") }))
		}

		fun build(id: String, flips: List<String>): Experiment {
			val attributes = flips.flatMap { byId.getValue(it).members }
			val control = flips.firstNotNullOfOrNull { blocked[it] }
				?.let { Control(InterventionClass.MANUAL, it) }
				?: controls.classify(attributes, frame.environment)
			val n = 2L * perArm
			val t = options.trialMinutes
			val cost = when (control.kind) {
				InterventionClass.AUTOMATIC_LOCAL -> Cost(n * t, 0, n)
				InterventionClass.AUTOMATIC_CI -> Cost(PlanWeights.QUEUE_MINUTES + t, n * t, n)
				InterventionClass.MANUAL -> Cost(PlanWeights.MANUAL_MINUTES + n * t, 0, n)
			}
			val dimensions = attributes.mapNotNull { Dimension.of(it)?.id }.distinct().sorted()
			val table = OutcomeTable.binary(ids, flips.toSet(), trials.power, options.alpha)
			val label = "${frame.passing} with ${flips.joinToString(", ")}" +
				" set to ${frame.failing} values"
			val arms = listOf(Arm("${frame.passing} unchanged", emptyList()), Arm(label, flips))
			val text = instructions(control, flips)
			return Experiment(id, control.kind, flips, dimensions, cost, table, arms, text)
		}

		fun planned(e: Experiment, decision: Decision, reason: String): Planned {
			val branches = Information.branches(prior, e.table)
			val gain = Information.gain(prior, branches)
			val points = PlanWeights.points(e.cost)
			val perCost = gain * FixedPoint.ONE / points
			return Planned(e, gain, points, perCost, decision, reason, branches)
		}

		fun rejection(): String = if (trials.perArm == null) {
			"$perArm trials per arm cannot reach alpha at the target power " +
				"(best ${FixedPoint.format(trials.power)})"
		} else {
			"no information: no outcome changes the belief"
		}

		fun stuck(noneRanked: Boolean, manual: List<Planned>): List<StuckReason> = buildList {
			if (hypotheses.isEmpty()) {
				add(StuckReason(StuckCode.NO_HYPOTHESES, "no candidate cause to test"))
			}
			if (controllable.isEmpty() && manualIds.isNotEmpty()) {
				val why = "the discriminating dimension is uncontrollable: ${reasons(manual)}"
				add(StuckReason(StuckCode.UNCONTROLLABLE, why))
			}
			if (controllable.isNotEmpty() && trials.perArm == null) {
				val why = "no arm of up to $perArm trials reaches alpha at the target power"
				add(StuckReason(StuckCode.BUDGET, why))
			}
			if (controllable.isNotEmpty() && trials.perArm != null && noneRanked) {
				val why = "no controllable experiment changes the belief"
				add(StuckReason(StuckCode.NO_INFORMATION, why))
			}
		}

		fun unseparated(executable: List<Experiment>, manual: List<Planned>): List<Unseparated> =
			ids.groupBy { id -> executable.map { it.table.row(id) } }.values
				.filter { it.size > 1 }
				.map { group ->
					val unmet = manual.filter { it.experiment.flips.single() in group }
					val reason = if (unmet.isEmpty()) {
						"no proposed experiment separates these"
					} else {
						"no controllable experiment separates these: ${reasons(unmet)}"
					}
					Unseparated(group.sorted(), reason)
				}.sortedBy { it.ids.first() }

		fun reasons(manual: List<Planned>) =
			manual.joinToString("; ") { "${it.experiment.flips.single()}: ${it.reason}" }

		fun instructions(control: Control, flips: List<String>): String {
			val what = flips.joinToString(", ")
			val alpha = FixedPoint.format(options.alpha)
			val compare = "Compare the failure counts with a one-sided Fisher exact test at " +
				"alpha $alpha; the failure is reproduced when the treatment arm fails " +
				"significantly more often."
			val runs = "Run each arm $perArm times."
			val set = "set $what to the ${frame.failing} values"
			return when (control.kind) {
				InterventionClass.AUTOMATIC_LOCAL ->
					"Build a local container from the ${frame.passing} capture and $set. $runs " +
						"Keep an unchanged container as the control. $compare"

				InterventionClass.AUTOMATIC_CI ->
					"Write a workflow variant of ${frame.passing} that does this: $set. Add an " +
						"unchanged copy as the control. $runs $compare"

				InterventionClass.MANUAL ->
					"Not automatic: ${control.reason}. On a host you control, $set and keep an " +
						"unchanged host as the control. $runs $compare Record the counts as an " +
						"observation."
			}
		}
	}
}
