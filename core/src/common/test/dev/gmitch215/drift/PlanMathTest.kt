package dev.gmitch215.drift

import dev.gmitch215.drift.math.FixedPoint
import dev.gmitch215.drift.plan.Belief
import dev.gmitch215.drift.plan.Fisher
import dev.gmitch215.drift.plan.Information
import dev.gmitch215.drift.plan.Mass
import dev.gmitch215.drift.plan.OutcomeTable
import dev.gmitch215.drift.plan.PValue
import dev.gmitch215.drift.plan.Trials
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlanMathTest {
	private val one = FixedPoint.ONE

	private fun near(expected: Long, actual: Long, tolerance: Long, what: String = "") =
		assertTrue(abs(expected - actual) <= tolerance, "$what expected $expected got $actual")

	private fun uniform(n: Int) =
		Belief(Belief.share(List(n) { 1L }).mapIndexed { i, m -> Mass("h$i", m) })

	private fun exact(vararg groups: Int, n: Int): OutcomeTable {
		val rows = (0 until n).associate { h ->
			var g = 0
			var start = 0
			for ((i, size) in groups.withIndex()) {
				if (h >= start && h < start + size) g = i
				start += size
			}
			"h$h" to List(groups.size) { if (it == g) one else 0L }
		}
		return OutcomeTable(groups.indices.map { "o$it" }, rows)
	}

	// #region belief
	@Test
	fun shareGivesTheLeftoverUnitsToTheLargestRemaindersAndTheEarlierIndexOnATie() {
		assertEquals(listOf(333_334L, 333_333L, 333_333L), Belief.share(listOf(1, 1, 1)))
		assertEquals(listOf(333_333L, 666_667L), Belief.share(listOf(1, 2)))
		assertEquals(listOf(0L, one), Belief.share(listOf(0, 5)))
		assertEquals(listOf(7L, 3L), Belief.share(listOf(7, 3), 10))
	}

	@Test
	fun shareRejectsBadWeights() {
		assertFailsWith<IllegalArgumentException> { Belief.share(emptyList()) }
		assertFailsWith<IllegalArgumentException> { Belief.share(listOf(0, 0)) }
		assertFailsWith<IllegalArgumentException> { Belief.share(listOf(-1, 3)) }
		assertFailsWith<IllegalArgumentException> { Belief.share(listOf(Long.MAX_VALUE, 1)) }
	}

	@Test
	fun aBeliefSortsTheKnownIdsAndKeepsTheUnknownMassExact() {
		val belief = Belief.of(mapOf("b" to 1L, "a" to 3L), 250_000)
		assertEquals(listOf("a", "b", Belief.UNKNOWN), belief.masses.map { it.id })
		assertEquals(listOf(562_500L, 187_500L, 250_000L), belief.masses.map { it.micro })
		assertEquals(187_500L, belief.mass("b"))
		assertEquals(0L, belief.mass("zzz"))
	}

	@Test
	fun withNoKnownHypothesisAllTheMassIsUnknown() {
		val belief = Belief.of(emptyMap(), 250_000)
		assertEquals(listOf(Mass(Belief.UNKNOWN, one)), belief.masses)
		assertEquals(0L, belief.entropy())
	}

	@Test
	fun aBeliefRejectsWhatCannotBeADistribution() {
		assertFailsWith<IllegalArgumentException> { Belief.of(mapOf(Belief.UNKNOWN to 1L), 0) }
		assertFailsWith<IllegalArgumentException> { Belief.of(mapOf("a" to 1L), one + 1) }
		assertFailsWith<IllegalArgumentException> { Belief(listOf(Mass("a", 1))) }
		val negative = listOf(Mass("a", -1), Mass("b", one + 1))
		assertFailsWith<IllegalArgumentException> { Belief(negative) }
		val twice = listOf(Mass("a", one / 2), Mass("a", one / 2))
		assertFailsWith<IllegalArgumentException> { Belief(twice) }
	}

	@Test
	fun sharesAlwaysSumExactlyAndNeverGoNegative() {
		val rng = Rng(11)
		repeat(300) { seed ->
			val n = 1 + rng.next(9)
			val weights = List(n) {
				if (rng.next(4) == 0) 0L else rng.next(1_000_000).toLong() * rng.next(1000)
			}
			if (weights.sum() == 0L) return@repeat
			val shares = Belief.share(weights)
			assertEquals(one, shares.sum(), "$seed")
			assertTrue(shares.all { it >= 0 }, "$seed")
			val sum = weights.sum()
			for (i in weights.indices) {
				val floor = weights[i] * one / sum
				assertTrue(shares[i] == floor || shares[i] == floor + 1, "$seed $i")
				if (weights[i] == 0L) assertEquals(0L, shares[i], "$seed zero weight")
			}
		}
	}

	@Test
	fun posteriorsAlwaysSumExactlyAndNeverGoNegative() {
		val rng = Rng(23)
		repeat(300) { seed ->
			val n = 2 + rng.next(7)
			val k = 2 + rng.next(3)
			val weights = (0 until n).associate { "h$it" to 1L + rng.next(100_000) }
			val prior = Belief.of(weights, rng.next(400_000).toLong())
			val rows = prior.masses.associate { m ->
				val raw = List(k) { if (rng.next(3) == 0) 0L else rng.next(1000).toLong() }
				m.id to Belief.share(if (raw.sum() == 0L) List(k) { 1L } else raw)
			}
			val table = OutcomeTable(List(k) { "o$it" }, rows)
			val branches = Information.branches(prior, table)
			assertEquals(one, branches.sumOf { it.probability }, "$seed")
			for (b in branches) {
				assertEquals(one, b.posterior.masses.sumOf { it.micro }, "$seed ${b.outcome}")
				assertTrue(b.posterior.masses.all { it.micro >= 0 }, "$seed ${b.outcome}")
			}
			val gain = Information.gain(prior, branches)
			assertTrue(gain >= 0 && gain <= prior.entropy() + 8, "$seed gain $gain")
		}
	}
	// #endregion

	// #region information
	@Test
	fun log2IsExactOnPowersOfTwoAndWithinTwoMicroBitsElsewhere() {
		assertEquals(0L, Information.log2(one))
		assertEquals(-1_000_000L, Information.log2(500_000))
		assertEquals(-2_000_000L, Information.log2(250_000))
		assertEquals(-3_000_000L, Information.log2(125_000))
		near(-415_037, Information.log2(750_000), 2, "3/4")
		near(-1_584_964, Information.log2(333_333), 2, "1/3")
		near(-584_962, Information.log2(666_667), 2, "2/3")
		near(-3_321_928, Information.log2(100_000), 2, "1/10")
		near(-152_003, Information.log2(900_000), 2, "9/10")
		near(-19_931_569, Information.log2(1), 2, "1e-6")
		assertFailsWith<IllegalArgumentException> { Information.log2(0) }
		assertFailsWith<IllegalArgumentException> { Information.log2(one + 1) }
	}

	@Test
	fun entropyOfKnownDistributions() {
		assertEquals(1_000_000L, Information.entropy(listOf(500_000, 500_000)))
		assertEquals(2_000_000L, Information.entropy(List(4) { 250_000L }))
		assertEquals(3_000_000L, Information.entropy(List(8) { 125_000L }))
		assertEquals(0L, Information.entropy(listOf(one, 0)))
		near(1_584_963, Information.entropy(Belief.share(listOf(1, 1, 1))), 3, "log2 3")
	}

	@Test
	fun aPerfectTestBetweenTwoEquiprobableHypothesesIsExactlyOneBit() {
		val gain = Information.gain(uniform(2), exact(1, 1, n = 2))
		assertEquals(1_000_000L, gain)
	}

	@Test
	fun aUselessExperimentGainsExactlyNothing() {
		val same = OutcomeTable(listOf("a", "b"), emptyMap())
		assertEquals(0L, Information.gain(uniform(4), same))
		val rows = (0 until 3).associate { "h$it" to listOf(700_000L, 300_000L) }
		assertEquals(0L, Information.gain(uniform(3), OutcomeTable(listOf("a", "b"), rows)))
	}

	@Test
	fun separatingThreeOfFourIntoOneAndOneAndAPairGainsOneAndAHalfBits() {
		// 2 - (1/2) * 1 = 1.5 bits: outcomes {h0}, {h1}, {h2, h3}; exact in integers
		assertEquals(1_500_000L, Information.gain(uniform(4), exact(1, 1, 2, n = 4)))
		assertEquals(2_000_000L, Information.gain(uniform(4), exact(1, 1, 1, 1, n = 4)))
		assertEquals(1_000_000L, Information.gain(uniform(4), exact(2, 2, n = 4)))
	}

	@Test
	fun threeEquiprobableAndAPerfectTestGainLog2OfThree() {
		near(1_584_963, Information.gain(uniform(3), exact(1, 1, 1, n = 3)), 3, "log2 3")
	}

	@Test
	fun aNoisyTestGainsOneMinusTheBinaryEntropyOfTheNoise() {
		val rows = mapOf("h0" to listOf(900_000L, 100_000L), "h1" to listOf(100_000L, 900_000L))
		val gain = Information.gain(uniform(2), OutcomeTable(listOf("a", "b"), rows))
		near(531_004, gain, 3, "1 - H(0.1)")
	}

	@Test
	fun gainGrowsWithHowEvenlyTheTestSplitsTheHypotheses() {
		val gains = (0..4).map { k ->
			val sizes = if (k == 0) intArrayOf(8) else intArrayOf(k, 8 - k)
			if (k == 0) 0L else Information.gain(uniform(8), exact(*sizes, n = 8))
		}
		assertEquals(0L, gains[0])
		for (k in 1..3) assertTrue(gains[k] < gains[k + 1], "k=$k ${gains[k]} ${gains[k + 1]}")
		assertEquals(1_000_000L, gains[4])
		assertEquals(gains[3], Information.gain(uniform(8), exact(5, 3, n = 8)))
	}

	@Test
	fun anOutcomeNoHypothesisCanProduceLeavesThePriorAlone() {
		val rows = (0 until 2).associate { "h$it" to listOf(one, 0L) }
		val branches = Information.branches(uniform(2), OutcomeTable(listOf("a", "b"), rows))
		assertEquals(listOf(one, 0L), branches.map { it.probability })
		assertEquals(uniform(2), branches[1].posterior)
		assertEquals(uniform(2), branches[0].posterior)
	}

	@Test
	fun aHypothesisWithoutARowIsUniformAndTheTableGroupsIdenticalRows() {
		val table = OutcomeTable(listOf("a", "b", "c"), mapOf("x" to listOf(one, 0L, 0L)))
		assertEquals(listOf(333_334L, 333_333L, 333_333L), table.row("nobody"))
		assertEquals(listOf(listOf("m", "n"), listOf("x")), table.groups(listOf("x", "n", "m")))
		assertFailsWith<IllegalArgumentException> { OutcomeTable(listOf("a"), emptyMap()) }
		assertFailsWith<IllegalArgumentException> { OutcomeTable(listOf("a", "a"), emptyMap()) }
		assertFailsWith<IllegalArgumentException> {
			OutcomeTable(listOf("a", "b"), mapOf("x" to listOf(1L, 1L)))
		}
		assertFailsWith<IllegalArgumentException> {
			OutcomeTable(listOf("a", "b"), mapOf("x" to listOf(one)))
		}
	}

	@Test
	fun theBinaryTableClampsToTheNoiseFloor() {
		val table = OutcomeTable.binary(listOf("a", "b"), setOf("a"), one, 0)
		val floor = OutcomeTable.NOISE_FLOOR
		assertEquals(listOf(one - floor, floor), table.row("a"))
		assertEquals(listOf(floor, one - floor), table.row("b"))
	}
	// #endregion

	// #region fisher
	@Test
	fun fisherMatchesTheTeaTastingTable() {
		val p = Fisher.oneSided(3, 1, 1, 3)
		assertEquals(PValue(17, 70), p)
		assertEquals(242_857L, p.micro())
		assertEquals(PValue(1, 70), Fisher.oneSided(4, 0, 0, 4))
		assertEquals(14_285L, Fisher.oneSided(4, 0, 0, 4).micro())
	}

	@Test
	fun fisherMatchesTextbookAndHandCheckedTables() {
		assertEquals(PValue(3731, 2_704_156), Fisher.oneSided(11, 3, 1, 9))
		assertEquals(1_379L, Fisher.oneSided(11, 3, 1, 9).micro())
		assertEquals(PValue(20, 20), Fisher.oneSided(0, 3, 3, 0))
		assertEquals(one, Fisher.oneSided(0, 3, 3, 0).micro())
		assertEquals(PValue(15, 495), Fisher.oneSided(4, 2, 0, 6))
		assertEquals(PValue(280, 11_440), Fisher.oneSided(8, 2, 1, 5))
		assertEquals(PValue(1, 118_264_581_564_861_424), Fisher.oneSided(30, 0, 0, 30))
		assertEquals(0L, Fisher.oneSided(30, 0, 0, 30).micro())
	}

	@Test
	fun fisherDecidesTheBoundaryExactly() {
		val p = Fisher.oneSided(3, 0, 0, 3)
		assertEquals(PValue(1, 20), p)
		assertTrue(p.atMost(50_000))
		assertTrue(!p.atMost(49_999))
		assertTrue(Fisher.oneSided(2, 0, 0, 2).atMost(166_667))
		assertTrue(!Fisher.oneSided(2, 0, 0, 2).atMost(166_666))
	}

	@Test
	fun theLowerTailIsTheUpperTailOfTheSwappedRows() {
		val upper = Fisher.oneSided(1, 9, 11, 3)
		assertEquals(PValue(2_704_065, 2_704_156), upper)
		assertEquals(Fisher.oneSided(11, 3, 1, 9).numerator, 3731L)
	}

	@Test
	fun binomialCoefficientsFollowPascalAndStayInRange() {
		for (n in 1..Fisher.MAX_TOTAL) {
			for (k in 1 until n) {
				val sum = Fisher.choose(n - 1, k - 1) + Fisher.choose(n - 1, k)
				assertEquals(sum, Fisher.choose(n, k), "$n $k")
			}
		}
		assertEquals(118_264_581_564_861_424L, Fisher.choose(60, 30))
		assertEquals(0L, Fisher.choose(5, 6))
		assertEquals(0L, Fisher.choose(5, -1))
		assertFailsWith<IllegalArgumentException> { Fisher.choose(61, 1) }
		assertFailsWith<IllegalArgumentException> { Fisher.oneSided(-1, 0, 0, 0) }
	}

	@Test
	fun everyOneSidedPValueIsAProbability() {
		val rng = Rng(5)
		repeat(200) {
			val a = rng.next(8)
			val b = rng.next(8)
			val c = rng.next(8)
			val d = rng.next(8)
			val p = Fisher.oneSided(a, b, c, d)
			assertTrue(p.numerator in 0..p.denominator, "$a $b $c $d")
			assertTrue(p.micro() in 0..one)
		}
	}
	// #endregion

	// #region trials
	@Test
	fun theMinimumTrialsPerArmForAlpha() {
		assertEquals(3, Trials.minPerArm(50_000))
		assertEquals(5, Trials.minPerArm(10_000))
		assertEquals(3, Trials.minPerArm(100_000))
		assertEquals(2, Trials.minPerArm(200_000))
		assertEquals(12, Trials.minPerArm(1))
		assertEquals(null, Trials.minPerArm(1, max = 5))
	}

	@Test
	fun theFailureRateIsLaplaceSmoothed() {
		assertEquals(800_000L, Trials.rate(3, 3))
		assertEquals(500_000L, Trials.rate(0, 0))
		assertEquals(714_285L, Trials.rate(4, 5))
		assertFailsWith<IllegalArgumentException> { Trials.rate(4, 3) }
	}

	@Test
	fun thresholdAndPowerFollowTheBinomialByHand() {
		assertEquals(3, Trials.threshold(3, 50_000))
		assertEquals(4, Trials.threshold(6, 50_000))
		assertEquals(null, Trials.threshold(2, 50_000))
		assertEquals(125_000L, Trials.power(3, 3, 500_000))
		assertEquals(901_120L, Trials.power(6, 4, 800_000))
		near(one, Trials.power(5, 0, 300_000), 1, "a tail from zero is everything")
	}

	@Test
	fun theTrialPlanPicksTheSmallestCountThatReachesThePower() {
		val plan = Trials.plan(800_000, 50_000, 800_000)
		assertEquals(3, plan.minimum)
		assertEquals(6, plan.perArm)
		assertEquals(4, plan.threshold)
		assertEquals(901_120L, plan.power)
		val sure = Trials.plan(990_000, 50_000, 800_000)
		assertEquals(3, sure.perArm)
		assertEquals(3, sure.threshold)
		val rare = Trials.plan(Trials.rate(0, 20), 50_000, 800_000, max = 10)
		assertEquals(null, rare.perArm)
		assertTrue(rare.power < 800_000L)
		assertEquals(null, Trials.plan(800_000, 1, 800_000, max = 5).perArm)
	}
	// #endregion
}
