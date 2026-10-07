package dev.gmitch215.drift.scan

import dev.gmitch215.drift.fixtures.Fixtures
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.know.Node
import dev.gmitch215.drift.know.Rule
import dev.gmitch215.drift.know.Rules
import dev.gmitch215.drift.know.Status
import dev.gmitch215.drift.scan.probe.Catalog
import dev.gmitch215.drift.scan.scanner.Scanners
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleCatalogTest {
	private fun paths(node: Node): Set<String> = when (node) {
		is Node.AllOf -> node.nodes.flatMap(::paths).toSet()
		is Node.AnyOf -> node.nodes.flatMap(::paths).toSet()
		is Node.Not -> paths(node.node)
		is Node.On -> setOf(node.path)
		is Node.Cross -> setOf(node.path)
		is Node.Changed -> setOf(node.path)
	}

	private val capture = Capture.run(
		FakeHost(
			platform = "jvm",
			env = mapOf("DRIFT_RUNNER_LABEL" to "x", "LANG" to "C"),
			files = mapOf("/etc/os-release" to "ID=x\nVERSION_ID=\"1\"\nPRETTY_NAME=\"x\"\n"),
			commands = mapOf(
				listOf("uname", "-r") to CommandResult(0, "1\n"),
			),
		),
		"catalog",
	).attributes.map { it.path }.toSet()

	private val tools = Scanners.all.map { it.tool }.toSet()

	private val scanned: Set<String> = Fixtures.names
		.filter { it.endsWith(".txt") && it.substringBefore('/') in tools }
		.flatMap {
			val scanner = Scanners.all.single { s -> s.tool == it.substringBefore('/') }
			Scanners.attributes(scanner, Fixtures.text(it)).map { a -> a.path }
		}
		.toSet()

	private fun emitted(path: String): Boolean {
		val base = path.removeSuffix("*")
		if (path.startsWith("env.")) return capture.any { it.startsWith("env.") }
		return (capture + scanned).any { it == path || (path.endsWith("*") && it.startsWith(base)) }
	}

	private fun missing(rule: Rule): List<String> = paths(rule.detect)
		.filterNot { it.startsWith("probe:") || it.startsWith("run:") }
		.filterNot(::emitted)
		.sorted()

	@Test
	fun everyProbeARuleNamesIsInTheCatalog() {
		val ids = Catalog.all.map { it.id }.toSet()
		for (rule in Rules.all) {
			rule.probe?.let { assertTrue(it in ids, "${rule.id} names unknown probe $it") }
			for (path in paths(rule.detect).filter { it.startsWith("probe:") }) {
				val id = path.removePrefix("probe:")
				assertTrue(id in ids, "${rule.id} reads unknown probe $id")
			}
		}
	}

	@Test
	fun activeRulesReadOnlyEmittedAttributesAndPendingOnesAreListed() {
		val pending = Rules.all.filter { it.status == Status.PENDING_SCANNER }
		for (rule in pending) println("pending-scanner: ${rule.id} missing ${missing(rule)}")
		println("rules ${Rules.all.size}, pending ${pending.size}")
		for (rule in Rules.all) {
			val gaps = missing(rule)
			if (rule.status == Status.ACTIVE) {
				assertTrue(gaps.isEmpty(), "${rule.id} is active but reads $gaps")
			} else {
				assertTrue(gaps.isNotEmpty(), "${rule.id} is pending but every path is emitted")
			}
		}
	}

	@Test
	fun theEmittedPathsIncludeTheOnesTheNewScannersAdd() {
		val expected = setOf(
			"tool.libc.family",
			"tool.python.externally-managed",
			"tool.visualstudio.version",
			"runtime.platform",
			"ci.runner.label",
		)
		for (path in expected) assertTrue(emitted(path), path)
		assertEquals(false, emitted("tool.nope.version"))
	}
}
