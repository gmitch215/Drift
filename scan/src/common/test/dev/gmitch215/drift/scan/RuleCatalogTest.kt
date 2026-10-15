package dev.gmitch215.drift.scan

import dev.gmitch215.drift.fixtures.Fixtures
import dev.gmitch215.drift.fixtures.LayoutFixtures
import dev.gmitch215.drift.fixtures.ProjectFixtures
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.know.Node
import dev.gmitch215.drift.know.Rule
import dev.gmitch215.drift.know.Rules
import dev.gmitch215.drift.know.Status
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.scan.probe.Catalog
import dev.gmitch215.drift.scan.project.ProjectScanner
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
            env = mapOf(
                "DRIFT_RUNNER_LABEL" to "x",
                "LANG" to "C",
                "JAVA_TOOL_OPTIONS" to "-Xmx1g -Djava.net.preferIPv6Addresses=true",
            ),
            files = mapOf(
                "/etc/os-release" to "ID=x\nVERSION_ID=\"1\"\nPRETTY_NAME=\"x\"\n",
                "/sys/fs/cgroup/cpu.max" to "max 100000\n",
                "/proc/self/cgroup" to "0::/\n",
                "/sys/fs/cgroup/memory.max" to "536870912\n",
                "/proc/cmdline" to "ro net.ifnames=0\n",
                "/proc/sys/kernel/apparmor_restrict_unprivileged_userns" to "1\n",
                "/etc/crypto-policies/config" to "DEFAULT\n",
                "/usr/share/zoneinfo/tzdata.zi" to "# version 2024a\n",
            ),
            commands = mapOf(
                listOf("uname", "-r") to CommandResult(0, "1\n"),
                listOf("readlink", "/etc/localtime") to
                    CommandResult(0, "/usr/share/zoneinfo/Etc/UTC\n"),
            ),
        ),
        "catalog",
    ).attributes.map { it.path }.toSet() + Capture.run(
        FakeHost(
            platform = "macosArm64",
            os = "macos",
            commands = mapOf(listOf("sw_vers", "-productVersion") to CommandResult(0, "26.0\n")),
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

    private val projected: Set<String> = ProjectFixtures.names
        .map { it.substringBefore('/') }
        .distinct()
        .flatMap { dir ->
            ProjectScanner.scan(projectFixtureHost(dir)).map { it.path }
        }
        .toSet()

    private val laidOut: Set<String> = LayoutFixtures.names
        .flatMap { Capsule.parse(LayoutFixtures.text(it)).attributes.map { a -> a.path } }
        .toSet()

    private fun emitted(path: String): Boolean {
        val base = path.removeSuffix("*")
        if (path.startsWith("env.")) return capture.any { it.startsWith("env.") }
        return (capture + scanned + projected + laidOut).any {
            it == path ||
                (path.endsWith("*") && it.startsWith(base))
        }
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
            "tool.npm.version",
            "tool.python.gil-disabled",
            "tool.cc.vendor",
            "project.gradle.kotlin.version",
            "tool.openssl.version",
            "runtime.openssl.legacy-provider",
            "cgroup.version",
            "tz.system-zone",
            "runtime.jvm.options",
            "project.composer.json.plugins.unlisted",
            "browser.css.supports.interpolate-size",
            "browser.viewport.width",
        )
        for (path in expected) assertTrue(emitted(path), path)
        assertEquals(false, emitted("tool.nope.version"))
    }
}
