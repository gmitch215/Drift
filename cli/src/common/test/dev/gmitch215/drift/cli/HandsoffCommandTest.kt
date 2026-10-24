package dev.gmitch215.drift.cli

import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.fixtures.HandsoffFixtures
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun fixtureFiles(name: String) = HandsoffFixtures.names
    .filter { it.startsWith("$name/repo/") }
    .associate { "repo/" + it.removePrefix("$name/repo/") to HandsoffFixtures.text(it) }

private fun golden(name: String, case: String) = HandsoffFixtures.text("$name/golden/$case.txt")

class HandsoffCommandTest {
    private val disk = MemoryCaseFiles()

    private fun host(name: String, canRun: Boolean = true): FakeHost {
        val files = fixtureFiles(name)
        val listing = files.keys.filter { it.startsWith("repo/.github/workflows/") }
            .joinToString("") { it.substringAfterLast('/') + "\n" }
        return FakeHost(
            files = files,
            canRun = canRun,
            commands = mapOf(
                listOf("ls", "-1", "repo/.github/workflows") to
                    CommandResult(if (listing.isEmpty()) 2 else 0, listing),
            ),
        )
    }

    private fun plan(host: FakeHost, args: String) =
        DriftCommand(host, disk).test("handsoff plan repo $args".trim())

    @Test
    fun aCleanCheckoutPrintsItsGoldenAndExitsZero() {
        val result = plan(host("node-clean"), "--job test")
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(golden("node-clean", "job"), result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    fun aStopPrintsItsGoldenAndExitsNonZero() {
        val result = plan(host("stop-two-lockfiles"), "")
        assertEquals(1, result.statusCode)
        assertEquals(golden("stop-two-lockfiles", "plain"), result.stdout)
        val answered = plan(host("stop-two-lockfiles"), "--lockfile package-lock.json")
        assertEquals(0, answered.statusCode, answered.stderr)
        assertEquals(golden("stop-two-lockfiles", "answered"), answered.stdout)
    }

    @Test
    fun theOnlyProcessItStartsIsLsOfTheWorkflowDirectory() {
        val host = host("python-clean")
        val result = plan(host, "--job test")
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(listOf(listOf("ls", "-1", "repo/.github/workflows")), host.ran)
        assertEquals(golden("python-clean", "job"), result.stdout)
    }

    @Test
    fun aWorkflowFlagStartsNoProcessAtAll() {
        val host = host("node-clean")
        val result = plan(host, "--job test --workflow .github/workflows/ci.yml")
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(emptyList(), host.ran)
    }

    @Test
    fun aHostThatCannotRunProcessesStillReadsFiles() {
        val host = host("node-clean", canRun = false)
        val result = plan(host, "--workflow .github/workflows/ci.yml --job test")
        assertEquals(0, result.statusCode, result.stderr)
        val blind = plan(host, "")
        assertTrue("could not be listed on this host" in blind.stdout, blind.stdout)
    }

    @Test
    fun nothingIsWrittenUnlessOutNamesAFile() {
        plan(host("node-clean"), "--job test")
        plan(host("stop-no-lockfile"), "")
        assertEquals(emptyMap(), disk.files)
        val result = plan(host("node-clean"), "--job test --out report.txt")
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(setOf("report.txt"), disk.files.keys)
        assertEquals(result.stdout, disk.files.getValue("report.txt"))
    }

    @Test
    fun jsonIsCanonicalAndMatchesTheGolden() {
        val result = plan(host("node-clean"), "--job test --json")
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(HandsoffFixtures.text("node-clean/golden/job.json"), result.stdout)
        val parsed = CanonicalJson.parse(result.stdout) as JsonObject
        assertEquals(JsonBool(true), parsed["ready"])
        assertEquals(result.stdout.trimEnd('\n'), CanonicalJson.encode(parsed))
        val stopped = plan(host("stop-no-lockfile"), "--json")
        assertEquals(1, stopped.statusCode)
        assertEquals(JsonBool(false), (CanonicalJson.parse(stopped.stdout) as JsonObject)["ready"])
    }

    @Test
    fun badFlagValuesAreUsageErrors() {
        val cell = plan(host("node-matrix"), "--job test --cell nodash")
        assertEquals(1, cell.statusCode)
        assertTrue("--cell takes KEY=VALUE, got nodash" in cell.stderr, cell.stderr)
        val runtime = plan(host("node-clean"), "--runtime ruby=3")
        assertEquals(1, runtime.statusCode)
        val message = "--runtime takes node, python, java, php or go"
        assertTrue(message in runtime.stderr, runtime.stderr)
    }

    @Test
    fun aCellFlagAnswersTheMatrixStop() {
        val stopped = plan(host("node-matrix"), "--job test")
        assertEquals(1, stopped.statusCode)
        assertEquals(golden("node-matrix", "stop"), stopped.stdout)
        val cell = "--cell node=20 --cell os=ubuntu-latest"
        val answered = plan(host("node-matrix"), "--job test $cell")
        assertEquals(0, answered.statusCode, answered.stderr)
        assertEquals(golden("node-matrix", "cell"), answered.stdout)
    }

    @Test
    fun aMissingCaseStopsWithoutGuessing() {
        val result = plan(host("node-clean"), "--from-ci .drift/ci/none")
        assertEquals(1, result.statusCode)
        assertTrue("holds no capsule.json" in result.stdout, result.stdout)
    }
}
