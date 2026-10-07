package dev.gmitch215.drift.bench

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScoreTest {
	private val truth = Truth("env.TZ")

	@Test
	fun rankIsOneBasedAndAbsentIsNull() {
		assertEquals(1, Metrics.rank(truth, Diagnosis(listOf("env.TZ", "env.LANG"))))
		assertEquals(2, Metrics.rank(truth, Diagnosis(listOf("env.LANG", "env.TZ"))))
		assertEquals(null, Metrics.rank(truth, Diagnosis(listOf("env.LANG"))))
		assertEquals(null, Metrics.rank(Truth(null), Diagnosis(listOf("env.TZ"))))
	}

	@Test
	fun topKCountsRanksAtOrBelowK() {
		val ranks = listOf(1, 2, 4, null)
		assertEquals(1, Metrics.topK(ranks, 1).count)
		assertEquals(2, Metrics.topK(ranks, 3).count)
		assertEquals(4, Metrics.topK(ranks, 3).total)
		assertEquals(500_000, Metrics.topK(ranks, 3).micro)
		assertEquals(0, Rate(0, 0).micro)
	}

	@Test
	fun mrrIsTheMeanReciprocalRank() {
		assertEquals(583_333, Metrics.mrr(listOf(1, 2, 4)))
		assertEquals(437_500, Metrics.mrr(listOf(1, 2, 4, null)))
		assertEquals(0, Metrics.mrr(emptyList()))
	}

	@Test
	fun brierIsTheMeanSquaredError() {
		assertEquals(0, Metrics.brier(listOf(1_000_000L to true, 0L to false)))
		assertEquals(1_000_000, Metrics.brier(listOf(0L to true, 1_000_000L to false)))
		assertEquals(250_000, Metrics.brier(listOf(500_000L to true, 500_000L to false)))
		assertEquals(0, Metrics.brier(emptyList()))
	}

	@Test
	fun falseConfirmedCountsWrongOrUnearnedConfirmations() {
		val results = listOf(
			truth to Diagnosis(listOf("env.TZ"), confirmed = "env.TZ"),
			truth to Diagnosis(listOf("env.LANG"), confirmed = "env.LANG"),
			Truth(null) to Diagnosis(listOf("env.TZ"), confirmed = "env.TZ"),
			Truth(null) to Diagnosis(listOf("env.TZ")),
			truth to Diagnosis(emptyList()),
		)
		val rate = Metrics.falseConfirmed(results)
		assertEquals(2, rate.count)
		assertEquals(5, rate.total)
		assertEquals(400_000, rate.micro)
	}

	@Test
	fun wilsonMatchesTheClosedForm() {
		val cases = listOf(
			Triple(0, 10, 0L to 277_533L),
			Triple(5, 10, 236_593L to 763_407L),
			Triple(10, 10, 722_467L to 1_000_000L),
			Triple(3, 20, 52_369L to 360_419L),
			Triple(1, 1, 206_549L to 1_000_000L),
		)
		for ((k, n, expected) in cases) {
			val w = Metrics.wilson(k, n)
			assertTrue(abs(w.low - expected.first) <= 3, "low of $k/$n was ${w.low}")
			assertTrue(abs(w.high - expected.second) <= 3, "high of $k/$n was ${w.high}")
		}
		assertEquals(Metrics.wilson(5, 10).low, Rate(5, 10).wilson().low)
	}

	@Test
	fun wilsonRejectsBadCounts() {
		assertFailsWith<IllegalArgumentException> { Metrics.wilson(1, 0) }
		assertFailsWith<IllegalArgumentException> { Metrics.wilson(5, 4) }
		assertFailsWith<IllegalArgumentException> { Metrics.wilson(-1, 4) }
	}
}
