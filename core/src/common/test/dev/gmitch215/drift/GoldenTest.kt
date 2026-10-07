package dev.gmitch215.drift

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.math.FixedPoint
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.ProbeResult
import dev.gmitch215.drift.model.ProbeStatus
import dev.gmitch215.drift.model.Stability
import kotlin.test.Test
import kotlin.test.assertEquals

class GoldenTest {
	private val eAcute = Char(0xE9)
	private val euro = Char(0x20AC)
	private val smiley = charArrayOf(Char(0xD83D), Char(0xDE00)).concatToString()
	private val mixed = "tab\there\nnewline \"q\" \\ ${Char(1)} $eAcute $euro $smiley"

	private val capsule = Capsule(
		label = "golden-$eAcute$euro$smiley",
		attributes = listOf(
			Attribute("text.mixed", mixed, "golden", Stability.VOLATILE),
			Attribute("os.arch", "x86_64", "host"),
			Attribute("env.TZ", "UTC", "env"),
		),
		probes = listOf(ProbeResult("text.sort", ProbeStatus.OK, "a\nb\n")),
	)

	@Test
	fun canonicalCapsuleBytesAreIdenticalOnEveryTarget() {
		val expected = "{\"attributes\":[" +
			"{\"path\":\"env.TZ\",\"source\":\"env\",\"stability\":\"static\",\"value\":\"UTC\"}," +
			"{\"path\":\"os.arch\",\"source\":\"host\",\"stability\":\"static\"," +
			"\"value\":\"x86_64\"}," +
			"{\"path\":\"text.mixed\",\"source\":\"golden\",\"stability\":\"volatile\"," +
			"\"value\":\"tab\\there\\nnewline \\\"q\\\" \\\\ \\u0001 $eAcute $euro $smiley\"}]," +
			"\"label\":\"golden-$eAcute$euro$smiley\"," +
			"\"probes\":[{\"hash\":" +
			"\"911169ddaaf146aff539f58c26c489af3b892dff0fe283c1c264c65ae5aa59a2\"," +
			"\"id\":\"text.sort\",\"status\":\"ok\",\"transcript\":\"a\\nb\\n\"}],\"schema\":1}"
		assertEquals(expected, capsule.canonical())
		assertEquals(
			"9a69c08642926bfd932e95a337bda50b268249e4757f8d51d42f23ea3e89edf1",
			capsule.hash(),
		)
	}

	@Test
	fun hashOfLongInputIsIdenticalOnEveryTarget() {
		val expected = "41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3"
		assertEquals(expected, Sha256.hex("a".repeat(1000)))
	}

	@Test
	fun fixedPointResultsAreIdenticalOnEveryTarget() {
		assertEquals(1_098_612, FixedPoint.ln(3_000_000))
		assertEquals(82_085, FixedPoint.exp(-2_500_000))
		assertEquals(4_815_891, FixedPoint.ln(123_456_789))
		assertEquals(1_408_104_848, FixedPoint.exp(7_250_000))
	}

	@Test
	fun canonicalRuleBytesAreIdenticalOnEveryTarget() {
		val rule = demoRule(
			GCC_14,
			"title" to "\"Caf\\u00e9\"",
			"probe" to "\"numeric.parse\"",
		)
		val side13 = "{\"attrs\":{\"tool.cc.version\":\"13.2.1\"},\"probes\":{},\"run\":{}}"
		val side14 = "{\"attrs\":{\"tool.cc.version\":\"14.2.0\"},\"probes\":{},\"run\":{}}"
		val expected = "{\"applies\":[\"compiler\"]," +
			"\"detect\":{\"all\":[{\"a\":\"tool.cc.version\",\"ver\":\"<14\"}," +
			"{\"b\":\"tool.cc.version\",\"ver\":\">=14\"}]},\"difference\":\"d\"," +
			"\"fixtures\":{\"negative\":[{\"a\":$side14,\"b\":$side14,\"name\":\"n\"}]," +
			"\"positive\":[{\"a\":$side13,\"b\":$side14,\"name\":\"p\"}]}," +
			"\"id\":\"demo-rule\",\"mechanism\":\"M.\",\"probe\":\"numeric.parse\"," +
			"\"provenance\":[{\"kind\":\"spec\",\"url\":\"https://example.com/x\"," +
			"\"verified\":\"2026-10-06\"}],\"schema\":1,\"severity\":\"low\"," +
			"\"status\":\"active\",\"symptoms\":[{\"basis\":\"inferred\",\"text\":\"t\"}]," +
			"\"title\":\"Caf$eAcute\"}"
		assertEquals(expected, rule.canonical())
		assertEquals(
			"c15470e1467b7d0928848c53c4839066efe94e5a860603bb3249e6ac5fcc6d19",
			rule.hash(),
		)
	}
}
