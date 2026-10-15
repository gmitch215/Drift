package dev.gmitch215.drift.bench

import dev.gmitch215.drift.fixtures.BenchData
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FreezeTest {
    private val current = mapOf(
        "rules_sha256" to "aa",
        "fit_sha256" to "bb",
        Freeze.HOLDOUT to "t1,t2",
    )

    private val scenarios = (CanonicalJson.parse(BenchData.text("scenarios.json")) as JsonArray)
        .items.map { Scenario.fromJson(it) }

    @Test
    fun aFreezeMatchesTheStateItWasWrittenFrom() {
        val text = Freeze.toJson("abc123", current)
        assertTrue(text.contains("\"commit\":\"abc123\""))
        assertEquals(emptyList(), Freeze.mismatches(text, current))
    }

    @Test
    fun aMismatchNamesTheFieldWithBothValues() {
        val text = Freeze.toJson("abc123", current)
        assertEquals(
            listOf("fit_sha256 (frozen bb, now cc)"),
            Freeze.mismatches(text, current + ("fit_sha256" to "cc")),
        )
        assertEquals(
            listOf("${Freeze.HOLDOUT} (frozen t1,t2, now t1)"),
            Freeze.mismatches(text, current + (Freeze.HOLDOUT to "t1")),
        )
        assertEquals(
            listOf("split_sha256 (frozen missing, now dd)"),
            Freeze.mismatches(text, current + ("split_sha256" to "dd")),
        )
    }

    @Test
    fun theRuleCatalogHashIsStable() {
        assertEquals(64, Freeze.rulesSha256().length)
        assertEquals(Freeze.rulesSha256(), Freeze.rulesSha256())
    }

    @Test
    fun theHoldoutListIsTheNewTemplatesOutsideTheDevBucket() {
        val expected = scenarios.map { it.template }.distinct()
            .filter { it !in SplitRule.LEGACY_TEMPLATES && !SplitRule.isDev(it) }.sorted()
        assertEquals(expected, Freeze.holdoutTemplates(scenarios))
    }

    @Test
    fun aLabOpensTestIdsOnlyThroughUnsealed() {
        val split = SplitRule.splitScenarios(scenarios)
        val test = split.test.first()
        val dev = split.dev.first()
        fun lab(unsealed: Unsealed?) = Lab.parse(
            BenchData.text("scenarios.json"),
            BenchData.text("split.json"),
            unsealed,
        ) { BenchData.text("probed/" + it.replace(test, dev)) }
        assertFailsWith<SealedException> { lab(null).observe(listOf(test)) }
        val seen = lab(Unsealed(split.test)).observe(listOf(test))
        assertEquals(listOf(test), seen.map { it.scenario.id })
        assertFailsWith<SealedException> {
            lab(Unsealed(split.test)).observe(listOf("no-such-scenario"))
        }
    }
}
