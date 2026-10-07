package dev.gmitch215.drift.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GateTest {
	@Test
	fun firesFollowsTheShellFunction() {
		val mods = listOf(881, 93, 673, 141, 111, 581, 818, 946)
		for ((trial, mod) in mods.withIndex()) {
			assertTrue(Gate.fires(trial, 12345, mod + 1), "trial $trial below its value")
			assertFalse(Gate.fires(trial, 12345, mod), "trial $trial at its value")
		}
	}

	@Test
	fun theEndsOfTheRangeAreAlwaysAndNever() {
		assertEquals(30, Gate.failures(30, 99, 1000).size)
		assertEquals(emptyList(), Gate.failures(30, 99, 0))
	}

	@Test
	fun failuresAreSortedTrialIndexes() {
		val f = Gate.failures(60, 4242, 500)
		assertEquals(f.sorted(), f)
		assertTrue(f.all { it in 0 until 60 })
	}

	@Test
	fun withinIsTheIntegerNormalBand() {
		assertTrue(Gate.within(20, 1000, 20))
		assertFalse(Gate.within(20, 1000, 19))
		assertTrue(Gate.within(20, 0, 0))
		assertFalse(Gate.within(20, 0, 1))
		assertTrue(Gate.within(40, 500, 20))
		assertTrue(Gate.within(40, 500, 26))
		assertFalse(Gate.within(40, 500, 28))
		assertFalse(Gate.within(40, 500, 12))
	}
}
