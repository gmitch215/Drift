package dev.gmitch215.drift.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PrngTest {
	@Test
	fun splitMixMatchesTheReferenceSequence() {
		val zero = SplitMix64(0)
		assertEquals(0xE220A8397B1DCDAFuL.toLong(), zero.next())
		assertEquals(0x6E789E6AA1B965F4uL.toLong(), zero.next())
		assertEquals(0x06C45D188009454FuL.toLong(), zero.next())
		val other = SplitMix64(1234567)
		assertEquals(0x599ED017FB08FC85uL.toLong(), other.next())
		assertEquals(0x2C73F08458540FA5uL.toLong(), other.next())
	}

	@Test
	fun belowStaysInRangeAndRejectsABadBound() {
		val rng = SplitMix64(7)
		repeat(500) { assertTrue(rng.below(6) in 0..5) }
		assertFailsWith<IllegalArgumentException> { rng.below(0) }
	}

	@Test
	fun shuffleIsAPermutationAndDependsOnTheSeed() {
		val items = (1..20).toList()
		val a = SplitMix64(1).shuffled(items)
		assertEquals(items, a.sorted())
		assertEquals(a, SplitMix64(1).shuffled(items))
		assertNotEquals(a, SplitMix64(2).shuffled(items))
		assertEquals(emptyList(), SplitMix64(1).shuffled(emptyList<Int>()))
	}

	@Test
	fun hashesAreSha256Prefixes() {
		assertEquals(0xBA7816BFuL.toLong(), Hashing.hash32("abc"))
		assertEquals(0xBA7816BF8F01CFEAuL.toLong(), Hashing.hash64("abc"))
	}
}
