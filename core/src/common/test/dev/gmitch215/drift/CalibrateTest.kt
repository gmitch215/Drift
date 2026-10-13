package dev.gmitch215.drift

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.triage.Calibrate
import dev.gmitch215.drift.triage.ConfidenceTable
import dev.gmitch215.drift.triage.Via
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CalibrateTest {
    private fun form(i: Int): String {
        val v = "20.${i % 9}.1"
        return "### Node.js version\n\n$v\n\n### What happened?\n\nIt broke on node $v"
    }

    private fun corpus(count: Int, body: (Int) -> String = ::form): List<String> {
        val issues = (1..count).map { i ->
            val text = CanonicalJson.encode(JsonString(body(i)))
            (if (i == 1) "" else ",") + "{\"repo\":\"t/r\",\"number\":$i,\"body\":$text}"
        }
        return listOf("{\"cutoff\":\"x\",\"issues\":[") + issues + "]"
    }

    @Test
    fun theSplitIsSeededAndStable() {
        val dev = (1L..200L).count { Calibrate.isDev(Calibrate.SEED, "t/r", it) }
        assertTrue(dev in 70..130, "$dev")
        assertEquals(
            Calibrate.isDev("a", "t/r", 7),
            Calibrate.isDev("a", "t/r", 7),
        )
        val other = (1L..200L).count {
            Calibrate.isDev("other", "t/r", it) != Calibrate.isDev("a", "t/r", it)
        }
        assertTrue(other > 0)
    }

    @Test
    fun aPathWithEnoughDevFactsMovesAndTheHeldOutBrierImproves() {
        val result = Calibrate.run(corpus(400), minFacts = 5)
        val pattern = result.bases.getValue(Via.PATTERN)
        assertTrue(pattern > Via.PATTERN.base, "$pattern")
        assertEquals(Via.SECTION.base, result.bases.getValue(Via.SECTION))
        assertTrue(result.before.facts > 20)
        assertTrue(result.after.text < result.before.text, result.text())
        assertEquals(result.toJson(), Calibrate.run(corpus(400), minFacts = 5).toJson())
    }

    @Test
    fun tooFewDevFactsKeepTheDefaults() {
        val result = Calibrate.run(corpus(100))
        assertEquals(Via.entries.associateWith { it.base }, result.bases)
        assertEquals(result.before.text, result.after.text)
    }

    @Test
    fun bodiesThatAreNotFormsAreNotScored() {
        val result = Calibrate.run(corpus(50) { "It broke on node 20.1.1 after the upgrade" })
        assertEquals(0, result.before.facts)
        assertEquals("n/a", result.before.text)
        assertEquals(50, result.issues)
        assertTrue(result.dev.isEmpty())
    }

    @Test
    fun theTableRoundTripsThroughItsParser() {
        val result = Calibrate.run(corpus(400), minFacts = 5)
        val json = CanonicalJson.parse(result.toJson()) as JsonObject
        assertEquals(
            setOf(
                "schema",
                "bases",
                "seed",
                "issues",
                "heldOutFacts",
                "heldOutBrierBefore",
                "heldOutBrierAfter",
            ),
            json.fields.keys,
        )
        assertEquals(result.bases, ConfidenceTable.parse(result.toJson()))
        assertEquals(
            mapOf(Via.PATTERN to 500),
            ConfidenceTable.parse(
                "{\"schema\":1,\"bases\":{\"pattern\":500}}",
            ),
        )
    }

    @Test
    fun aBadTableIsRefusedWithTheReason() {
        fun bad(text: String) = assertFailsWith<IllegalArgumentException> {
            ConfidenceTable.parse(text)
        }.message.orEmpty()
        assertTrue("not JSON" in bad("{nope"))
        assertTrue("object" in bad("[]"))
        assertTrue("schema" in bad("{\"schema\":2,\"bases\":{}}"))
        assertTrue("bases" in bad("{\"schema\":1}"))
        assertTrue("unknown path: x" in bad("{\"schema\":1,\"bases\":{\"x\":500}}"))
        assertTrue("100 to 990" in bad("{\"schema\":1,\"bases\":{\"pattern\":50}}"))
        assertTrue("100 to 990" in bad("{\"schema\":1,\"bases\":{\"pattern\":\"700\"}}"))
    }

    @Test
    fun theTextNamesEveryPathAndTheScores() {
        val text = Calibrate.run(corpus(400), minFacts = 5).text()
        for (via in Via.entries) assertTrue(via.id in text, text)
        assertTrue("Brier" in text)
    }
}
