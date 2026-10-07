package dev.gmitch215.drift

import dev.gmitch215.drift.know.Constraints
import dev.gmitch215.drift.know.Rule
import dev.gmitch215.drift.know.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConstraintsTest {
	private val side = """{"attrs":{"tool.cc.version":"%s"}}"""

	private fun fixture(a: String?, b: String?) = """{"name":"f","a":${side(a)},"b":${side(b)}}"""

	private fun side(version: String?) = if (version == null) "{}" else side.replace("%s", version)

	private fun withFixtures(positive: String, negative: String) =
		"fixtures" to """{"positive":[$positive],"negative":[$negative]}"""

	private fun rule(id: String, vararg edits: Pair<String, String?>) =
		demoRule(GCC_14, "id" to "\"$id\"", *edits)

	@Test
	fun aCleanSetPasses() {
		val report = Constraints.check(listOf(rule("one"), rule("two")))
		assertTrue(report.ok, report.violations.toString())
		assertEquals(2, report.rules)
		assertEquals(0, report.pendingCount)
	}

	@Test
	fun duplicateIdsFail() {
		val report = Constraints.check(listOf(rule("same"), rule("same")))
		assertFalse(report.ok)
		assertEquals(listOf("id"), report.violations.map { it.path })
		assertEquals("same", report.violations.single().ruleId)
	}

	@Test
	fun aRuleBuiltWithoutFixtureProvenanceOrMechanismFailsTheGate() {
		val base = rule("bare")
		val bare = base.copy(
			mechanism = "",
			provenance = emptyList(),
			fixtures = base.fixtures.copy(positive = emptyList()),
		)
		val paths = Constraints.check(listOf(bare)).violations.map { it.path }
		assertTrue("mechanism" in paths)
		assertTrue("provenance" in paths)
		assertTrue("fixtures.positive" in paths)
	}

	@Test
	fun aPositiveFixtureThatDoesNotMatchFails() {
		val bad = rule("p", withFixtures(fixture("14.2.0", "14.2.0"), fixture("14.2.0", "14.2.0")))
		val violations = Constraints.check(listOf(bad)).violations
		assertEquals(listOf("fixtures.positive[0]"), violations.map { it.path })
		assertTrue(violations.single().reason.contains("NO_MATCH"))
	}

	@Test
	fun aPositiveFixtureThatIsUnknownFails() {
		val typo = rule("u", withFixtures(fixture(null, "14.2.0"), fixture("14.2.0", "14.2.0")))
		val violations = Constraints.check(listOf(typo)).violations
		assertTrue(violations.single().reason.contains("UNKNOWN"))
	}

	@Test
	fun aNegativeFixtureThatMatchesFailsButUnknownPasses() {
		val hit = fixture("13.2.1", "14.2.0")
		val clash = rule("n", withFixtures(hit, hit))
		val violations = Constraints.check(listOf(clash)).violations
		assertEquals(listOf("fixtures.negative[0]"), violations.map { it.path })
		val unknown = rule("k", withFixtures(fixture("13.2.1", "14.2.0"), fixture(null, "14.2.0")))
		assertTrue(Constraints.check(listOf(unknown)).ok)
	}

	@Test
	fun pendingScannerRulesAreListedNotFailed() {
		val pending = listOf("zeta", "alpha").map { rule(it, "status" to "\"pending-scanner\"") }
		val report = Constraints.check(pending + rule("live"))
		assertTrue(report.ok)
		assertEquals(listOf("alpha", "zeta"), report.pending)
		assertEquals(2, report.pendingCount)
		assertEquals(Status.PENDING_SCANNER, pending.first().status)
	}

	@Test
	fun perRuleViolationsAreReportedTogether() {
		val broken: Rule = rule("b").let { it.copy(title = "", applies = emptyList()) }
		val paths = Constraints.check(listOf(broken)).violations.map { it.path }
		assertEquals(listOf("title", "applies"), paths)
	}
}
