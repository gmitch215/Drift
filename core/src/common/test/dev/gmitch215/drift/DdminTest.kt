package dev.gmitch215.drift

import dev.gmitch215.drift.delta.Ddmin
import dev.gmitch215.drift.delta.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DdminTest {
	private val items = (0..7).toList()

	private fun needs(vararg needed: Int): (List<Int>) -> Verdict = { subset ->
		if (subset.containsAll(needed.toList())) Verdict.FAIL else Verdict.PASS
	}

	@Test
	fun findsASingleItem() {
		val result = Ddmin.run(items, needs(3))
		assertEquals(listOf(3), result.minimal)
		assertEquals(6, result.calls)
		assertEquals(
			listOf(items, listOf(0, 1, 2, 3), listOf(0, 1), listOf(2, 3), listOf(2), listOf(3)),
			result.sequence,
		)
	}

	@Test
	fun findsAConjunctionOfTwo() {
		assertEquals(listOf(1, 6), Ddmin.run(items, needs(1, 6)).minimal)
	}

	@Test
	fun findsAnInterferingPairThatFailsOnlyTogether() {
		val result = Ddmin.run(items, needs(2, 5))
		assertEquals(listOf(2, 5), result.minimal)
		assertEquals(0, result.unresolved)
	}

	@Test
	fun keepsTheWholeInputWhenEveryItemIsNeeded() {
		assertEquals(items, Ddmin.run(items, needs(*items.toIntArray())).minimal)
	}

	@Test
	fun aSingleItemInputIsReturnedWithoutFurtherCalls() {
		val result = Ddmin.run(listOf(9), needs(9))
		assertEquals(listOf(9), result.minimal)
		assertEquals(1, result.calls)
	}

	@Test
	fun theFullInputMustFail() {
		assertFailsWith<IllegalArgumentException> { Ddmin.run(items, needs(99)) }
	}

	@Test
	fun unresolvedIsNeverTreatedAsFail() {
		val full = items
		val result = Ddmin.run(items) { if (it == full) Verdict.FAIL else Verdict.UNRESOLVED }
		assertEquals(items, result.minimal)
		assertTrue(result.unresolved > 0)
	}

	@Test
	fun routesAroundUnresolvedSubsets() {
		val result = Ddmin.run(items) {
			when {
				!it.contains(3) -> Verdict.PASS
				it.size == 4 -> Verdict.UNRESOLVED
				else -> Verdict.FAIL
			}
		}
		assertEquals(listOf(3), result.minimal)
		assertEquals(1, result.unresolved)
	}

	@Test
	fun repeatedSubsetsAreServedFromTheMemo() {
		val seen = mutableListOf<List<Int>>()
		val result = Ddmin.run(items) {
			seen.add(it)
			needs(2, 5)(it)
		}
		assertEquals(seen.size, result.calls)
		assertEquals(seen.size, seen.toSet().size)
	}

	@Test
	fun callSequencesAreIdenticalOnEveryTarget() {
		val single = Ddmin.run(items, needs(2, 5))
		assertEquals(listOf(2, 5), single.minimal)
		assertEquals(GOLDEN_SEQUENCE, single.sequence.joinToString(";") { it.joinToString(",") })
	}

	@Test
	fun randomMonotonePredicatesGiveAMinimalFailingSet() {
		val rng = Rng(2024)
		repeat(300) {
			val n = rng.next(9) + 4
			val culprits = List(rng.next(3) + 1) {
				(0 until n).shuffled(rng).take(rng.next(3) + 1).toSet()
			}
			val test: (List<Int>) -> Verdict = { subset ->
				if (culprits.any { subset.containsAll(it) }) Verdict.FAIL else Verdict.PASS
			}
			val result = Ddmin.run((0 until n).toList(), test)
			assertEquals(Verdict.FAIL, test(result.minimal), "culprits=$culprits")
			for (i in result.minimal.indices) {
				val smaller = result.minimal.filterIndexed { j, _ -> j != i }
				assertEquals(Verdict.PASS, test(smaller), "culprits=$culprits")
			}
			assertTrue(culprits.any { it == result.minimal.toSet() })
		}
	}

	private companion object {
		const val GOLDEN_SEQUENCE = "0,1,2,3,4,5,6,7;0,1,2,3;4,5,6,7;0,1;2,3;4,5;6,7;2,3,4,5,6,7;" +
			"2,3,6,7;2,3,4,5;2;3;4;5;3,4,5;2,4,5;2,5"
	}
}
