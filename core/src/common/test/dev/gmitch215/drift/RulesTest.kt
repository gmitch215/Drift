package dev.gmitch215.drift

import dev.gmitch215.drift.know.Basis
import dev.gmitch215.drift.know.Constraints
import dev.gmitch215.drift.know.Detect
import dev.gmitch215.drift.know.RuleCompiler
import dev.gmitch215.drift.know.Rules
import dev.gmitch215.drift.know.Status
import dev.gmitch215.drift.know.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RulesTest {
	private val seeds = setOf(
		"double-tostring-min-value",
		"git-safe-directory-ownership",
		"go-1-25-container-gomaxprocs",
	)

	@Test
	fun theGeneratedRegistryLoadsTheExampleRules() {
		assertTrue(Rules.ids.containsAll(seeds))
		assertEquals(Rules.ids, Rules.all.map { it.id }.toSet())
		for (id in Rules.ids) assertEquals(id, RuleCompiler.compile(Rules.text(id), id).id)
	}

	@Test
	fun everyLoadedRulePassesTheGate() {
		val report = Constraints.check(Rules.all)
		assertTrue(report.ok, report.violations.joinToString("\n"))
		assertEquals(Rules.ids.size, report.rules)
		assertEquals(Rules.all.count { it.status == Status.PENDING_SCANNER }, report.pendingCount)
	}

	@Test
	fun everyFixtureEvaluatesAsDeclared() {
		var count = 0
		for (rule in Rules.all) {
			for (f in rule.fixtures.positive) {
				count++
				val verdict = Detect.match(rule, f.a, f.b).verdict
				assertEquals(Verdict.MATCH, verdict, "${rule.id}: ${f.name}")
			}
			for (f in rule.fixtures.negative) {
				count++
				val verdict = Detect.match(rule, f.a, f.b).verdict
				assertTrue(verdict != Verdict.MATCH, "${rule.id}: ${f.name}")
			}
		}
		val declared = Rules.all.sumOf { it.fixtures.positive.size + it.fixtures.negative.size }
		assertEquals(declared, count)
	}

	@Test
	fun swappingTheSidesOfADirectionalPositiveFixtureStopsTheMatch() {
		val rule = Rules.all.first { it.id == "go-1-25-container-gomaxprocs" }
		val f = rule.fixtures.positive.single()
		assertEquals(Verdict.NO_MATCH, Detect.match(rule, f.b, f.a).verdict)
	}

	@Test
	fun matchesCarryEvidenceMechanismAndBasis() {
		val go = Rules.all.first { it.id == "go-1-25-container-gomaxprocs" }
		val f = go.fixtures.positive.single()
		val m = Detect.match(go, f.a, f.b).match!!
		val refs = m.evidence.map { it.ref }
		assertEquals(listOf("probe:resources.cgroup-cpu", "tool.go.version"), refs)
		assertEquals(go.mechanism, m.mechanism)
		assertEquals(Basis.INFERRED, m.basis)
		val double = Rules.all.first { it.id == "double-tostring-min-value" }
		val d = double.fixtures.positive.single()
		assertEquals(Basis.VERIFIED, Detect.match(double, d.a, d.b).match!!.basis)
	}
}
