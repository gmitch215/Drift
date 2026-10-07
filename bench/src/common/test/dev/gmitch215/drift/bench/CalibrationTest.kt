package dev.gmitch215.drift.bench

import dev.gmitch215.drift.fixtures.BenchData
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.know.Severity
import dev.gmitch215.drift.model.Dimension
import dev.gmitch215.drift.rank.Tier
import dev.gmitch215.drift.rank.Weights
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CalibrationTest {
	private fun lab(dir: String = "probed", onCapsule: (String) -> Unit = {}) = Lab.parse(
		BenchData.text("scenarios.json"),
		BenchData.text("split.json"),
	) {
		onCapsule(it)
		BenchData.text("$dir/$it")
	}

	private fun fit() = CanonicalJson.parse(BenchData.text("calibration/fit.json")).obj()

	private fun JsonObject.obj(key: String) = require(key).let { it as JsonObject }

	private fun cand(dimension: Dimension, label: Boolean, probe: Boolean = false) = Cand(
		"x.${dimension.id}",
		dimension,
		probe,
		emptyList(),
		Tier.DIFFERENCE,
		false,
		0,
		null,
		label,
		false,
	)

	private fun arm() = Arm(
		"img", emptyMap(), "1g", "1", null, emptyMap(), "none", emptyMap(), null, emptyList(), 0, 0,
	)

	private fun observed(id: String, vararg cands: Cand) = Observed(
		Scenario(
			id, "t", "t", "env", 1, "sh", "", "", null, 20, arm(), arm(), null, emptyList(),
			emptyList(), true, true, emptyList(), "",
		),
		emptyList(),
		cands.toList(),
	)

	// #region isotonic
	private fun points(vararg labels: Boolean) =
		labels.mapIndexed { i, hit -> (i + 1).toLong() to hit }

	@Test
	fun isotonicPoolsAdjacentViolatorsLikeClassicPav() {
		val iso = Isotonic.fit(points(false, true, false, true, true), 0)
		assertEquals(
			listOf(1L to 0L, 2L to 500_000L, 4L to 1_000_000L, 5L to 1_000_000L),
			iso.steps,
		)
		assertEquals(0L, iso.at(1))
		assertEquals(500_000L, iso.at(3))
		assertEquals(1_000_000L, iso.at(99))
		assertEquals(0L, iso.at(-5))
	}

	@Test
	fun isotonicMergesATiedScoreBeforeAnythingElse() {
		val iso = Isotonic.fit(listOf(1L to true, 1L to false, 2L to true), 0)
		assertEquals(listOf(1L to 500_000L, 2L to 1_000_000L), iso.steps)
	}

	@Test
	fun aFallingTrendPoolsToOneSmoothedBlock() {
		val iso = Isotonic.fit(points(true, true, false, false))
		assertEquals(listOf(1L to 500_000L), iso.steps)
		val three = Isotonic.fit(points(true, false, false))
		assertEquals(listOf(1L to 400_000L), three.steps)
	}

	@Test
	fun smoothedStepsNeverDecreaseOnRandomData() {
		for (seed in 1L..30L) {
			val rng = SplitMix64(seed)
			val data = List(40) { rng.below(12).toLong() to (rng.below(3) == 0) }
			val steps = Isotonic.fit(data).steps
			assertEquals(steps.map { it.first }.sorted().distinct(), steps.map { it.first })
			assertEquals(steps.map { it.second }.sorted(), steps.map { it.second }, "seed $seed")
		}
	}
	// #endregion

	// #region reliability
	@Test
	fun reliabilityBinsCountPredictedAndObserved() {
		fun e(p: Long, hit: Boolean) = Entry("a", hit, false, null, false, p)
		val bins = Evaluate.reliability(
			listOf(
				e(100_000, false),
				e(150_000, false),
				e(500_000, true),
				e(900_000, true),
				e(1_000_000, false),
			),
		).items.map { it as JsonObject }
		assertEquals(Evaluate.BINS, bins.size)
		assertEquals(listOf(2L, 0L, 1L, 0L, 2L), bins.map { it.require("n").long() })
		assertEquals(125_000L, bins[0].require("mean_predicted").long())
		assertEquals(500_000L, bins[2].require("mean_predicted").long())
		assertEquals(950_000L, bins[4].require("mean_predicted").long())
		assertEquals(500_000L, bins[4].obj("observed").require("micro").long())
		assertEquals(1L, bins[2].obj("observed").require("count").long())
		assertEquals(0L, bins[3].obj("observed").require("total").long())
	}

	@Test
	fun theChanceOfAtLeastKHitsIsTheExactPoissonBinomialTail() {
		assertEquals(1_000_000L, Evaluate.tail(listOf(500_000, 500_000), 0))
		assertEquals(750_000L, Evaluate.tail(listOf(500_000, 500_000), 1))
		assertEquals(250_000L, Evaluate.tail(listOf(500_000, 500_000), 2))
		assertEquals(0L, Evaluate.tail(listOf(0, 0), 1))
		assertEquals(1_000_000L, Evaluate.tail(listOf(1_000_000, 1_000_000), 2))
		assertEquals(
			1_000_000L - 900_000L * 800_000L / 1_000_000L,
			Evaluate.tail(listOf(100_000, 200_000), 1),
		)
	}

	@Test
	fun theShippedOrderBeatsARandomOrderAtTopOneButNotAtTopThree() {
		val v = Evaluate.versusRandom(OBSERVED)
		val one = v.obj("top1")
		val three = v.obj("top3")
		assertEquals(19L, one.require("hits").long())
		assertEquals(41L, one.require("scenarios").long())
		assertTrue(one.require("p_at_least_micro").long() < 50_000)
		assertTrue(three.require("p_at_least_micro").long() > 250_000)
	}

	@Test
	fun theRandomBaselineIsTheClosedFormExpectationOverOrders() {
		val entries = List(4) { Entry("p$it", it == 2, false, null, false, 250_000) }
		val pred = Pred(observed("r"), entries, random = true)
		assertEquals(250_000L, pred.hit(1))
		assertEquals(750_000L, pred.hit(3))
		assertEquals(1_000_000L, pred.hit(9).coerceAtMost(1_000_000L))
		assertEquals(520_833L, pred.reciprocal())
		val absent = entries.map { Entry(it.path, false, false, null, false, 0) }
		assertEquals(0L, Pred(observed("r"), absent, random = true).hit(3))
	}
	// #endregion

	// #region folds
	@Test
	fun foldsDependOnTheIdAloneAndAreSpreadOverTheDevSplit() {
		assertEquals(3, Folds.of("memory-oom-1"))
		assertEquals(0, Folds.of("env-pool-size-2"))
		assertEquals(3, Folds.of("kernel-release-gate-basic-1"))
		val dev = lab().devIds
		assertEquals(setOf(0, 1, 2, 3, 4), dev.map { Folds.of(it) }.toSet())
		assertEquals(dev.map { Folds.of(it) }, dev.reversed().reversed().map { Folds.of(it) })
		assertTrue(dev.all { Folds.of(it, 7) in 0 until 7 })
	}
	// #endregion

	// #region sealed
	@Test
	fun noFitOrEvaluationPathReachesATestScenario() {
		val seen = mutableListOf<String>()
		val lab = lab("probed") { seen += it }
		val test = SplitRule.split(
			(CanonicalJson.parse(BenchData.text("scenarios.json")) as JsonArray).items
				.map { it.obj().require("id").string() },
		).test
		assertTrue(test.isNotEmpty())
		assertTrue(lab.devIds.none { it in test })
		val e = assertFailsWith<SealedException> {
			lab.observe(listOf(lab.devIds.first(), test.first()))
		}
		assertTrue(test.first() in e.message!!)
		assertFailsWith<SealedException> { lab.observe(listOf("no-such-scenario")) }
		assertEquals(emptyList(), seen)
		lab.observeDev()
		assertTrue(seen.isNotEmpty() && seen.none { f -> test.any { f.startsWith("$it.") } })
	}
	// #endregion

	// #region probes
	private val originalObserved: List<Observed> by lazy { lab("capsules").observeDev() }

	private val removedObserved: List<Observed> by lazy {
		lab("probed").withoutProbes().observeDev()
	}

	private fun causeRank(o: Observed) = o.ranked.indexOfFirst { it.label }.takeIf { it >= 0 }

	@Test
	fun theProbedPairsCarryProbeDifferencesAndNoRuleMatch() {
		assertEquals(45, OBSERVED.size)
		assertEquals(13, OBSERVED.count { it.probes.isNotEmpty() })
		assertEquals(13, OBSERVED.count { o -> o.ranked.any { it.probe } })
		assertEquals(19, OBSERVED.sumOf { o -> o.ranked.count { it.probe } })
		assertEquals(0, OBSERVED.count { it.rules.isNotEmpty() })
		assertTrue(originalObserved.all { it.probes.isEmpty() && it.rules.isEmpty() })
		assertTrue(removedObserved.all { o -> o.probes.isEmpty() && o.ranked.none { it.probe } })
		assertEquals(OBSERVED.map { it.changed }, removedObserved.map { it.changed })
	}

	@Test
	fun everyDevProbeAgreementReadsTheCandidatePathSoTheLinkChangesNoTier() {
		val candidates = OBSERVED.flatMap { it.ranked }
		assertEquals(3, candidates.count { it.dimensionProbe })
		assertTrue(candidates.filter { it.dimensionProbe }.all { it.probe })
		assertEquals(19, candidates.count { it.tier == Tier.PLAUSIBLE })
		assertTrue(candidates.filter { it.tier == Tier.PLAUSIBLE }.all { it.probe })
		assertTrue(candidates.none { it.tier == Tier.CORRELATED })
	}

	@Test
	fun aCoveredRankingRestsOnAttributeChangesAlone() {
		fun covered(vararg c: Cand) = observed("c", *c).covered
		assertTrue(covered(cand(Dimension.ENV, true)))
		assertTrue(covered())
		assertTrue(!covered(cand(Dimension.ENV, true), cand(Dimension.OS, false, probe = true)))
		val ruled = Cand(
			"x", Dimension.ENV, false, listOf(Severity.LOW), Tier.PLAUSIBLE, false, 0, null, false,
			false,
		)
		assertTrue(!covered(ruled))
		val broad = Cand(
			"x", Dimension.ENV, false, emptyList(), Tier.CORRELATED, false, 0, null, false, false,
			dimensionProbe = true,
		)
		assertTrue(!covered(broad))
		assertEquals(OBSERVED.map { it.covered }, OBSERVED.map { o -> o.ranked.none { it.probe } })
	}

	@Test
	fun theNoneWeightIsFitOnCoveredRankingsOnly() {
		val covered = observed("a", cand(Dimension.ENV, false))
		val probed = observed("b", cand(Dimension.ENV, true, probe = true))
		assertEquals(
			Fit.noneWeight(listOf(covered), Table.handSet),
			Fit.noneWeight(listOf(covered, probed, probed, probed), Table.handSet),
		)
		assertEquals(Fit.NONE_GRID.last(), Fit.noneWeight(listOf(covered), Table.handSet))
	}

	@Test
	fun theProbeTierMovesOneCauseDownAndNoneUp() {
		val moved = OBSERVED.zip(removedObserved).filter { (a, b) -> causeRank(a) != causeRank(b) }
		assertEquals(listOf("env-pool-size-decoy-2"), moved.map { it.first.scenario.id })
		val (probed, removed) = moved.single()
		assertEquals(2, causeRank(probed))
		assertEquals(0, causeRank(removed))
		assertEquals(Tier.PLAUSIBLE, probed.ranked.first().tier)
	}

	@Test
	fun noProbeRatioChangesTheOrderBecauseTheTierComesFirst() {
		val hand = OBSERVED.map { o -> Models.handSet.learner.learn(OBSERVED)(o).rank }
		for (ratio in Evaluate.PROBE_RATIOS) {
			val model = Models.probeAt(ratio).learner.learn(OBSERVED)
			assertEquals(hand, OBSERVED.map { model(it).rank }, "probe ratio $ratio")
		}
	}

	@Test
	fun theHandSetProbeRatioReadsWorseThanOneInNOnRankingsWithAProbe() {
		fun probed(p: Pred) = p.o.ranked.any { it.probe }
		fun cv(m: Model) = Evaluate.brier(Evaluate.crossValidated(OBSERVED, m).filter(::probed))
		val uniform = cv(Models.random)
		assertTrue(cv(Models.probeAt(6_000_000)) > uniform)
		assertTrue(cv(Models.probeAt(1_000_000)) < cv(Models.probeAt(6_000_000)))
	}

	@Test
	fun theFittedProbeRatioDoesNotBeatOneInNOnRankingsWithAProbe() {
		fun probed(p: Pred) = p.o.ranked.any { it.probe }
		val fitted = Evaluate.crossValidated(OBSERVED, Models.evidenceFit).filter(::probed)
		val uniform = Evaluate.crossValidated(OBSERVED, Models.random).filter(::probed)
		assertTrue(Evaluate.brier(fitted) * 100 > Evaluate.brier(uniform) * 98)
		assertEquals(
			Evaluate.brierCovered(Evaluate.crossValidated(OBSERVED, Models.softmaxNone)),
			Evaluate.brierCovered(
				fitted + Evaluate.crossValidated(OBSERVED, Models.evidenceFit)
				.filter { !probed(it) },
			),
		)
	}

	@Test
	fun theReversedPairsExerciseTheRuleGatesAndTheTiersStayHonest() {
		val reversed = lab("probed").reversed().observeDev()
		val honesty = Evaluate.honesty(Evaluate.entries(reversed))
		assertTrue(honesty.require("rule_matches").long() > 0)
		assertTrue(honesty.require("rule_matches_inferred_only").long() > 0)
		assertTrue(honesty.require("known").long() > 0)
		assertEquals(0L, honesty.require("inferred_only_printed_known").long())
		assertEquals(0L, honesty.require("known_without_verified_rule").long())
		for (o in reversed) {
			for (c in o.ranked) {
				if (c.tier == Tier.KNOWN) assertTrue(c.verifiedRule, "${o.scenario.id} ${c.path}")
				if (c.rules.isNotEmpty() && !c.verifiedRule) {
					assertEquals(Tier.PLAUSIBLE, c.tier, "${o.scenario.id} ${c.path}")
				}
			}
		}
	}

	@Test
	fun reversingALabReadsTheOtherCapsuleOfEachPair() {
		val seen = mutableListOf<String>()
		val reversed = lab("probed") { seen += it }.reversed()
		val id = reversed.devIds.first()
		reversed.observe(listOf(id))
		assertEquals(listOf("$id.red.json", "$id.green.json"), seen)
		val straight = lab("probed").observe(listOf(id)).single()
		val swapped = reversed.observe(listOf(id)).single()
		assertEquals(straight.changed, swapped.changed)
	}
	// #endregion

	// #region fit
	@Test
	fun priorsAreTheSmoothedOddsAgainstTheOverallOdds() {
		val train = listOf(
			observed("a", cand(Dimension.ENV, true), cand(Dimension.ENV, false)),
			observed("b", cand(Dimension.ENV, false), cand(Dimension.ENV, false)),
			observed("c", cand(Dimension.BUILD, true), cand(Dimension.BUILD, true)),
		)
		val table = Fit.priors(train)
		assertEquals(500_000L, table.dimensions.getValue(Dimension.ENV))
		assertEquals(3_000_000L, table.dimensions.getValue(Dimension.BUILD))
		assertEquals(1_000_000L, table.dimensions.getValue(Dimension.OS))
		assertEquals(Table.handSet.probe, table.probe)
		assertEquals(Table.handSet.rules, table.rules)
	}

	@Test
	fun evidenceThatNeverOccursKeepsItsHandSetWeightAndEvidenceThatDoesIsFit() {
		val none = listOf(observed("a", cand(Dimension.ENV, true), cand(Dimension.ENV, false)))
		assertEquals(Table.handSet.probe, Fit.full(none).probe)
		val probed = listOf(
			observed(
				"a",
				cand(Dimension.ENV, true, probe = true),
				cand(Dimension.ENV, false),
				cand(Dimension.ENV, false),
				cand(Dimension.ENV, false),
			),
		)
		assertEquals(4_000_000L, Fit.full(probed).probe)
	}

	@Test
	fun theNoneWeightIsTheGridValueWithTheLowestTrainingBrier() {
		val train = listOf(
			observed("a", cand(Dimension.ENV, true)),
			observed("b", cand(Dimension.ENV, true)),
			observed("c", cand(Dimension.ENV, true)),
		)
		assertEquals(Fit.NONE_GRID.first(), Fit.noneWeight(train, Table.handSet))
		val miss = listOf(
			observed("a", cand(Dimension.ENV, false)),
			observed("b", cand(Dimension.ENV, false)),
		)
		assertEquals(Fit.NONE_GRID.last(), Fit.noneWeight(miss, Table.handSet))
	}
	// #endregion

	// #region pipeline
	@Test
	fun theHarnessRescoringEqualsTheRankerOnEveryDevScenario() {
		val table = Table.current()
		val shares = Models.softmaxNone.learner.learn(OBSERVED)
		for (o in OBSERVED) {
			for (c in o.ranked) {
				assertEquals(c.rankerScore, table.score(c), "${o.scenario.id} ${c.path}")
			}
			val pred = shares(o)
			val expected = if (o.covered) {
				pred.ranked.sortedBy { e -> o.ranked.indexOfFirst { it.path == e.path } }
					.map { it.path to it.p }
			} else {
				o.ranked.map { it.path to null }
			}
			assertEquals(expected, o.ranked.map { it.path to it.probability }, o.scenario.id)
		}
	}

	@Test
	fun theCommittedWeightsEqualTheGeneratedFit() {
		val fit = fit()
		val table = fit.obj("table")
		for ((dimension, weight) in Weights.dimensions) {
			val fitted = table.obj("dimensions").require(dimension.id).long()
			assertEquals(weight.ratio, fitted, dimension.id)
		}
		assertEquals(Weights.probe.ratio, table.require("probe").long())
		for ((severity, weight) in Weights.rules) {
			assertEquals(weight.ratio, table.obj("rules").require(severity.id).long(), severity.id)
		}
		val probability = fit.obj("probability")
		assertEquals(Weights.NONE, probability.require("none").long())
		val provenance = fit.obj("provenance")
		val dev = provenance.require("dev_scenarios").long()
		val seed = provenance.require("seed").long()
		assertEquals("dev split of $dev synthetic scenarios, seed $seed", Weights.FIT)
		assertEquals(
			fit.obj("selection").require("chosen").string(),
			Models.softmaxNone.id,
		)
		assertEquals(fit.obj("table").toString(), fit.obj("hand_set").toString())
		assertEquals(Table.handSet.dimensions, Table.current().dimensions)
		assertEquals(Table.handSet.probe, Table.current().probe)
		assertEquals(Table.handSet.rules, Table.current().rules)
	}

	@Test
	fun aBundleOfAttributeChangesSharesLessThanAllOfTheMass() {
		val scores = listOf(Weights.dimensions.getValue(Dimension.RUNTIME).logOdds) +
			List(7) { Weights.dimensions.getValue(Dimension.BUILD).logOdds }
		val shares = Weights.shares(scores)
		assertTrue(shares.sum() < 1_000_000L)
		assertTrue(shares.sum() > 980_000L, shares.sum().toString())
		assertEquals(1, shares.drop(1).distinct().size)
		assertTrue(shares.first() > shares[1])
	}

	@Test
	fun theCalibrationFilesAreByteIdenticalToARegeneration() {
		val c = Calibrate.run(Inputs(lab("probed"), lab("capsules")), SEED)
		assertEquals(BenchData.text("calibration/fit.json"), c.fit)
		assertEquals(BenchData.text("calibration/evaluation.json"), c.evaluation)
		assertEquals(PINNED_FIT_SHA256, Sha256.hex(c.fit))
		assertEquals(PINNED_EVALUATION_SHA256, Sha256.hex(c.evaluation))
	}

	@Test
	fun theChosenModelBeatsEveryBaselineOutOfFold() {
		val cv = Models.everything.associate { it.id to Evaluate.crossValidated(OBSERVED, it) }
		val chosen = Evaluate.brierCovered(cv.getValue(Models.softmaxNone.id))
		val beaten =
			listOf("hand-set", "hand-set+base-rate", "hand-set+isotonic", "priors+isotonic")
		for (id in beaten) {
			assertTrue(chosen < Evaluate.brierCovered(cv.getValue(id)), id)
		}
		assertTrue(chosen < Evaluate.brierCovered(cv.getValue("diff-order")))
		val order = Evaluate.mrr(cv.getValue(Models.softmaxNone.id))
		assertTrue(order > Evaluate.mrr(cv.getValue("random")))
	}
	// #endregion

	private companion object {
		const val SEED = 20261006L
		const val PINNED_FIT_SHA256 =
			"29712e5a14a1d57c91c82b1cb449138f41f06b2ae47865af1a7b0289bf0c6b11"
		const val PINNED_EVALUATION_SHA256 =
			"66f6c529896e197d84bce947c22ed75c400b89d33a1c034018dae11dc6767dfc"

		val OBSERVED: List<Observed> by lazy {
			Lab.parse(
				BenchData.text("scenarios.json"),
				BenchData.text("split.json"),
			) { BenchData.text("probed/$it") }.observeDev()
		}
	}
}
