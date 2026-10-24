package dev.gmitch215.drift.cli

import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.host.Capability
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.host.TIMED_OUT_EXIT_CODE
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val SHELL_PROBE = listOf("sh", "-c", "exit 0")
private val WRITABLE_PROBE = listOf("sh", "-c", "test -w .")
private val DOCKER_PROBE = listOf("docker", "version", "--format", "{{.Server.Version}}")
private val GIT_PROBE = listOf("git", "--version")

private val HEALTHY = mapOf(
    SHELL_PROBE to CommandResult(0, ""),
    WRITABLE_PROBE to CommandResult(0, ""),
    DOCKER_PROBE to CommandResult(0, "27.0"),
    GIT_PROBE to CommandResult(0, "git version 2.45.0"),
)

private fun Map<List<String>, CommandResult>.without(key: List<String>) = filterKeys { it != key }

class DoctorCommandTest {
    private val user = "zorbulax"
    private val home = "/home/zorbulax"

    private fun host(
        os: String = "linux",
        arch: String = "x86_64",
        capabilities: Set<Capability> = Capability.entries.toSet(),
        commands: Map<List<String>, CommandResult> = HEALTHY,
    ) = FakeHost(
        os = os,
        arch = arch,
        env = mapOf("USER" to user, "HOME" to home),
        capabilities = capabilities,
        commands = commands,
    )

    private fun doctor(host: FakeHost, vararg args: String) =
        DriftCommand(host).test((listOf("doctor") + args).joinToString(" "))

    private fun statuses(json: String): Map<String, String> =
        CanonicalJson.parse(json).obj().require("checks").array().associate {
            val check = it.obj()
            check.require("name").string() to check.require("status").string()
        }

    private fun count(json: String, key: String): Long =
        (CanonicalJson.parse(json).obj().require(key) as JsonInt).value

    @Test
    fun everyCheckOkExitsZero() {
        val result = doctor(host(), "--json")
        assertEquals(0, result.statusCode, result.stdout)
        assertEquals(
            mapOf(
                "platform" to "ok",
                "process" to "ok",
                "working directory" to "ok",
                "docker" to "ok",
                "git" to "ok",
            ),
            statuses(result.stdout),
        )
    }

    @Test
    fun textOutputPrintsStatusCauseAndFixPerCheck() {
        val result = doctor(host(commands = HEALTHY.without(GIT_PROBE)))
        assertEquals(0, result.statusCode)
        val lines = result.stdout.lines()
        assertTrue(lines.any { it.startsWith("ok") && it.endsWith("platform") }, result.stdout)
        val git = lines.indexOfFirst { it.startsWith("warn") && it.endsWith("git") }
        assertTrue(git >= 0, result.stdout)
        assertEquals("      cause: git is not on PATH", lines[git + 1])
        assertTrue(lines[git + 2].startsWith("      fix: install git"), result.stdout)
    }

    @Test
    fun platformWarnsOutsideTheReleaseTargets() {
        assertEquals("warn", statuses(doctor(host(arch = "riscv64"), "--json").stdout)["platform"])
        val intelMac = doctor(host(os = "macos", arch = "x86_64"), "--json")
        assertEquals("warn", statuses(intelMac.stdout)["platform"])
        assertEquals(0, intelMac.statusCode)
    }

    @Test
    fun platformFailsOnAnUnknownSystem() {
        val result = doctor(host(os = "unknown"), "--json")
        assertEquals("fail", statuses(result.stdout)["platform"])
        assertEquals(1, result.statusCode)
    }

    @Test
    fun processFailsWithoutProcessCapability() {
        val result = doctor(host(capabilities = emptySet()), "--json")
        assertEquals("fail", statuses(result.stdout)["process"])
        assertEquals(1, result.statusCode)
    }

    @Test
    fun processFailsWhenTheHostCannotStartOne() {
        val result = doctor(FakeHost(canRun = false), "--json")
        assertEquals("fail", statuses(result.stdout)["process"])
        assertEquals(1, result.statusCode)
    }

    @Test
    fun processFailsOnANonzeroExit() {
        val broken = HEALTHY + (SHELL_PROBE to CommandResult(3, ""))
        val result = doctor(host(commands = broken), "--json")
        assertEquals("fail", statuses(result.stdout)["process"])
        assertEquals(1, result.statusCode)
    }

    @Test
    fun processWarnsWhenItTimesOut() {
        val slow = HEALTHY + (SHELL_PROBE to CommandResult(TIMED_OUT_EXIT_CODE, ""))
        val result = doctor(host(commands = slow), "--json")
        assertEquals("warn", statuses(result.stdout)["process"])
        assertEquals(0, result.statusCode)
    }

    @Test
    fun workingDirectoryWarnsWhenNotWritable() {
        val readOnly = HEALTHY + (WRITABLE_PROBE to CommandResult(1, ""))
        val result = doctor(host(commands = readOnly), "--json")
        assertEquals("warn", statuses(result.stdout)["working directory"])
        assertEquals(0, result.statusCode)
    }

    @Test
    fun workingDirectoryWarnsWithoutAShell() {
        val noShell = HEALTHY.without(WRITABLE_PROBE)
        val result = doctor(host(commands = noShell), "--json")
        assertEquals("warn", statuses(result.stdout)["working directory"])
        assertEquals(0, result.statusCode)
    }

    @Test
    fun dockerWarnsWhenMissing() {
        val result = doctor(host(commands = HEALTHY.without(DOCKER_PROBE)), "--json")
        assertEquals("warn", statuses(result.stdout)["docker"])
        assertEquals(0, result.statusCode)
    }

    @Test
    fun dockerWarnsWhenTheDaemonIsDown() {
        val message = "Cannot connect to the Docker daemon at $home/.docker/run/docker.sock"
        val down = HEALTHY + (DOCKER_PROBE to CommandResult(1, message))
        val result = doctor(host(commands = down), "--json")
        assertEquals("warn", statuses(result.stdout)["docker"])
        assertEquals(0, result.statusCode)
    }

    @Test
    fun dockerWarnsWhenItDoesNotAnswerInTime() {
        val slow = HEALTHY + (DOCKER_PROBE to CommandResult(TIMED_OUT_EXIT_CODE, ""))
        assertEquals("warn", statuses(doctor(host(commands = slow), "--json").stdout)["docker"])
    }

    @Test
    fun gitWarnsWhenMissing() {
        val result = doctor(host(commands = HEALTHY.without(GIT_PROBE)), "--json")
        assertEquals("warn", statuses(result.stdout)["git"])
        assertEquals(0, result.statusCode)
    }

    @Test
    fun jsonCountsFailedAndWarnedChecks() {
        val mixed = HEALTHY.without(GIT_PROBE) + (SHELL_PROBE to CommandResult(1, ""))
        val json = doctor(host(commands = mixed), "--json").stdout
        assertEquals(1L, count(json, "failed"))
        assertEquals(1L, count(json, "warned"))
        val first = CanonicalJson.parse(json).obj().require("checks").array().first().obj()
        assertEquals(setOf("name", "status", "cause", "fix"), first.fields.keys)
    }

    @Test
    fun outputNeverNamesTheUserOrHomeDirectory() {
        val leaky = HEALTHY + (DOCKER_PROBE to CommandResult(1, "$home/.docker denied for $user"))
        val failing = host(os = "unknown", commands = leaky)
        for (output in listOf(doctor(failing).stdout, doctor(failing, "--json").stdout)) {
            assertFalse(user in output, output)
            assertFalse(home in output, output)
        }
    }
}
