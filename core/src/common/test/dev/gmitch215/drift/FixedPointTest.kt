package dev.gmitch215.drift

import dev.gmitch215.drift.math.FixedPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FixedPointTest {
	@Test
	fun lnKnownValues() {
		assertEquals(0, FixedPoint.ln(FixedPoint.ONE))
		assertEquals(693_147, FixedPoint.ln(2 * FixedPoint.ONE))
		assertEquals(2_302_585, FixedPoint.ln(10 * FixedPoint.ONE))
		assertEquals(-693_147, FixedPoint.ln(FixedPoint.ONE / 2))
		assertEquals(-4_605_170, FixedPoint.ln(10_000))
	}

	@Test
	fun expKnownValues() {
		assertEquals(FixedPoint.ONE, FixedPoint.exp(0))
		assertEquals(2_718_282, FixedPoint.exp(FixedPoint.ONE))
		assertEquals(367_879, FixedPoint.exp(-FixedPoint.ONE))
		assertEquals(7_389_056, FixedPoint.exp(2 * FixedPoint.ONE))
	}

	@Test
	fun lnExpRoundTrip() {
		for (x in listOf(500_000L, 1_234_567L, 3_000_000L, 9_999_999L, 123_456_789L)) {
			val back = FixedPoint.exp(FixedPoint.ln(x))
			assertTrue(back - x in -(x / 100_000 + 2)..(x / 100_000 + 2), "x=$x back=$back")
		}
	}

	@Test
	fun domainErrors() {
		assertFailsWith<IllegalArgumentException> { FixedPoint.ln(0) }
		assertFailsWith<IllegalArgumentException> { FixedPoint.exp(21 * FixedPoint.ONE) }
	}

	@Test
	fun formatting() {
		assertEquals("0.000000", FixedPoint.format(0))
		assertEquals("1.500000", FixedPoint.format(1_500_000))
		assertEquals("-0.000042", FixedPoint.format(-42))
		assertEquals("12.000001", FixedPoint.format(12_000_001))
	}
}
