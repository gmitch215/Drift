package dev.gmitch215.drift

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.math.IntSqrt
import dev.gmitch215.drift.spectrum.Element
import dev.gmitch215.drift.spectrum.Ochiai
import dev.gmitch215.drift.spectrum.Run
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OchiaiTest {
	private fun e(path: String, value: String = "1") = Element(path, value)

	private val table = listOf(
		Run(true, setOf(e("a"), e("b"), e("c"), e("e"), e("f"))),
		Run(true, setOf(e("a"), e("b"))),
		Run(false, setOf(e("a"), e("c"), e("d"))),
		Run(false, setOf(e("c"))),
	)

	@Test
	fun intSqrtIsExactOnSmallValues() {
		for (n in 0L..100_000L) {
			val r = IntSqrt.floor(n)
			assertTrue(r * r <= n && (r + 1) * (r + 1) > n, "n=$n r=$r")
		}
	}

	@Test
	fun intSqrtIsExactAtTheLargestSquares() {
		val top = 3_037_000_499L
		assertEquals(top, IntSqrt.floor(Long.MAX_VALUE))
		assertEquals(top, IntSqrt.floor(top * top))
		assertEquals(top - 1, IntSqrt.floor(top * top - 1))
		assertEquals(top, IntSqrt.floor(top * top + 1))
		assertEquals(1L shl 31, IntSqrt.floor(1L shl 62))
		assertEquals((1L shl 31) - 1, IntSqrt.floor((1L shl 62) - 1))
		assertEquals(0, IntSqrt.floor(0))
		assertEquals(1, IntSqrt.floor(1))
		assertEquals(1, IntSqrt.floor(3))
		assertEquals(2, IntSqrt.floor(4))
	}

	@Test
	fun intSqrtRejectsNegatives() {
		assertFailsWith<IllegalArgumentException> { IntSqrt.floor(-1) }
	}

	@Test
	fun scoreMatchesTheHandComputedTable() {
		assertEquals(1_000_000, Ochiai.score(2, 0, 0))
		assertEquals(816_496, Ochiai.score(2, 0, 1))
		assertEquals(707_106, Ochiai.score(1, 1, 0))
		assertEquals(408_248, Ochiai.score(1, 1, 2))
		assertEquals(500_000, Ochiai.score(1, 3, 0))
		assertEquals(0, Ochiai.score(0, 2, 5))
		assertEquals(0, Ochiai.score(0, 0, 0))
	}

	@Test
	fun scoreRejectsBadCounts() {
		assertFailsWith<IllegalArgumentException> { Ochiai.score(-1, 0, 0) }
		assertFailsWith<IllegalArgumentException> { Ochiai.score(1, 2000, 2000) }
	}

	@Test
	fun rankOrdersTheTableByScoreThenPathThenValue() {
		val ranked = Ochiai.rank(table)
		assertEquals(
			listOf("b" to 1_000_000L, "a" to 816_496L, "e" to 707_106L, "f" to 707_106L),
			ranked.take(4).map { it.element.path to it.score },
		)
		assertEquals(
			listOf("c" to 408_248L, "d" to 0L),
			ranked.drop(4).map {
			it.element.path to it.score
		},
		)
		val a = ranked.first { it.element.path == "a" }
		assertEquals(Triple(2, 0, 1), Triple(a.ef, a.nf, a.ep))
	}

	@Test
	fun equalScoresBreakTiesByValue() {
		val runs = listOf(Run(true, setOf(e("p", "z"), e("p", "m"))))
		assertEquals(listOf("m", "z"), Ochiai.rank(runs).map { it.element.value })
	}

	@Test
	fun noRunsAndNoFailuresScoreZero() {
		assertEquals(emptyList(), Ochiai.rank(emptyList()))
		assertEquals(listOf(0L), Ochiai.rank(listOf(Run(false, setOf(e("a"))))).map { it.score })
	}

	@Test
	fun runOrderDoesNotChangeTheRanking() {
		val rng = Rng(7)
		val expected = Ochiai.rank(table)
		repeat(20) {
			assertEquals(expected, Ochiai.rank(table.shuffled(rng)))
		}
	}

	@Test
	fun moreFailingCoverageNeverLowersAScore() {
		val rng = Rng(11)
		repeat(200) {
			val runs = List(rng.next(8) + 1) {
				val keys = (0 until 4).filter { rng.next(2) == 0 }.map { e("k$it") }
				Run(rng.next(2) == 0, keys.toSet())
			}
			val x = e("k${rng.next(4)}")
			val before = Ochiai.rank(runs).firstOrNull { it.element == x }?.score ?: 0
			val after = Ochiai.rank(runs + Run(true, setOf(x))).first { it.element == x }.score
			assertTrue(after >= before, "before=$before after=$after runs=$runs")
		}
	}

	@Test
	fun rankingBytesAreIdenticalOnEveryTarget() {
		val json = CanonicalJson.encode(JsonArray(Ochiai.rank(table).map { it.toJson() }))
		assertEquals(GOLDEN_JSON, json)
		assertEquals(GOLDEN_SHA, Sha256.hex(json))
	}

	private companion object {
		const val GOLDEN_JSON =
			"[{\"ef\":2,\"ep\":0,\"nf\":0,\"path\":\"b\",\"score\":1000000,\"value\":\"1\"}," +
			"{\"ef\":2,\"ep\":1,\"nf\":0,\"path\":\"a\",\"score\":816496,\"value\":\"1\"}," +
			"{\"ef\":1,\"ep\":0,\"nf\":1,\"path\":\"e\",\"score\":707106,\"value\":\"1\"}," +
			"{\"ef\":1,\"ep\":0,\"nf\":1,\"path\":\"f\",\"score\":707106,\"value\":\"1\"}," +
			"{\"ef\":1,\"ep\":2,\"nf\":1,\"path\":\"c\",\"score\":408248,\"value\":\"1\"}," +
			"{\"ef\":0,\"ep\":1,\"nf\":2,\"path\":\"d\",\"score\":0,\"value\":\"1\"}]"
		const val GOLDEN_SHA = "c23d866666bf719ac7264de1b816c2ab021896d0fd918d885dc33a7f962058d3"
	}
}

class Rng(seed: Long) : kotlin.random.Random() {
	private var state = seed * 2685821657736338717L + 1442695040888963407L

	override fun nextBits(bitCount: Int): Int {
		state = state xor (state shl 13)
		state = state xor (state ushr 7)
		state = state xor (state shl 17)
		return (state ushr (64 - bitCount)).toInt()
	}

	fun next(bound: Int): Int = nextInt(bound)
}
