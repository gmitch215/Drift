package dev.gmitch215.drift

import dev.gmitch215.drift.diff.CapsuleDiff
import dev.gmitch215.drift.diff.DiffKind
import dev.gmitch215.drift.diff.Order
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.history.ExclusionReason
import dev.gmitch215.drift.history.History
import dev.gmitch215.drift.history.HistoryKey
import dev.gmitch215.drift.history.RunOrder
import dev.gmitch215.drift.history.Transition
import dev.gmitch215.drift.history.TransitionKind
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.know.Basis
import dev.gmitch215.drift.know.Rules
import dev.gmitch215.drift.math.FixedPoint
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import dev.gmitch215.drift.model.Step
import dev.gmitch215.drift.model.StepConclusion
import dev.gmitch215.drift.rank.EvidenceKind
import dev.gmitch215.drift.rank.Ranker
import dev.gmitch215.drift.rank.Spectrum
import dev.gmitch215.drift.rank.Tier
import dev.gmitch215.drift.rank.Weights
import dev.gmitch215.drift.symptom.Symptom
import dev.gmitch215.drift.symptom.SymptomKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RankHistoryTest {
	private val job = "ci"

	private fun capsule(vararg attrs: Pair<String, String>) =
		Capsule("c", attrs.map { Attribute(it.first, it.second, "test") })

	private val ran = listOf(
		Step(job, "build", StepConclusion.SUCCESS, null),
		Step(job, "test", StepConclusion.SUCCESS, null),
	)

	private val failed = listOf(
		Step(job, "build", StepConclusion.SUCCESS, null),
		Step(job, "test", StepConclusion.FAILURE, null),
	)

	private val vacuous = listOf(Step(job, "build", StepConclusion.SUCCESS, null))

	private fun green(id: String, vararg attrs: Pair<String, String>, steps: List<Step> = ran) =
		Run(id, RunOutcome.PASS, steps, capsule = capsule(*attrs))

	private fun red(id: String, vararg attrs: Pair<String, String>, steps: List<Step> = failed) =
		Run(id, RunOutcome.FAIL, steps, capsule = capsule(*attrs))

	private val ok = arrayOf("tool.cc.version" to "13", "env.HOME" to "/x")

	private fun greens(n: Int) = List(n) { green("g${it + 1}", "tool.go.version" to "1.21", *ok) }

	private val bad = red("r1", "tool.go.version" to "1.24", *ok)

	private fun rank(history: List<Run>, last: Run = history.last()) =
		Ranker.rank(Transition(last, bad), history + bad, job, emptyList())

	@Test
	fun spectrumOverEnoughGreensMakesTheChangeCorrelated() {
		val ranking = rank(greens(3))
		val c = ranking.candidates.single()
		assertEquals("tool.go.version", c.path)
		assertEquals(Tier.CORRELATED, c.tier)
		assertEquals(1, c.spectrum?.ef)
		assertEquals(0, c.spectrum?.ep)
		assertEquals(FixedPoint.ONE, c.spectrum?.score)
		assertEquals(FixedPoint.ONE, c.coverage)
		val ratio = Weights.spectrumRatio(FixedPoint.ONE, FixedPoint.ONE)
		assertEquals(Weights.spectrum.ratio, ratio)
		assertEquals(
			listOf(EvidenceKind.ATTRIBUTE, EvidenceKind.SPECTRUM),
			c.evidence.map { it.kind },
		)
		assertEquals(FixedPoint.ln(ratio), c.evidence[1].weight)
		assertEquals(Basis.INFERRED, c.basis)
		assertEquals(TransitionKind.SINGLE, ranking.transition?.kind)
	}

	@Test
	fun oneGreenAndOneRedSayNothingAboutCorrelation() {
		val c = rank(greens(1)).candidates.single()
		assertEquals(Tier.DIFFERENCE, c.tier)
		assertNull(c.spectrum)
		assertEquals(listOf(EvidenceKind.ATTRIBUTE), c.evidence.map { it.kind })
		assertEquals(FixedPoint.ONE, c.coverage)
	}

	@Test
	fun sparseRunsWeakenTheSpectrumWeight() {
		val sparse = List(3) { green("g${it + 1}", "tool.go.version" to "1.21") }
		val full = rank(greens(3)).candidates.single()
		val thin = rank(sparse).candidates.single { it.path == "tool.go.version" }
		assertEquals(Tier.CORRELATED, thin.tier)
		assertEquals(333_333L, thin.coverage)
		assertTrue(thin.evidence[1].weight < full.evidence[1].weight)
		assertEquals(
			FixedPoint.ln(Weights.spectrumRatio(FixedPoint.ONE, 333_333L)),
			thin.evidence[1].weight,
		)
	}

	@Test
	fun aVacuousGreenNeverChangesTheInput() {
		val base = rank(greens(3))
		val poisoned = green("g0", "tool.go.version" to "1.24", *ok, steps = vacuous)
		val withVacuous = Ranker.rank(
			Transition(greens(3).last(), bad),
			listOf(poisoned) + greens(3) + bad,
			job,
			emptyList(),
		)
		assertEquals(base.candidates, withVacuous.candidates)
		assertEquals(base.bundles, withVacuous.bundles)
		assertEquals(listOf("g0"), withVacuous.excluded.map { it.runId })
		assertEquals(listOf(ExclusionReason.VACUOUS), withVacuous.excluded.map { it.reason })
	}

	@Test
	fun aGreenWithoutStepEvidenceIsLeftOutAndTheSpectrumFallsBack() {
		val blind = List(3) {
			green("g${it + 1}", "tool.go.version" to "1.21", *ok, steps = emptyList())
		}
		val ranking = rank(blind)
		assertEquals(Tier.DIFFERENCE, ranking.candidates.single().tier)
		assertEquals(3, ranking.excluded.size)
		assertTrue(ranking.excluded.all { it.reason == ExclusionReason.NO_STEP_EVIDENCE })
	}

	@Test
	fun shufflingTheHistoryNeverChangesTheRanking() {
		val history = greens(4) + green("g5", "tool.go.version" to "1.22", *ok)
		val expected = Ranker.rank(Transition(history.last(), bad), history + bad, job, emptyList())
		for (seed in 1L..20L) {
			val order = (history + bad).shuffled(Rng(seed))
			assertEquals(
				expected,
				Ranker.rank(Transition(history.last(), bad), order, job, emptyList()),
				"$seed",
			)
		}
	}

	@Test
	fun changesNoRunSeparatesFormOneBundle() {
		val before = arrayOf("tool.go.version" to "1.21", "tool.node.version" to "22", *ok)
		val after = arrayOf("tool.go.version" to "1.24", "tool.node.version" to "24", *ok)
		val gs = List(3) { green("g${it + 1}", *before) }
		val reds = listOf(red("r1", *after), red("r2", *after))
		val ranking = Ranker.rank(Transition(gs.last(), reds[0]), gs + reds, job, emptyList())
		assertEquals(
			listOf("tool.go.version", "tool.node.version"),
			ranking.bundles.single().members,
		)
		assertEquals(listOf("bundle-1", "bundle-1"), ranking.candidates.map { it.bundle })
		assertEquals(TransitionKind.BUNDLE, ranking.transition?.kind)
	}

	@Test
	fun aChangeOnlySomeRedsShareIsNotInTheBundle() {
		val before = arrayOf("tool.go.version" to "1.21", "tool.node.version" to "22", *ok)
		val gs = List(3) { green("g${it + 1}", *before) }
		val r1 = red("r1", "tool.go.version" to "1.24", "tool.node.version" to "24", *ok)
		val r2 = red("r2", "tool.go.version" to "1.24", "tool.node.version" to "22", *ok)
		val ranking = Ranker.rank(Transition(gs.last(), r1), gs + r1 + r2, job, emptyList())
		assertEquals(
			listOf("tool.go.version"),
			ranking.candidates.filter {
			it.spectrum?.ef == 2
		}.map { it.path },
		)
		assertEquals(emptyList(), ranking.bundles)
		assertEquals(listOf(null, null), ranking.candidates.map { it.bundle })
	}

	@Test
	fun addedStepsAreListedWithTheTransition() {
		val extra = failed + Step(job, "dump", StepConclusion.FAILURE, null)
		val r = red("r1", "tool.go.version" to "1.24", *ok, steps = extra)
		val gs = greens(3)
		val ranking = Ranker.rank(Transition(gs.last(), r), gs + r, job, emptyList())
		assertEquals(listOf("dump"), ranking.transition?.steps?.map { it.name })
		assertEquals(DiffKind.ADDED, ranking.transition?.steps?.single()?.kind)
		assertEquals(TransitionKind.BUNDLE, ranking.transition?.kind)
	}

	@Test
	fun noChangeAndNoCapsuleGiveNoCandidates() {
		val same = red("r1", "tool.go.version" to "1.21", *ok)
		val gs = greens(3)
		val unchanged = Ranker.rank(Transition(gs.last(), same), gs + same, job, emptyList())
		assertEquals(emptyList(), unchanged.candidates)
		assertEquals(TransitionKind.UNEXPLAINED, unchanged.transition?.kind)

		val blind = bad.copy(capsule = null)
		val none = Ranker.rank(Transition(gs.last(), blind), gs + blind, job, emptyList())
		assertEquals(emptyList(), none.candidates)
		assertEquals(TransitionKind.NO_CAPSULE, none.transition?.kind)
	}

	@Test
	fun aRedWithNoFailedStepRanksWithoutASpectrum() {
		val r = bad.copy(steps = emptyList())
		val gs = greens(3)
		val c = Ranker.rank(Transition(gs.last(), r), gs + r, job, emptyList()).candidates.single()
		assertEquals(Tier.DIFFERENCE, c.tier)
		assertNull(c.coverage)
	}

	@Test
	fun theFailingRunsSymptomsFeedTheRules() {
		val rule = demoRule(
			"""{"all":[{"b":"run:exit","eq":"1"},{"b":"tool.go.version","ver":">=1.24"}]}""",
			"symptoms" to """[{"text":"t","basis":"verified"}]""",
		)
		val gs = greens(3)
		val exit = Symptom(SymptomKind.EXIT, "1", 3)
		val failing = bad.copy(symptoms = listOf(exit))
		val withRun = Ranker.rank(Transition(gs.last(), failing), gs + failing, job, listOf(rule))
		val c = withRun.candidates.single()
		assertEquals(Tier.KNOWN, c.tier)
		assertEquals("rule:demo-rule", c.evidence.last { it.kind == EvidenceKind.RULE }.ref)
		val without = Ranker.rank(Transition(gs.last(), bad), gs + bad, job, listOf(rule))
		assertEquals(Tier.CORRELATED, without.candidates.single().tier)
		assertEquals(emptyList(), without.unattached)
	}

	@Test
	fun withoutAProbeOrARuleNoCandidateExceedsCorrelatedWhateverTheHistory() {
		val rng = Rng(11)
		val pool = listOf("tool.go.version", "tool.cc.version", "env.HOME", "cpu.count")
		val weak = setOf(EvidenceKind.ATTRIBUTE, EvidenceKind.SPECTRUM)

		fun attrs(salt: Int) = pool.filter { rng.next(3) != 0 }.map { it to "v${rng.next(salt)}" }
			.toTypedArray()

		repeat(60) { n ->
			val gs = List(2 + rng.next(4)) { green("g$it", *attrs(2)) }
			val rs = List(1 + rng.next(3)) { red("r$it", *attrs(3)) }
			val ranking = Ranker.rank(Transition(gs.last(), rs[0]), gs + rs, job, emptyList())
			for (c in ranking.candidates) {
				assertTrue(c.tier <= Tier.CORRELATED, "$n ${c.path}")
				assertTrue(c.evidence.all { it.kind in weak }, c.path)
				assertEquals(c.evidence.sumOf { it.weight }, c.score, "$n ${c.path}")
			}
		}
	}

	// #region drangler
	private val key = HistoryKey("e2e", FixtureRuns.JOB)
	private val everyRun = (FixtureRuns.reds + FixtureRuns.greens).map { FixtureRuns.run(it) }
	private val history = History.of(key, everyRun.shuffled(Rng(5)), RunOrder.RUN_ID)

	private val members = listOf(
		"tool.node.version",
		"ci.provisioner.build-date",
		"ci.provisioner.version",
		"ci.runner.image.version",
		"deps.@napi-rs/keyring.version",
		"deps.prettier-plugin-sh.version",
		"deps.wrangler.version",
		"tool.npm.version",
	)

	@Test
	fun theDranglerTransitionIsOneBundleNoCandidateIsTheCause() {
		val ranking = Ranker.rank(history.transition!!, history.runs, FixtureRuns.JOB)
		assertEquals(TransitionKind.BUNDLE, ranking.transition?.kind)
		assertEquals(
			listOf("Dump Host State on Failure", "Post Set up Node", "Set up Node"),
			ranking.transition?.steps?.map { it.name },
		)
		assertEquals(members, ranking.candidates.map { it.path })
		assertEquals(listOf(Tier.CORRELATED), ranking.candidates.map { it.tier }.distinct())
		assertEquals(listOf("bundle-1"), ranking.candidates.map { it.bundle }.distinct())
		assertEquals(members, ranking.bundles.single().members)
		assertEquals(emptyList(), ranking.excluded)
		assertEquals(emptyList(), ranking.unattached)
		assertTrue(ranking.candidates.all { it.matches.isEmpty() && it.basis == Basis.INFERRED })
		assertEquals(862_068L, ranking.candidates.map { it.coverage }.distinct().single())
		assertTrue(ranking.candidates.all { it.spectrum == Spectrum(3, 0, 0, FixedPoint.ONE) })
	}

	@Test
	fun theChangedBuildDateIsNeverReportedEqual() {
		val diff = CapsuleDiff.diff(
			FixtureRuns.run(FixtureRuns.green).capsule!!,
			FixtureRuns.run(FixtureRuns.reds[0]).capsule!!,
		)
		val date = diff.changes.single { it.path == "ci.provisioner.build-date" }
		assertEquals(Order.UNORDERED, date.order)
		assertTrue(diff.changes.none { it.order == Order.EQUAL })
	}

	@Test
	fun withoutStepEvidenceInTheGreensTheDranglerFallsBackToDifferences() {
		val blind = history.runs.map {
			if (it.outcome ==
			RunOutcome.PASS
			) {
				it.copy(steps = emptyList())
			} else {
				it
			}
		}
		val ranking = Ranker.rank(history.transition!!, blind, FixtureRuns.JOB)
		assertEquals(listOf(Tier.DIFFERENCE), ranking.candidates.map { it.tier }.distinct())
		assertEquals(5, ranking.excluded.size)
		assertTrue(ranking.candidates.all { it.spectrum == null })
		val shares = ranking.candidates.map { it.probability!! }
		assertTrue(shares.sum() < 1_000_000L, shares.toString())
	}

	@Test
	fun aSpectrumTakesTheProbabilityOffEveryDranglerCandidate() {
		val ranking = Ranker.rank(history.transition!!, history.runs, FixtureRuns.JOB)
		assertEquals(8, ranking.candidates.size)
		assertTrue(ranking.candidates.all { it.spectrum != null && it.probability == null })
	}

	@Test
	fun theDranglerRankingBytesAreIdenticalOnEveryTarget() {
		val ranking = Ranker.rank(history.transition!!, history.runs, FixtureRuns.JOB)
		val json = CanonicalJson.encode(ranking.toJson())
		assertEquals(Sha256.hex(json), Sha256.hex(CanonicalJson.encode(ranking.toJson())))
		assertEquals(DRANGLER_SHA, Sha256.hex(json))
	}

	@Test
	fun theDranglerRankingDoesNotDependOnRunAttributeOrRuleOrder() {
		val expected = Ranker.rank(history.transition!!, history.runs, FixtureRuns.JOB)
		val green = history.transition!!.green.id
		val red = history.transition!!.red.id
		for (seed in 1L..6L) {
			val runs = everyRun.shuffled(Rng(seed)).map { run ->
				val c = run.capsule!!
				run.copy(capsule = c.copy(attributes = c.attributes.shuffled(Rng(seed))))
			}
			val byId = runs.associateBy { it.id }
			val transition = Transition(byId.getValue(green), byId.getValue(red))
			val rules = Rules.all.shuffled(Rng(seed))
			assertEquals(expected, Ranker.rank(transition, runs, FixtureRuns.JOB, rules), "$seed")
		}
	}

	@Test
	fun aHistoryPastTheOchiaiLimitRanksWithoutASpectrum() {
		val gs = List(Weights.MAX_RUNS) { green("g$it", "tool.go.version" to "1.21") }
		val ranking = Ranker.rank(Transition(gs.last(), bad), gs + bad, job, emptyList())
		val c = ranking.candidates.single { it.path == "tool.go.version" }
		assertEquals(Tier.DIFFERENCE, c.tier)
		assertNull(c.spectrum)
	}
	// #endregion

	private companion object {
		const val DRANGLER_SHA = "189eb08d0ef4a5bc26c929a8116e584e974d74af9b67af23e7fa8bdcc20fc8d5"
	}
}
