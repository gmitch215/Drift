package dev.gmitch215.drift

import dev.gmitch215.drift.fixtures.SetFixtures
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Stability
import dev.gmitch215.drift.set.SetCapsule
import dev.gmitch215.drift.set.SetDiagnosis
import dev.gmitch215.drift.set.Split
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetDiagnosisTest {
    private fun fixture(dir: String): List<SetCapsule> = SetFixtures.names.sorted()
        .filter { it.startsWith("$dir/") && it.endsWith(".json") }
        .map {
            val name = it.substringAfterLast('/').removeSuffix(".json")
            SetCapsule(name, name.startsWith("fail"), Capsule.parse(SetFixtures.text(it)))
        }

    private fun capsule(failing: Boolean, vararg attrs: Pair<String, String>) = SetCapsule(
        "c",
        failing,
        Capsule("c", attrs.map { Attribute(it.first, it.second, "test") }),
    )

    @Test
    fun theSeparatingKeyRanksFirstOnTheToolchainSet() {
        val result = SetDiagnosis.of(fixture("toolchain"))
        assertEquals(2, result.failing)
        assertEquals(2, result.passing)
        val top = result.splits.first()
        assertEquals("tool.node.version", top.path)
        assertEquals("22.4.0", top.value)
        assertEquals(1_000_000, top.score)
        assertTrue(top.separates)
        assertEquals(listOf(top), result.separating)
    }

    @Test
    fun partialSplitsFollowWithCountsAndNothingElseSeparates() {
        val result = SetDiagnosis.of(fixture("toolchain"))
        val byKey = result.splits.associateBy { it.path to it.value }
        assertEquals(1, byKey.getValue("tool.npm.version" to "10.8.1").failing)
        assertEquals(0, byKey.getValue("tool.npm.version" to "10.8.1").passing)
        assertEquals(707_106, byKey.getValue("tool.npm.version" to "10.8.1").score)
        assertEquals(707_106, byKey.getValue("cpu.count" to "16").score)
        assertEquals(500_000, byKey.getValue("env.TZ" to "UTC").score)
        assertEquals(1, byKey.getValue("env.TZ" to "UTC").passing)
        assertTrue(result.splits.drop(1).none { it.separates })
        val scores = result.splits.map { it.score }
        assertEquals(scores.sortedDescending(), scores)
    }

    @Test
    fun valuesEveryCapsuleHoldAndValuesNoFailingCapsuleHoldAreLeftOut() {
        val paths = SetDiagnosis.of(fixture("toolchain")).splits.map { it.path }.toSet()
        assertTrue("os.version" !in paths && "env.LANG" !in paths)
        val values = SetDiagnosis.of(fixture("toolchain")).splits.map { it.path to it.value }
        assertTrue(("tool.node.version" to "20.11.1") !in values)
        assertTrue(("cpu.count" to "4") !in values)
    }

    @Test
    fun theBenchProxyRepeatsRankTheProxyKeyFirst() {
        val result = SetDiagnosis.of(fixture("proxy"))
        val top = result.splits.first()
        assertEquals("env.HTTP_PROXY", top.path)
        assertEquals("http://127.0.0.1:9", top.value)
        assertEquals(listOf(top), result.separating)
        val decoys = result.splits.filter { it.path == "env.NO_PROXY" }
        assertEquals(2, decoys.size)
        assertTrue(decoys.all { it.failing == 1 && it.passing == 0 && !it.separates })
    }

    @Test
    fun theOrderOfTheCapsulesDoesNotChangeTheResult() {
        val set = fixture("toolchain")
        assertEquals(SetDiagnosis.of(set), SetDiagnosis.of(set.reversed()))
        assertEquals(SetDiagnosis.of(set), SetDiagnosis.of(set.shuffled(kotlin.random.Random(7))))
    }

    @Test
    fun aSetWithOnlyOneSideHasNoSplits() {
        val failing = fixture("toolchain").filter { it.failing }
        val result = SetDiagnosis.of(failing)
        assertEquals(2, result.failing)
        assertEquals(0, result.passing)
        assertTrue(result.splits.isEmpty())
        assertTrue(SetDiagnosis.of(emptyList()).splits.isEmpty())
    }

    @Test
    fun volatileAttributesAreNotScored() {
        val pass = SetCapsule(
            "p",
            false,
            Capsule(
                "p",
                listOf(
                    Attribute("env.GITHUB_RUN_ID", "1", "t"),
                    Attribute("env.TZ", "UTC", "t"),
                    Attribute("clock.now", "1", "t", Stability.VOLATILE),
                ),
            ),
        )
        val fail = SetCapsule(
            "f",
            true,
            Capsule(
                "f",
                listOf(
                    Attribute("env.GITHUB_RUN_ID", "2", "t"),
                    Attribute("env.TZ", "Asia/Tokyo", "t"),
                    Attribute("clock.now", "2", "t", Stability.VOLATILE),
                ),
            ),
        )
        assertEquals(listOf("env.TZ"), SetDiagnosis.of(listOf(pass, fail)).splits.map { it.path })
    }

    @Test
    fun aKeyAbsentFromEveryPassingCapsuleSeparates() {
        val result = SetDiagnosis.of(
            listOf(
                capsule(false, "a" to "1"),
                capsule(true, "a" to "1", "proxy" to "on"),
                capsule(true, "a" to "1", "proxy" to "on"),
            ),
        )
        assertEquals(listOf(Split("proxy", "on", 2, 0, 1_000_000, true)), result.splits)
    }

    @Test
    fun theJsonCarriesTheCountsAndEveryRow() {
        val result = SetDiagnosis.of(fixture("toolchain"))
        val json = CanonicalJson.encode(result.toJson())
        assertTrue(
            json.startsWith("{\"capsules\":[{\"name\":\"fail-1\",\"outcome\":\"fail\"}"),
            json,
        )
        assertTrue("\"failing\":2,\"passing\":2,\"splits\":[{" in json, json)
        assertTrue("\"path\":\"tool.node.version\"" in json, json)
        assertEquals(result.splits.size, Regex("\"separates\"").findAll(json).count())
    }

    @Test
    fun theTextNamesTheSeparatingKeyFirstAndCapsTheRows() {
        val result = SetDiagnosis.of(fixture("toolchain"))
        val lines = result.text(2).lines()
        assertEquals("set: 2 failing, 2 passing, 1 separating", lines[0])
        assertEquals(
            "1. tool.node.version = 22.4.0  failing 2/2  passing 0/2  score 1.000000  separates",
            lines[1],
        )
        assertTrue(lines[2].endsWith("score 0.707106"), lines[2])
        assertEquals(4, lines.size)
        assertEquals("", lines.last())
        assertEquals(result.splits.size + 2, result.text().lines().size)
    }
}
