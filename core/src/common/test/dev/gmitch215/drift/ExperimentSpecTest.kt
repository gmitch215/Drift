package dev.gmitch215.drift

import dev.gmitch215.drift.fixtures.LabFixtures
import dev.gmitch215.drift.fixtures.RunFixtures
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.lab.ArmSet
import dev.gmitch215.drift.lab.Arms
import dev.gmitch215.drift.lab.Budget
import dev.gmitch215.drift.lab.BundleVerdict
import dev.gmitch215.drift.lab.Counts
import dev.gmitch215.drift.lab.DecisionRule
import dev.gmitch215.drift.lab.ExperimentSpec
import dev.gmitch215.drift.lab.Outcome
import dev.gmitch215.drift.lab.Shortage
import dev.gmitch215.drift.lab.Sizing
import dev.gmitch215.drift.lab.TrialSizing
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import dev.gmitch215.drift.plan.Belief
import dev.gmitch215.drift.plan.Decision
import dev.gmitch215.drift.plan.Environment
import dev.gmitch215.drift.plan.Experiment
import dev.gmitch215.drift.plan.Fisher
import dev.gmitch215.drift.plan.Frame
import dev.gmitch215.drift.plan.Hypothesis
import dev.gmitch215.drift.plan.InterventionClass
import dev.gmitch215.drift.plan.Options
import dev.gmitch215.drift.plan.Plan
import dev.gmitch215.drift.plan.Planner
import dev.gmitch215.drift.plan.Trials
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExperimentSpecTest {
	private val green = Capsule.parse(RunFixtures.text("capsules/36702683742.json"))
	private val red = Capsule.parse(RunFixtures.text("capsules/36995781138.json"))
	private val node = Capsule.parse(LabFixtures.text("node.json"))
	private val budget = Budget(40, 240)

	private fun sufficient(s: Sizing) = assertIs<Sizing.Sufficient>(s)

	private fun insufficient(s: Sizing) = assertIs<Sizing.Insufficient>(s)

	// #region trial sizing

	@Test
	fun theTrialCountForTheDranglerRateIsTheCountThePlannerChose() {
		val plan = DranglerCase.plan
		val sizing = sufficient(
			TrialSizing.fromRate(plan.options.rate, budget, plan.options.trialMinutes),
		)
		assertEquals(plan.trials, sizing.plan)
		assertEquals(6, sizing.plan.perArm)
		assertEquals(12, sizing.trials)
		assertEquals(48, sizing.minutes)
	}

	@Test
	fun theRateComesFromTheCompletedRunsAndSkipsTheRest() {
		val runs = (FixtureRuns.reds + FixtureRuns.greens).map { FixtureRuns.run(it) }
		assertEquals(3 to 8, TrialSizing.counts(runs))
		val noise = listOf(Run("c", RunOutcome.CANCELLED), Run("u", RunOutcome.UNKNOWN))
		assertEquals(3 to 8, TrialSizing.counts(runs + noise))
		assertEquals(
			TrialSizing.fromRate(Trials.rate(3, 8), budget, 4),
			TrialSizing.fromRuns(runs + noise, budget, 4),
		)
		assertEquals(400_000, sufficient(TrialSizing.fromRuns(runs, budget, 4)).plan.rate)
	}

	@Test
	fun withoutACompletedRunTheRateIsUnknownAndNothingIsGuessed() {
		for (runs in listOf(emptyList(), listOf(Run("c", RunOutcome.CANCELLED)))) {
			val s = insufficient(TrialSizing.fromRuns(runs, budget, 4))
			assertEquals(Shortage.NO_RATE, s.reason)
			assertNull(s.neededPerArm)
			assertNull(s.shortfallTrials)
			assertEquals("no completed run to take a failure rate from", s.detail)
		}
	}

	@Test
	fun aBudgetThatCannotReachSignificanceReportsTheNeededTrialsAndTheShortfall() {
		val s = insufficient(TrialSizing.fromRate(500_000, Budget(10, 100), 5))
		assertEquals(Shortage.BUDGET, s.reason)
		assertEquals(5, s.allowedPerArm)
		assertEquals(10, s.neededPerArm)
		assertEquals(10, s.shortfallTrials)
		assertEquals(100, s.neededMinutes)
		assertEquals(0, s.shortfallMinutes)
		assertEquals(
			"the budget allows 5 trials per arm; power 0.800000 at alpha 0.050000 and failure " +
				"rate 0.500000 needs 10 per arm (20 trials, 100 minutes): short by 10 trials " +
				"and 0 minutes",
			s.detail,
		)
	}

	@Test
	fun minutesBindWhenTheyAreScarcerThanTrials() {
		val s = insufficient(TrialSizing.fromRate(500_000, Budget(60, 20), 2))
		assertEquals(5, s.allowedPerArm)
		assertEquals(10, s.neededPerArm)
		assertEquals(40, s.neededMinutes)
		assertEquals(20, s.shortfallMinutes)
		val slow = insufficient(TrialSizing.fromRate(300_000, Budget(10, 100), 5))
		assertEquals(21, slow.neededPerArm)
		assertEquals(32, slow.shortfallTrials)
		assertEquals(210, slow.neededMinutes)
		assertEquals(110, slow.shortfallMinutes)
		assertEquals(6, sufficient(TrialSizing.fromRate(800_000, Budget(60, 24), 2)).plan.perArm)
		assertEquals(
			5,
			insufficient(TrialSizing.fromRate(800_000, Budget(11, 240), 1)).allowedPerArm,
		)
	}

	@Test
	fun aRateNoCountReachesIsUnreachableAndAZeroBudgetNeedsTheFullCount() {
		val low = insufficient(TrialSizing.fromRate(100_000, Budget(100, 1000), 1))
		assertEquals(Shortage.UNREACHABLE, low.reason)
		assertEquals(30, low.allowedPerArm)
		assertNull(low.neededPerArm)
		assertTrue("no count up to 30 trials per arm" in low.detail)
		val none = insufficient(TrialSizing.fromRate(800_000, Budget(0, 0), 4))
		assertEquals(0, none.allowedPerArm)
		assertEquals(6, none.neededPerArm)
		assertEquals(12, none.shortfallTrials)
		assertEquals(48, none.shortfallMinutes)
		assertFailsWith<IllegalArgumentException> { TrialSizing.fromRate(500_000, budget, 0) }
		assertFailsWith<IllegalArgumentException> { Budget(-1, 5) }
	}

	@Test
	fun sizingSerializesBothWays() {
		val ok = TrialSizing.fromRate(800_000, budget, 4).toJson()
		assertEquals("sufficient", (ok["status"] as JsonString).value)
		val no = TrialSizing.fromRate(100_000, budget, 4).toJson()
		assertEquals("insufficient", (no["status"] as JsonString).value)
		assertEquals(JsonNull, no["neededPerArm"])
		assertEquals("unreachable", (no["reason"] as JsonString).value)
	}

	// #endregion

	// #region decision rule

	private val rule = DecisionRule(6, 4, 50_000, 800_000)

	@Test
	fun theRuleDecidesSupportedRefutedOrInconclusiveFromTheCounts() {
		val table = listOf(
			Triple(Counts(6, 6), Counts(0, 6), Outcome.SUPPORTED),
			Triple(Counts(4, 6), Counts(0, 6), Outcome.SUPPORTED),
			Triple(Counts(3, 6), Counts(0, 6), Outcome.INCONCLUSIVE),
			Triple(Counts(0, 6), Counts(0, 6), Outcome.REFUTED),
			Triple(Counts(1, 6), Counts(0, 6), Outcome.REFUTED),
			Triple(Counts(2, 6), Counts(2, 6), Outcome.REFUTED),
			Triple(Counts(6, 6), Counts(5, 6), Outcome.INCONCLUSIVE),
		)
		for ((t, c, expected) in table) assertEquals(expected, rule.evaluate(t, c), "$t $c")
	}

	@Test
	fun theRuleAgreesWithTheExactTestAndNeverSupportsAnArmThatFailsNoMoreThanTheControl() {
		for (t in 0..6) {
			for (c in 0..6) {
				val outcome = rule.evaluate(Counts(t, 6), Counts(c, 6))
				val p = Fisher.oneSided(t, 6 - t, c, 6 - c)
				assertEquals(p.atMost(50_000), outcome == Outcome.SUPPORTED, "$t $c")
				if (t <= c) assertTrue(outcome != Outcome.SUPPORTED, "$t $c")
			}
		}
	}

	@Test
	fun theRuleRefusesCountsOutsideWhatTheExactTestCanHold() {
		assertFailsWith<IllegalArgumentException> { Counts(7, 6) }
		assertFailsWith<IllegalArgumentException> { Counts(0, 0) }
		assertFailsWith<IllegalArgumentException> {
			rule.evaluate(Counts(1, 31), Counts(0, 30))
		}
		val plan = sufficient(TrialSizing.fromRate(800_000, budget, 4))
		assertEquals(rule, DecisionRule.of(plan))
	}

	@Test
	fun thePreregisteredRuleHasAGoldenHashOnEveryTarget() {
		assertEquals(
			"15fdd89e33b93c5e3f4031351604c3e69721accddb72ac8ffc2d3cf5ffcdcc00",
			rule.sha256(),
		)
		assertEquals(Sha256.hex(rule.json()), rule.sha256())
		assertEquals(rule.json(), CanonicalJson.encode(CanonicalJson.parse(rule.json())))
		val json = rule.toJson()
		assertEquals(
			"the exact one-sided p-value is at most 0.050000",
			(json["supported"] as JsonString).value,
		)
		assertTrue("0.800000" in (json["refuted"] as JsonString).value)
		assertEquals("any other result", (json["inconclusive"] as JsonString).value)
	}

	// #endregion

	// #region the drangler SHA frame

	private val plan: Plan = DranglerCase.plan

	private val sizing = TrialSizing.fromRate(plan.options.rate, budget, plan.options.trialMinutes)

	private fun arms(e: Experiment): ArmSet? = Arms.fromExperiment(
		e,
		plan.hypotheses,
		green,
		red,
		"npm test",
		Environment.CI,
	)

	private val specs = plan.experiments.map { it.experiment }.map {
		it to ExperimentSpec.from(it, plan.frame, arms(it), sizing, plan.hypotheses)
	}

	@Test
	fun everyAutomaticDranglerExperimentBuildsAnExecutableSpecAndEveryManualOneDoesNot() {
		val automatic = specs.filter { it.first.kind != InterventionClass.MANUAL }
		val manual = specs.filter { it.first.kind == InterventionClass.MANUAL }
		assertTrue(automatic.isNotEmpty() && manual.isNotEmpty())
		for ((e, spec) in automatic) {
			assertTrue(spec.executable, e.id)
			assertTrue(spec.blockers.isEmpty(), e.id)
			assertEquals(InterventionClass.AUTOMATIC_CI, spec.kind, e.id)
			assertEquals(listOf("control", "treatment"), spec.arms.map { it.role })
			assertTrue(spec.arms[0].interventions.isEmpty(), e.id)
			assertTrue(spec.arms[1].interventions.isNotEmpty(), e.id)
			assertNotNull(spec.arms[1].capsuleSha256)
			assertNull(spec.arms[1].dockerfileSha256)
			val types = spec.arms[1].interventions.map { it.type }.toSet()
			val allowed = setOf("pin-dependency", "toggle-step", "set-runtime")
			assertTrue(types.all { it in allowed }, e.id)
		}
		for ((e, spec) in manual) {
			assertFalse(spec.executable, e.id)
			assertEquals(InterventionClass.MANUAL, spec.kind)
			assertTrue(spec.blockers.isNotEmpty())
			assertTrue(spec.blockers.all { it.member.startsWith("ci.") }, e.id)
			assertTrue(spec.blockers.all { "runner provider" in it.reason }, e.id)
			assertTrue(spec.arms.all { it.capsuleSha256 == null && it.interventions.isEmpty() })
			assertTrue(spec.instructions.startsWith("Not automatic:"), e.id)
			assertNull(spec.verdict)
			assertNull(spec.diff)
		}
		assertEquals(plan.experiments.count { it.decision == Decision.MANUAL }, manual.size)
	}

	@Test
	fun theRuleIsTheSameForEverySpecOfOnePlanAndIsWrittenBeforeAnyRun() {
		val hashes = specs.map { it.second.rule!!.sha256() }.toSet()
		assertEquals(1, hashes.size)
		for ((_, spec) in specs) {
			val json = CanonicalJson.parse(spec.json()) as JsonObject
			assertEquals(hashes.single(), (json["ruleSha256"] as JsonString).value)
			assertTrue(json.fields.keys.none { it in setOf("result", "results", "outcome") })
			assertEquals(spec.json(), CanonicalJson.encode(json))
		}
	}

	@Test
	fun theBiggestDranglerBundleIsABundleOfEverythingItSetsAndNothingIsIsolated() {
		val (_, spec) = specs.filter { it.second.executable }.maxBy { it.first.flips.size }
		val verdict = assertIs<BundleVerdict.Bundle>(spec.verdict)
		assertEquals(verdict.members, verdict.notIsolated)
		assertEquals(verdict.members, spec.diff!!.changed)
		assertTrue(spec.diff.cochanging.isEmpty())
		assertTrue(verdict.members.size >= 6)
		assertNull(verdict.mechanism)
		assertEquals(
			"818b226700c6dc27bef77827d00fda10a65e812e43f80d7d0902758d2a64fa50",
			spec.sha256(),
		)
	}

	@Test
	fun aSingleDranglerDependencyIsIsolatedAndTakesAMechanismOnlyWhenARuleAgrees() {
		val e = plan.experiments.map { it.experiment }.first {
			it.flips == listOf("deps.wrangler.version") && it.kind != InterventionClass.MANUAL
		}
		val set = arms(e)!!
		val verdict = assertIs<BundleVerdict.Isolated>(set.verdict)
		assertEquals("deps.wrangler.version", verdict.path)
		assertNull(verdict.mechanism)
		val explained = Arms.fromExperiment(
			e,
			plan.hypotheses,
			green,
			red,
			"npm test",
			Environment.CI,
			mechanisms = mapOf("deps.wrangler.version" to "a release changed the bundler"),
		)!!
		assertEquals(
			"a release changed the bundler",
			assertIs<BundleVerdict.Isolated>(explained.verdict).mechanism,
		)
		assertEquals("pin-dependency", set.right.interventions.single().type)
		assertEquals(
			"4.146.0",
			set.right.capsule.attributes.single {
			it.path == verdict.path
		}.value,
		)
		val before = set.left.capsule.attributes.single { it.path == verdict.path }
		assertEquals("4.123.0", before.value)
	}

	@Test
	fun theSetUpNodeHypothesisIsOneChangeThatTheRuleStillCallsABundle() {
		val e = plan.experiments.map { it.experiment }.first {
			it.flips.any { f -> f.startsWith("step:") } && it.kind != InterventionClass.MANUAL
		}
		val set = arms(e)!!
		val verdict = assertIs<BundleVerdict.Bundle>(set.verdict)
		assertTrue(verdict.members.any { it.startsWith("step:") })
		assertTrue("tool.node.version" in verdict.members || e.flips.size > 1)
		assertEquals(set.primaries, verdict.members.filter { it in set.primaries })
	}

	// #endregion

	// #region the local-versus-CI frame

	private val localFrame = Frame.environments("lab-node", "drangler-red")

	private val localPlan: Plan = run {
		val before = node.attributes.associate { it.path to it.value }
		val after = red.attributes.associate { it.path to it.value }
		val ids = (before.keys + after.keys).sorted()
			.filter { before[it] != after[it] && it != "env.HOME" && it != "ci.runner.region" }
		val hypotheses = ids.map { Hypothesis(it, listOf(it)) }
		val prior = Belief.of(hypotheses.associate { it.id to 1L }, 250_000)
		Planner.plan(
			hypotheses,
			prior,
			localFrame,
			Options(trialMinutes = 1, failures = 3, runs = 4),
		)
	}

	private val localSizing =
		TrialSizing.fromRate(
			localPlan.options.rate,
			Budget(40, 240),
			localPlan.options.trialMinutes,
		)

	private val localSpecs = localPlan.experiments.map { it.experiment }.map {
		val set = Arms.fromExperiment(
			it,
			localPlan.hypotheses,
			node,
			red,
			"npm test",
			Environment.LOCAL,
		)
		it to ExperimentSpec.from(it, localFrame, set, localSizing, localPlan.hypotheses)
	}

	private fun single(path: String): ExperimentSpec =
		localSpecs.first { it.first.flips == listOf(path) }.second

	@Test
	fun theLocalFrameBuildsContainerArmsWithFilesAndHashes() {
		val spec = single("env.NODE_ENV")
		assertTrue(spec.executable)
		assertEquals(listOf("control", "treatment"), spec.arms.map { it.role })
		assertEquals("npm test", spec.arms[1].command)
		for (arm in spec.arms) {
			assertNotNull(arm.dockerfileSha256)
			assertNotNull(arm.runScriptSha256)
			assertNotNull(arm.manifestSha256)
		}
		assertTrue(spec.arms[0].dockerfileSha256 != spec.arms[1].dockerfileSha256)
		assertEquals("env.NODE_ENV", assertIs<BundleVerdict.Isolated>(spec.verdict).path)
		assertEquals(listOf("unset-env"), spec.arms[1].interventions.map { it.type })
	}

	@Test
	fun aLocalImageSwapIsABundleWithTheKnownCoChangingMembers() {
		val spec = single("tool.node.version")
		assertTrue(spec.executable)
		val verdict = assertIs<BundleVerdict.Bundle>(spec.verdict)
		assertTrue("tool.libc.version" in verdict.notIsolated)
		assertTrue("component:tzdata" in verdict.notIsolated)
		assertTrue("tool.node.version" !in verdict.notIsolated)
		assertEquals(
			"89ac7ee0fa6371a4f44202f344a4866c0dc7e6d72f5c172054bf903f1ad084a7",
			spec.sha256(),
		)
	}

	@Test
	fun whatAContainerCannotSetBecomesABlockerWithTheReasonAndNeverAnArm() {
		val reasons = mapOf(
			"env.API_TOKEN" to "redacted",
			"cgroup.cpu.max" to "nothing to set",
			"limits.nofile" to "nothing to set",
			"os.release.VERSION_ID" to "the image is debian",
			"tool.python.version" to "second toolchain",
			"tool.bun.version" to "no official image is mapped for bun",
			"tool.libc.version" to "only an image swap changes it",
		)
		for ((path, why) in reasons) {
			val spec = single(path)
			assertFalse(spec.executable, path)
			assertEquals(listOf(path), spec.blockers.map { it.member }, path)
			val reason = spec.blockers.single().reason
			assertTrue(why in reason, "$path: $reason")
			assertTrue(spec.arms[1].interventions.isEmpty(), path)
		}
	}

	@Test
	fun theHostedRunnerAndHostAttributesAreManualExperimentsWithInstructions() {
		val manual = localSpecs.filter { it.first.kind == InterventionClass.MANUAL }
		assertEquals(
			setOf(
				"ci.provisioner.build-date", "ci.provisioner.name", "ci.provisioner.version",
				"ci.runner.image", "ci.runner.image.version", "ci.runner.version", "cpu.count",
				"kernel.release", "os.arch", "os.name", "os.version",
			),
			manual.flatMap { it.first.flips }.toSet(),
		)
		for ((e, spec) in manual) {
			assertFalse(spec.executable, e.id)
			assertTrue(spec.instructions.startsWith("Not automatic:"), e.id)
			assertEquals(1, spec.blockers.size)
			assertNull(spec.arms[1].capsuleSha256)
		}
		val hosted = single("ci.runner.image.version")
		assertTrue("runner provider" in hosted.blockers.single().reason)
	}

	@Test
	fun aPlannedExperimentWithMissingMembersIsNotExecutableEvenWhenThePlannerCalledItAutomatic() {
		val manual = localSpecs.count { it.first.kind == InterventionClass.MANUAL }
		val whole = localSpecs.first {
			it.first.flips.size == localPlan.hypotheses.size - manual
		}
		assertEquals(InterventionClass.AUTOMATIC_LOCAL, whole.first.kind)
		assertFalse(whole.second.executable)
		assertTrue(whole.second.blockers.size >= 10)
		assertEquals(11, manual)
		assertTrue(whole.second.arms[1].interventions.size >= 15)
		assertIs<BundleVerdict.Bundle>(whole.second.verdict)
	}

	// #endregion

	// #region the spec itself

	@Test
	fun aBudgetThatCannotReachSignificanceMakesTheSpecNonExecutableWithNoRule() {
		val e = plan.experiments.first { it.decision == Decision.NEXT }.experiment
		val tight = TrialSizing.fromRate(500_000, Budget(10, 100), 5)
		val spec = ExperimentSpec.from(e, plan.frame, arms(e), tight, plan.hypotheses)
		assertFalse(spec.executable)
		assertNull(spec.rule)
		val json = CanonicalJson.parse(spec.json()) as JsonObject
		assertEquals(JsonNull, json["ruleSha256"])
		assertEquals(JsonNull, json["rule"])
		assertEquals(
			"insufficient",
			((json["trials"] as JsonObject)["status"] as JsonString).value,
		)
		assertTrue("Trials: insufficient." in spec.text())
	}

	@Test
	fun theSpecBytesDoNotDependOnAttributeOrder() {
		val e = plan.experiments.first { it.decision == Decision.NEXT }.experiment
		val expected = ExperimentSpec.from(e, plan.frame, arms(e), sizing, plan.hypotheses).json()
		for (seed in 1..5) {
			val random = Random(seed)
			val shuffledGreen = green.copy(attributes = green.attributes.shuffled(random))
			val shuffledRed = red.copy(attributes = red.attributes.shuffled(random))
			val set = Arms.fromExperiment(
				e,
				plan.hypotheses,
				shuffledGreen,
				shuffledRed,
				"npm test",
				Environment.CI,
			)
			assertEquals(
				expected,
				ExperimentSpec.from(e, plan.frame, set, sizing, plan.hypotheses).json(),
				"seed $seed",
			)
		}
	}

	@Test
	fun theTextViewIsPlainAsciiWithinOneHundredColumns() {
		for ((e, spec) in specs + localSpecs) {
			val text = spec.text()
			for (line in text.trimEnd().split('\n')) {
				assertTrue(line.length <= 100, "${e.id}: ${line.length}")
				assertTrue(line.all { it.code in 32..126 }, "${e.id}: $line")
			}
			assertTrue(text.startsWith("Experiment ${e.id}, class ${spec.kind.id}"))
			val flat = text.split(Regex("\\s+")).joinToString(" ")
			assertTrue("sha256 ${spec.rule!!.sha256()})" in flat, e.id)
		}
	}

	@Test
	fun theSpecJsonNamesEveryPieceTheLoopNeeds() {
		val spec = single("env.TZ")
		val json = CanonicalJson.parse(spec.json()) as JsonObject
		assertEquals(
			setOf(
				"schema", "id", "frame", "class", "executable", "pairing", "arms", "diff",
				"verdict", "blockers", "trials", "rule", "ruleSha256", "instructions",
			),
			json.fields.keys,
		)
		val arms = json.require("arms").array()
		assertEquals(2, arms.size)
		assertEquals("control-treatment", json.require("pairing").string())
		val treatment = arms[1] as JsonObject
		assertEquals(
			"unset-env:name=TZ",
			(
				(
					treatment.require(
				"interventions",
			) as JsonArray
				).items[0] as JsonObject
			)
				.require("id").string(),
		)
		assertEquals(spec.sha256(), Sha256.hex(spec.json()))
	}

	// #endregion
}
