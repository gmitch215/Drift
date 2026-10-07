package dev.gmitch215.drift

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.history.History
import dev.gmitch215.drift.history.HistoryKey
import dev.gmitch215.drift.history.RunOrder
import dev.gmitch215.drift.history.Transition
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.math.FixedPoint
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.plan.Belief
import dev.gmitch215.drift.plan.Controls
import dev.gmitch215.drift.plan.Cost
import dev.gmitch215.drift.plan.Decision
import dev.gmitch215.drift.plan.Environment
import dev.gmitch215.drift.plan.Experiment
import dev.gmitch215.drift.plan.Frame
import dev.gmitch215.drift.plan.FrameKind
import dev.gmitch215.drift.plan.Hypothesis
import dev.gmitch215.drift.plan.InterventionClass
import dev.gmitch215.drift.plan.Options
import dev.gmitch215.drift.plan.OutcomeTable
import dev.gmitch215.drift.plan.Plan
import dev.gmitch215.drift.plan.PlanWeights
import dev.gmitch215.drift.plan.Planned
import dev.gmitch215.drift.plan.Planner
import dev.gmitch215.drift.plan.Status
import dev.gmitch215.drift.plan.StuckCode
import dev.gmitch215.drift.rank.Ranker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanTest {
	private val local = Frame.environments("local container", "CI runner")
	private val options = Options(trialMinutes = 4, failures = 4, runs = 5)
	private val one = FixedPoint.ONE

	private fun bundle(n: Int, prefix: String = "env.H") =
		(0 until n).map { Hypothesis("$prefix$it", listOf("$prefix$it")) }

	private fun prior(hypotheses: List<Hypothesis>) =
		Belief.of(hypotheses.associate { it.id to 1L }, PlanWeights.UNKNOWN_PRIOR)

	private fun plan(hypotheses: List<Hypothesis>, frame: Frame = local, opts: Options = options) =
		Planner.plan(hypotheses, prior(hypotheses), frame, opts)

	// #region controls
	@Test
	fun controlsFollowTheLongestPrefixAndNeverGuess() {
		val controls = Controls.standard
		fun kind(path: String, env: Environment) = controls.classify(listOf(path), env).kind
		assertEquals(InterventionClass.AUTOMATIC_LOCAL, kind("env.TZ", Environment.LOCAL))
		assertEquals(InterventionClass.AUTOMATIC_CI, kind("deps.a.version", Environment.CI))
		assertEquals(InterventionClass.AUTOMATIC_LOCAL, kind("tool.libc.glibc", Environment.LOCAL))
		assertEquals(InterventionClass.MANUAL, kind("tool.libc.glibc", Environment.CI))
		assertEquals(InterventionClass.AUTOMATIC_CI, kind("tool.node.version", Environment.CI))
		assertEquals(InterventionClass.AUTOMATIC_CI, kind("step:Job/Set up Node", Environment.CI))
		assertEquals(InterventionClass.MANUAL, kind("step:Job/Set up Node", Environment.LOCAL))
		assertEquals(InterventionClass.AUTOMATIC_LOCAL, kind("os.release.id", Environment.LOCAL))
		for (path in listOf("ci.runner.image.version", "kernel.release", "cpu.model", "os.name")) {
			assertEquals(InterventionClass.MANUAL, kind(path, Environment.LOCAL), path)
			assertEquals(InterventionClass.MANUAL, kind(path, Environment.CI), path)
		}
		val unknown = controls.classify(listOf("nothing.here"), Environment.LOCAL)
		assertEquals(InterventionClass.MANUAL, unknown.kind)
		assertEquals("nothing.here has no known control", unknown.reason)
	}

	@Test
	fun oneUncontrollableMemberMakesTheWholeUnitManual() {
		val control = Controls.standard.classify(
			listOf("kernel.release", "env.TZ"),
			Environment.LOCAL,
		)
		assertEquals(InterventionClass.MANUAL, control.kind)
		assertTrue(control.reason.startsWith("kernel.release cannot be set in local: "))
	}
	// #endregion

	// #region ddmin and the loop
	@Test
	fun theExperimentPoolIsWhatDdminWouldTestForEachPossibleCulprit() {
		val plan = plan(bundle(4))
		val flips = plan.experiments.map { it.experiment.flips }.toSet()
		val all = listOf("env.H0", "env.H1", "env.H2", "env.H3")
		val expected = setOf(
			all,
			listOf("env.H0", "env.H1"),
			listOf("env.H2", "env.H3"),
		) + all.map { listOf(it) }.toSet()
		assertEquals(expected, flips)
		assertEquals(7, plan.experiments.size)
		assertTrue(plan.experiments.all { it.experiment.kind == InterventionClass.AUTOMATIC_LOCAL })
	}

	@Test
	fun theFirstExperimentOnEightEquallyLikelyMembersSplitsTheBundleInHalf() {
		val plan = plan(bundle(8))
		val next = plan.next!!
		assertEquals(4, next.experiment.flips.size)
		assertEquals(Decision.NEXT, next.decision)
		val ranked = plan.experiments.filter { it.decision != Decision.REJECTED }
		assertEquals(ranked.sortedWith(Planned.RANK), ranked)
		assertTrue(ranked.all { it.perCost <= next.perCost })
		assertTrue(next.gain > 0)
		assertEquals(Status.READY, plan.status)
		assertEquals(emptyList(), plan.unseparated)
	}

	@Test
	fun followingThePlanFindsTheCulpritInAFewExperiments() {
		val hypotheses = bundle(8)
		for (culprit in hypotheses.map { it.id }) {
			var belief = prior(hypotheses)
			repeat(6) {
				val next = Planner.plan(hypotheses, belief, local, options).next!!
				assertTrue(next.gain > 0, culprit)
				val outcome = if (culprit in next.experiment.flips) {
					OutcomeTable.REPRODUCED
				} else {
					OutcomeTable.NOT_REPRODUCED
				}
				belief = next.branches.single { it.outcome == outcome }.posterior
			}
			val top = belief.masses.maxByOrNull { it.micro }!!
			assertEquals(culprit, top.id)
			assertTrue(top.micro > 900_000L, "$culprit ${top.micro}")
		}
	}

	@Test
	fun aCulpritOutsideTheListMovesTheMassToUnknown() {
		val hypotheses = bundle(4)
		var belief = prior(hypotheses)
		repeat(4) {
			val next = Planner.plan(hypotheses, belief, local, options).next!!
			belief = next.branches.single { it.outcome == OutcomeTable.NOT_REPRODUCED }.posterior
		}
		assertEquals(Belief.UNKNOWN, belief.masses.maxByOrNull { it.micro }!!.id)
		assertTrue(belief.mass(Belief.UNKNOWN) > 700_000L)
	}
	// #endregion

	// #region uncontrollable and stuck
	@Test
	fun anUncontrollableMemberIsManualAndNeverFlippedByAnAutomaticExperiment() {
		val hypotheses = bundle(3) + Hypothesis("kernel.release", listOf("kernel.release"))
		val plan = plan(hypotheses)
		val manual = plan.experiments.filter { it.decision == Decision.MANUAL }.single()
		assertEquals(listOf("kernel.release"), manual.experiment.flips)
		assertEquals(InterventionClass.MANUAL, manual.experiment.kind)
		assertEquals(
			"kernel.release cannot be set in local: the kernel belongs to the host",
			manual.reason,
		)
		assertTrue(manual.experiment.instructions.startsWith("Not automatic: "))
		val automatic = plan.experiments.filter { it.decision != Decision.MANUAL }
		assertTrue(automatic.none { "kernel.release" in it.experiment.flips })
		assertEquals(Status.READY, plan.status)
		assertEquals(listOf("kernel.release", Belief.UNKNOWN), plan.unseparated.single().ids)
		assertTrue(plan.unseparated.single().reason.contains("kernel.release cannot be set"))
	}

	@Test
	fun whenNothingCanBeSetTheOutcomeIsStuckBecauseTheDimensionIsUncontrollable() {
		val hypotheses = listOf(
			Hypothesis("kernel.release", listOf("kernel.release")),
			Hypothesis("cpu.model", listOf("cpu.model")),
		)
		val plan = plan(hypotheses)
		assertEquals(Status.STUCK, plan.status)
		assertNull(plan.next)
		val reason = plan.stuck.single()
		assertEquals(StuckCode.UNCONTROLLABLE, reason.code)
		assertTrue(reason.detail.startsWith("the discriminating dimension is uncontrollable: "))
		assertTrue(reason.detail.contains("cpu.model") && reason.detail.contains("kernel.release"))
		assertEquals(listOf(Decision.MANUAL, Decision.MANUAL), plan.experiments.map { it.decision })
		assertEquals(
			listOf("cpu.model", "kernel.release", Belief.UNKNOWN),
			plan.unseparated.single().ids,
		)
	}

	@Test
	fun noCandidateIsStuckWithItsOwnReason() {
		val plan = Planner.plan(emptyList(), Belief.of(emptyMap(), 0), local, options)
		assertEquals(Status.STUCK, plan.status)
		assertEquals(listOf(StuckCode.NO_HYPOTHESES), plan.stuck.map { it.code })
		assertEquals(emptyList(), plan.experiments)
	}

	@Test
	fun arrivingAtAlphaWithinTheBudgetIsNeededOrThePlanIsStuck() {
		val rare = options.copy(failures = 0, runs = 20, maxPerArm = 10)
		val plan = plan(bundle(3), opts = rare)
		assertEquals(Status.STUCK, plan.status)
		assertEquals(listOf(StuckCode.BUDGET), plan.stuck.map { it.code })
		assertNull(plan.trials.perArm)
		assertTrue(plan.experiments.all { it.decision == Decision.REJECTED })
		assertTrue(plan.experiments.all { it.reason.startsWith("10 trials per arm cannot reach") })
	}

	@Test
	fun anExperimentThatChangesNoBeliefIsRejectedAndTheOutcomeIsStuck() {
		val flat = Options(4, failures = 0, runs = 0, alpha = 500_000, power = 500_000)
		val plan = plan(bundle(2), opts = flat)
		assertEquals(Status.STUCK, plan.status)
		assertEquals(listOf(StuckCode.NO_INFORMATION), plan.stuck.map { it.code })
		assertTrue(plan.experiments.all { it.gain == 0L && it.decision == Decision.REJECTED })
		assertTrue(plan.experiments.all { it.reason.startsWith("no information") })
	}
	// #endregion

	// #region cost
	@Test
	fun costsFollowWhereAnArmRuns() {
		val automatic = plan(bundle(2))
		val total = 2L * automatic.trials.perArm!!
		assertEquals(Cost(total * 4, 0, total), automatic.next!!.experiment.cost)
		val ci = plan(
			listOf(Hypothesis("deps.a.version", listOf("deps.a.version"))),
			Frame.shas("1", "2"),
		)
		assertEquals(
			Cost(PlanWeights.QUEUE_MINUTES + 4, total * 4, total),
			ci.next!!.experiment.cost,
		)
		val manual = plan(listOf(Hypothesis("cpu.model", listOf("cpu.model"))))
		assertEquals(
			Cost(PlanWeights.MANUAL_MINUTES + total * 4, 0, total),
			manual.experiments.single().experiment.cost,
		)
		assertEquals(
			24L * 1_000_000 + 7L * 2_000_000 + 12L * 500_000,
			PlanWeights.points(Cost(24, 7, 12)),
		)
	}

	@Test
	fun costPerGainChangesTheRankAgainstRawGain() {
		fun planned(id: String, gain: Long, cost: Cost): Planned {
			val points = PlanWeights.points(cost)
			return stub(id, gain, points, gain * one / points, cost)
		}

		val strongButSlow = planned("a", 1_000_000, Cost(10, 0, 1))
		val weakButCheap = planned("b", 600_000, Cost(2, 0, 1))
		assertEquals(10_500_000L, strongButSlow.points)
		assertEquals(2_500_000L, weakButCheap.points)
		assertEquals(95_238L, strongButSlow.perCost)
		assertEquals(240_000L, weakButCheap.perCost)
		val both = listOf(weakButCheap, strongButSlow)
		assertEquals(listOf("a", "b"), both.sortedBy { -it.gain }.map { it.experiment.id })
		assertEquals(listOf("b", "a"), both.sortedWith(Planned.RANK).map { it.experiment.id })
	}

	@Test
	fun ranksBreakTiesByGainThenCostThenId() {
		val ranked = listOf(
			stub("d", 10, 10, 5),
			stub("c", 10, 10, 5),
			stub("b", 10, 5, 5),
			stub("a", 20, 20, 5),
			stub("z", 1, 1, 6),
		).sortedWith(Planned.RANK).map { it.experiment.id }
		assertEquals(listOf("z", "a", "b", "c", "d"), ranked)
	}
	// #endregion

	private fun stub(
		id: String,
		gain: Long,
		points: Long,
		perCost: Long,
		cost: Cost = Cost(1, 0, 1),
	): Planned {
		val table = OutcomeTable(listOf("a", "b"), emptyMap())
		val kind = InterventionClass.AUTOMATIC_LOCAL
		val e = Experiment(id, kind, emptyList(), emptyList(), cost, table, emptyList(), "")
		return Planned(e, gain, points, perCost, Decision.ALTERNATIVE, "", emptyList())
	}

	@Test
	fun theInputsTheCallerGetsWrongAreRejected() {
		assertFailsWith<IllegalArgumentException> { Options(0, failures = 1, runs = 2) }
		val twice = bundle(1) + bundle(1)
		assertFailsWith<IllegalArgumentException> { plan(twice) }
		val stranger = Hypothesis("env.elsewhere", listOf("env.elsewhere"))
		val belief = prior(bundle(2))
		assertFailsWith<IllegalArgumentException> {
			Planner.plan(listOf(stranger), belief, local, options)
		}
	}

	// #region frames and output
	@Test
	fun aTwoEnvironmentFrameLabelsTheArmsAndRunsLocally() {
		val plan = plan(bundle(2))
		assertEquals(FrameKind.ENVIRONMENT, plan.frame.kind)
		val e = plan.next!!.experiment
		val set = "local container with ${e.flips.joinToString(", ")} set to CI runner values"
		assertEquals(listOf("local container unchanged", set), e.arms.map { it.label })
		assertEquals(emptyList(), e.arms[0].flips)
		assertEquals(e.flips, e.arms[1].flips)
		assertTrue(e.instructions.startsWith("Build a local container from the local container"))
		assertTrue(e.instructions.contains("one-sided Fisher exact test at alpha 0.050000"))
		assertTrue(e.instructions.contains("Run each arm ${plan.trials.perArm} times."))
	}

	@Test
	fun aShaFrameRunsOnTheRunnerAndSaysSo() {
		val plan = plan(
			listOf(Hypothesis("deps.a.version", listOf("deps.a.version"))),
			Frame.shas("green1", "red1"),
		)
		val e = plan.next!!.experiment
		assertEquals(InterventionClass.AUTOMATIC_CI, e.kind)
		assertTrue(e.instructions.startsWith("Write a workflow variant of green1"))
		assertEquals(listOf("build"), e.dimensions)
	}

	@Test
	fun theFullDetailIsAlwaysInTheJson() {
		val plan = plan(bundle(3) + Hypothesis("cpu.model", listOf("cpu.model")))
		val json = CanonicalJson.parse(plan.json()).obj()
		assertEquals(plan.json(), CanonicalJson.encode(json))
		val keys = listOf("weights", "frame", "options", "trials", "hypotheses", "prior", "status")
		for (key in keys) assertTrue(json[key] != null, key)
		val experiments = json.require("experiments").array()
		assertEquals(plan.experiments.size, experiments.size)
		for (e in experiments) {
			val outcomes = e.obj().require("outcomes").array()
			assertEquals(2, outcomes.size)
			for (o in outcomes) {
				val posterior = o.obj().require("posterior").obj().require("masses").array()
				assertEquals(one, posterior.sumOf { it.obj().require("micro").long() })
			}
			assertTrue(e.obj().require("experiment").obj()["instructions"] != null)
		}
		assertTrue(plan.json().contains("\"calibrated\":false"))
	}

	@Test
	fun theSyntheticPlanBytesAreIdenticalOnEveryTarget() {
		val a = plan(bundle(4)).json()
		assertEquals(a, plan(bundle(4)).json())
		assertEquals(a, plan(bundle(4).shuffled(Rng(3))).json())
		assertEquals(SYNTHETIC_SHA, Sha256.hex(a))
	}
	// #endregion

	// #region from a ranking
	@Test
	fun aPairRankingBecomesHypothesesWithTheirControls() {
		fun capsule(vararg attrs: Pair<String, String>) =
			Capsule("c", attrs.map { Attribute(it.first, it.second, "test") })

		val ranking = Ranker.rank(
			capsule("env.TZ" to "UTC", "kernel.release" to "6.1", "tool.go.version" to "1.21"),
			capsule("env.TZ" to "CET", "kernel.release" to "6.8", "tool.go.version" to "1.24"),
			emptyList(),
		)
		val plan = Planner.from(ranking, local, options)
		assertEquals(
			listOf("env.TZ", "kernel.release", "tool.go.version"),
			plan.hypotheses.map { it.id },
		)
		assertEquals(Status.READY, plan.status)
		val manual = plan.experiments.filter { it.decision == Decision.MANUAL }
		assertEquals(listOf("kernel.release"), manual.map { it.experiment.flips.single() })
		assertEquals(Belief.UNKNOWN, plan.prior.masses.last().id)
		assertEquals(PlanWeights.UNKNOWN_PRIOR, plan.prior.mass(Belief.UNKNOWN))
		val automatic = plan.experiments.filter { it.decision != Decision.MANUAL }
		assertTrue(automatic.all { it.experiment.kind == InterventionClass.AUTOMATIC_LOCAL })
	}

	@Test
	fun aRankingWithNothingChangedIsStuckWithNoHypotheses() {
		val c = Capsule("c", listOf(Attribute("env.TZ", "UTC", "test")))
		val plan = Planner.from(Ranker.rank(c, c, emptyList()), local, options)
		assertEquals(listOf(StuckCode.NO_HYPOTHESES), plan.stuck.map { it.code })
	}
	// #endregion

	// #region drangler
	private val key = HistoryKey("e2e", FixtureRuns.JOB)
	private val everyRun = (FixtureRuns.reds + FixtureRuns.greens).map { FixtureRuns.run(it) }
	private val history = History.of(key, everyRun.shuffled(Rng(5)), RunOrder.RUN_ID)
	private val step = "step:${FixtureRuns.JOB}/Set up Node"
	private val provisioner = mapOf("ci.provisioner.build-date" to "ci.provisioner.version")

	private fun dranglerOptions(couplings: Map<String, String> = Options.COUPLINGS): Options {
		val ms = history.transition!!.red.steps.filter { it.job == FixtureRuns.JOB }
			.sumOf { it.durationMs ?: 0L }
		return Options(
			trialMinutes = (ms + 59_999) / 60_000,
			failures = 3,
			runs = 3,
			couplings = couplings,
		)
	}

	private fun dranglerPlan(
		transition: Transition = history.transition!!,
		runs: List<dev.gmitch215.drift.model.Run> = history.runs,
		options: Options = dranglerOptions(),
		red: dev.gmitch215.drift.model.Run? = transition.red,
	): Plan {
		val ranking = Ranker.rank(transition, runs, FixtureRuns.JOB)
		val frame = Frame.shas(transition.green.id, transition.red.id)
		return Planner.from(ranking, frame, options, red)
	}

	@Test
	fun theDranglerBundlePlansFourControllableAndTwoManualHypotheses() {
		val plan = dranglerPlan()
		assertEquals(4L, plan.options.trialMinutes)
		assertEquals(
			listOf(
				"ci.provisioner.version",
				"ci.runner.image.version",
				"deps.@napi-rs/keyring.version",
				"deps.prettier-plugin-sh.version",
				"deps.wrangler.version",
				step,
			),
			plan.hypotheses.map { it.id },
		)
		assertEquals(
			listOf("ci.provisioner.build-date", "ci.provisioner.version"),
			plan.hypotheses.first().members,
		)
		assertEquals(
			listOf(
				"step:${FixtureRuns.JOB}/Post Set up Node",
				step,
				"tool.node.version",
				"tool.npm.version",
			),
			plan.hypotheses.single { it.id == step }.members,
		)
		assertEquals(
			listOf("step:${FixtureRuns.JOB}/Dump Host State on Failure"),
			plan.excluded.map { it.id },
		)
		assertEquals(
			"ran after the failing step Run Integration Suite with Coverage, " +
				"so it cannot have caused it",
			plan.excluded.single().reason,
		)
		val manual = plan.experiments.filter { it.decision == Decision.MANUAL }
		assertEquals(
			listOf("ci.provisioner.version", "ci.runner.image.version"),
			manual.map { it.experiment.flips.single() }.sorted(),
		)
		assertEquals(Status.READY, plan.status)
	}

	@Test
	fun theDranglerPlanStartsWithTheControllableBundleAndLeavesTheRunnerUnseparated() {
		val plan = dranglerPlan()
		assertEquals(6, plan.trials.perArm)
		assertEquals(4, plan.trials.threshold)
		assertEquals(901_120L, plan.trials.power)
		assertEquals(800_000L, plan.trials.rate)
		val next = plan.next!!
		assertEquals(
			listOf(
				"deps.@napi-rs/keyring.version",
				"deps.prettier-plugin-sh.version",
				"deps.wrangler.version",
				step,
			),
			next.experiment.flips,
		)
		assertEquals(InterventionClass.AUTOMATIC_CI, next.experiment.kind)
		assertEquals(Cost(9, 48, 12), next.experiment.cost)
		assertEquals(111_000_000L, next.points)
		assertEquals(
			listOf("ci.provisioner.version", "ci.runner.image.version", Belief.UNKNOWN),
			plan.unseparated.single().ids,
		)
		assertEquals(7, plan.experiments.count { it.decision != Decision.MANUAL })
		assertTrue(plan.experiments.filter { it.decision != Decision.MANUAL }.all { it.gain > 0 })
	}

	@Test
	fun withoutTheRedRunNoStepIsExcludedAndTheDefaultCouplingsMergeTheNodeChanges() {
		val open = dranglerPlan(red = null)
		assertTrue(open.hypotheses.any { it.id.endsWith("Dump Host State on Failure") })
		assertEquals(emptyList(), open.excluded)
		val unmerged = dranglerPlan(options = dranglerOptions(provisioner))
		assertEquals(8, unmerged.hypotheses.size)
		val merged = dranglerPlan()
		assertEquals(6, merged.hypotheses.size)
		val members = listOf(
			"step:${FixtureRuns.JOB}/Post Set up Node",
			step,
			"tool.node.version",
			"tool.npm.version",
		)
		assertEquals(members, merged.hypotheses.single { it.id == step }.members)
	}

	@Test
	fun aCouplingToAJoblessStepNamesTheFirstAddedStepAndIsIgnoredWithoutOne() {
		val node = mapOf("tool.node.version" to "step:Set up Node")
		assertEquals(Options.COUPLINGS.getValue("tool.npm.version"), "step:Set up Node")
		val bare = dranglerPlan(options = dranglerOptions(node))
		assertTrue("tool.node.version" in bare.hypotheses.single { it.id == step }.members)
		val nope = mapOf("tool.node.version" to "step:Nope")
		val gone = dranglerPlan(options = dranglerOptions(nope))
		assertTrue(gone.hypotheses.any { it.id == "tool.node.version" })
	}

	@Test
	fun theDranglerPlanDoesNotDependOnRunAttributeOrCandidateOrder() {
		val expected = dranglerPlan().json()
		val green = history.transition!!.green.id
		val red = history.transition!!.red.id
		for (seed in 1L..4L) {
			val runs = everyRun.shuffled(Rng(seed)).map { run ->
				val c = run.capsule!!
				run.copy(capsule = c.copy(attributes = c.attributes.shuffled(Rng(seed))))
			}
			val byId = runs.associateBy { it.id }
			val transition = Transition(byId.getValue(green), byId.getValue(red))
			assertEquals(expected, dranglerPlan(transition, runs).json(), "$seed")
		}
	}

	@Test
	fun theDranglerPlanBytesAreIdenticalOnEveryTarget() {
		val json = dranglerPlan().json()
		assertEquals(json, dranglerPlan().json())
		assertEquals(DRANGLER_SHA, Sha256.hex(json))
	}
	// #endregion

	private companion object {
		const val SYNTHETIC_SHA = "7db569269df3b0dfa8087d68ae6dc5fa3b115f6774541d01c60fd0e68dcb19a8"
		const val DRANGLER_SHA = "54a0d6d4859f5a569dc61fc2bbb055dbc7955be4a0253e467b8e8123a16983b1"
	}
}
