package dev.gmitch215.drift.bench

import dev.gmitch215.drift.fixtures.BenchData
import dev.gmitch215.drift.fixtures.BenchUpgrade
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.upgrade.Group
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UpgradeBenchTest {
    private val lab = Lab.parse(
        BenchData.text("scenarios.json"),
        BenchData.text("split.json"),
    ) { BenchData.text("capsules/$it") }

    private val results = UpgradeBench.runDev(lab)

    @Test
    fun theCommittedRecordEqualsARegeneration() {
        assertEquals(BenchUpgrade.text("upgrade.json"), UpgradeBench.toJson(lab, results))
    }

    @Test
    fun everyDevMigrationScenarioGivesABreakAndAControlItem() {
        val dev = UpgradeBench.scenarios(lab).filter { it.id in lab.dev }
        assertEquals(dev.size * 2, results.size)
        assertEquals(dev.map { it.id }.sorted(), results.map { it.item.scenario }.distinct())
        assertEquals(results.size / 2, results.count { it.item.breaks })
        val counts = UpgradeBench.counts(lab)
        assertEquals(dev.size, counts.getValue("dev"))
        assertEquals(UpgradeBench.scenarios(lab).size, counts.values.sum())
        println("upgrade scenarios by split: $counts, dev items ${results.size}")
    }

    @Test
    fun aBreakItemMovesThePassingCapsuleToTheFailingValueAndAControlGoesBack() {
        for (r in results) {
            val i = r.item
            val (green, red) = lab.pair(i.scenario)
            val path = i.target.key
            val from = if (i.breaks) green else red
            val to = if (i.breaks) red else green
            assertEquals(from.label, i.input.label)
            assertEquals(to.attributes.first { it.path == path }.value, i.target.value)
            assertTrue(i.input.attributes.first { it.path == path }.value != i.target.value)
        }
        assertTrue(results.filter { it.item.breaks }.all { it.item.downgrade })
        assertTrue(results.filter { !it.item.breaks }.none { it.item.downgrade })
    }

    @Test
    fun testScenariosAreRefusedWithoutTheUnsealProof() {
        val test = UpgradeBench.scenarios(lab).first { it.id !in lab.dev }.id
        assertFailsWith<SealedException> { lab.pair(test) }
        assertFailsWith<SealedException> { UpgradeBench.items(lab, listOf(test)) }
        assertFailsWith<IllegalArgumentException> {
            UpgradeBench.runTest(lab, Unsealed(emptyList()))
        }
    }

    @Test
    fun theStrataSplitTheItemsByWhetherARuleNamesTheMeasuredFailure() {
        val score = UpgradeBench.score(results)
        val (all, matched, unmatched) = score.strata
        assertEquals(all.breaks, matched.breaks + unmatched.breaks)
        assertEquals(all.controls, matched.controls + unmatched.controls)
        assertTrue(
            results.first {
                it.item.scenario.contains("list-getfirst")
            }.item.explained.not(),
        )
        assertTrue(results.first { it.item.scenario.contains("python-copy") }.item.explained)
        print(score.report("dev"))
    }

    @Test
    fun aCaptureWithoutProjectFactsNeverConfirmsAnApiRule() {
        val all = UpgradeBench.score(results).strata.first()
        for (f in all.flags) {
            assertEquals(0, f.confirmed, f.flag)
            assertEquals(0, f.recall.count, f.flag)
        }
    }

    @Test
    fun theApiRuleIsWillBreakOnceTheCaptureHoldsTheProjectFact() {
        val facts = mapOf(
            "migration-python-copy-1" to "project.python.calls.copy.replace",
            "migration-php-json-validate-1" to "project.php.calls.json_validate",
            "migration-node-array-fromasync-1" to "project.js.calls.Array.fromAsync",
            "migration-jdk-list-getfirst-1" to "project.java.calls.sequenced-first-last",
        )
        val rules = mapOf(
            "migration-python-copy-1" to "python-3-13-copy-replace-added",
            "migration-php-json-validate-1" to "php-8-3-json-validate-added",
            "migration-node-array-fromasync-1" to "node-22-array-fromasync-added",
            "migration-jdk-list-getfirst-1" to "jdk-21-sequenced-collection-get-first-last-added",
        )
        val items = UpgradeBench.items(lab, facts.keys.toList()).filter { it.breaks }
        assertEquals(facts.size, items.size)
        for (item in items) {
            val extra = Attribute(facts.getValue(item.scenario), "true", "test")
            val withFact = UpgradeItem(
                item.scenario,
                item.template,
                item.move,
                item.downgrade,
                item.input.copy(attributes = item.input.attributes + extra),
                item.target,
                item.signature,
                item.explained,
            )
            val ids = UpgradeBench.run(listOf(withFact)).single().findings(Group.WILL_BREAK.id)
                .map { it.rule.id }
            assertTrue(rules.getValue(item.scenario) in ids, "${item.scenario}: $ids")
        }
    }

    @Test
    fun anEmptyResultListScoresAsZeros() {
        val score = UpgradeBench.score(emptyList())
        assertEquals(0, score.scenarios)
        assertTrue(score.report("none").contains("n/a (0 of 0)"))
        assertTrue(score.strata.all { s -> s.flags.all { it.recall.total == 0 } })
    }
}
