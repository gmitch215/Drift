package dev.gmitch215.drift

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonTest {
	private val eAcute = Char(0xE9)

	@Test
	fun sortsKeysAndRoundTrips() {
		val value = obj(
			"b" to JsonArray(listOf(JsonInt(-5), JsonBool(true), JsonNull)),
			"a" to JsonString("line\nbreak \"quoted\" \\ \u0001 $eAcute"),
		)
		val text = CanonicalJson.encode(value)
		assertEquals(
			"{\"a\":\"line\\nbreak \\\"quoted\\\" \\\\ \\u0001 $eAcute\",\"b\":[-5,true,null]}",
			text,
		)
		assertEquals(value, CanonicalJson.parse(text))
		assertEquals(text, CanonicalJson.encode(CanonicalJson.parse(text)))
	}

	@Test
	fun parserAcceptsWhitespaceAndEscapes() {
		val parsed = CanonicalJson.parse(" { \"k\" : [ 1 , \"\\u0041\\/\" ] } ")
		assertEquals(obj("k" to JsonArray(listOf(JsonInt(1), JsonString("A/")))), parsed)
	}

	@Test
	fun rejectsMalformedInput() {
		val inputs = listOf(
			"", "{", "[1,", "{\"a\":1.5}", "{\"a\":1e3}",
			"{\"a\":1,\"a\":2}", "[1] x", "\"abc", "tru", "{1:2}",
		)
		for (bad in inputs) {
			assertFailsWith<JsonException>(bad) { CanonicalJson.parse(bad) }
		}
	}

	@Test
	fun truncatedInputNeverThrowsAnythingElse() {
		val full = "{\"a\":[1,2,{\"b\":\"c\\n\"}],\"d\":null}"
		for (n in 0 until full.length) {
			runCatching { CanonicalJson.parse(full.substring(0, n)) }
				.onFailure { if (it !is JsonException) throw it }
		}
	}

	@Test
	fun encoderRejectsLoneSurrogates() {
		for (bad in listOf(
			Char(0xD83D).toString(),
			Char(0xDE00).toString(),
			"a" + Char(0xD83D) + "b",
		)) {
			assertFailsWith<JsonException> { CanonicalJson.encode(JsonString(bad)) }
		}
		val pair = charArrayOf(Char(0xD83D), Char(0xDE00)).concatToString()
		assertEquals("\"$pair\"", CanonicalJson.encode(JsonString(pair)))
	}

	@Test
	fun nestingPastTheLimitIsATypedErrorAndTheLimitItselfParses() {
		val deep = "[".repeat(100_000) + "]".repeat(100_000)
		assertFailsWith<JsonException> { CanonicalJson.parse(deep) }
		assertFailsWith<JsonException> { CanonicalJson.parse("{\"a\":".repeat(100_000)) }
		val ok = "[".repeat(256) + "]".repeat(256)
		assertEquals(ok, CanonicalJson.encode(CanonicalJson.parse(ok)))
		assertFailsWith<JsonException> {
			CanonicalJson.parse("[".repeat(257) + "]".repeat(257))
		}
	}
}
