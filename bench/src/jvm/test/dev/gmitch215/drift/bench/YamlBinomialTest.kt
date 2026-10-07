package dev.gmitch215.drift.bench

import dev.gmitch215.drift.json.CanonicalJson
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YamlBinomialTest {
	@Test
	fun yamlScalarsStayStringsAndStructureIsKept() {
		val json = Yaml.parse("a: 1\nb:\n  - x\n  - true\nc: {d: null}\n", "t.yml")
		assertEquals(
			"{\"a\":\"1\",\"b\":[\"x\",\"true\"],\"c\":{\"d\":\"null\"}}",
			CanonicalJson.encode(json),
		)
	}

	@Test
	fun malformedYamlIsATypedError() {
		for (bad in listOf("a: [1", "a: 1\na: 2\n", "\t- x", "a:\n")) {
			val e = assertFailsWith<TemplateException> { Yaml.parse(bad, "t.yml") }
			assertEquals(Problem.BAD_VALUE, e.problem)
			assertTrue(e.message!!.startsWith("t.yml"))
		}
	}

	@Test
	fun theTailsAreExact() {
		val t = Binomial.tails(10, 500, 3)
		assertEquals(t.denominator * BigInteger.valueOf(176), t.lower * BigInteger.valueOf(1024))
		assertEquals(t.denominator * BigInteger.valueOf(968), t.upper * BigInteger.valueOf(1024))
		assertEquals(
			BigInteger.valueOf(
				1024,
			).multiply(BigInteger.valueOf(1000).pow(10).divide(BigInteger.valueOf(1024))),
			t.denominator,
		)
		assertEquals("0.1719", t.smallest())
	}

	@Test
	fun theEndsOfTheRateAcceptOnlyTheEnds() {
		assertTrue(Binomial.tails(20, 1000, 20).accepts())
		assertFalse(Binomial.tails(20, 1000, 19).accepts())
		assertTrue(Binomial.tails(20, 0, 0).accepts())
		assertFalse(Binomial.tails(20, 0, 1).accepts())
	}

	@Test
	fun theAcceptanceBandIsTwoSidedAtOnePartInTwoThousand() {
		assertTrue(Binomial.tails(50, 350, 17).accepts())
		assertFalse(Binomial.tails(50, 350, 33).accepts())
		assertFalse(Binomial.tails(50, 350, 4).accepts())
		assertEquals("1", Binomial.tails(20, 0, 0).smallest())
	}

	@Test
	fun everyCountTheGeneratorAcceptsPassesTheExactTest() {
		for (n in listOf(10, 20, 40, 50)) {
			for (rate in 1..999 step 7) {
				for (k in 0..n) {
					if (Gate.within(n, rate, k)) {
						assertTrue(Binomial.tails(n, rate, k).accepts(), "n=$n rate=$rate k=$k")
					}
				}
			}
		}
	}
}
