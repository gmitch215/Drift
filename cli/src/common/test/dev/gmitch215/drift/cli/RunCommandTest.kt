package dev.gmitch215.drift.cli

import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import dev.gmitch215.drift.symptom.SymptomKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunCommandTest {
    private val disk = MemoryCaseFiles()

    private fun host(vararg commands: Pair<List<String>, CommandResult>, canRun: Boolean = true) =
        FakeHost(env = mapOf("TZ" to "UTC"), canRun = canRun, commands = mapOf(*commands))

    private fun drift(host: FakeHost, args: String) = DriftCommand(host, disk).test(args)

    private fun file(name: String) = disk.files.getValue("out/$name")

    @Test
    fun theProjectFlagAddsProjectFactsToTheCapsule() {
        val host = FakeHost(
            files = mapOf("go.mod" to "go 1.22.1\n"),
            commands = mapOf(listOf("make") to CommandResult(0, "")),
        )
        assertEquals(0, drift(host, "run --out out --project -- make").statusCode)
        val attrs = Capsule.parse(file("capsule.json")).attributes
        assertTrue(attrs.any { it.path == "project.go.version" && it.value == "1.22.1" })
        assertEquals(0, drift(host, "run --out out --force -- make").statusCode)
        val plain = Capsule.parse(file("capsule.json")).attributes
        assertTrue(plain.none { it.path.startsWith("project.") })
    }

    @Test
    fun passesTheCommandThroughAndKeepsACapsuleDirectory() {
        val host = host(listOf("make", "test") to CommandResult(0, "building\nall green\n"))
        val result = drift(host, "run --out out -- make test")
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals("building\nall green\n", result.stdout)
        assertTrue(listOf("make", "test") in host.ran)
        assertEquals(
            setOf("capsule.json", "run.json", "result.json", "tail.log"),
            disk.files.keys.map { it.removePrefix("out/") }.toSet(),
        )
        val capsule = Capsule.parse(file("capsule.json"))
        assertEquals("run", capsule.label)
        assertTrue(capsule.attributes.any { it.path == "env.TZ" && it.value == "UTC" })
        val run = Run.parse(file("run.json"))
        assertEquals(RunOutcome.PASS, run.outcome)
        assertEquals("make test", run.steps.single().name)
        val execution = CanonicalJson.parse(file("result.json")).obj()
        assertEquals(0L, execution.require("exitCode").long())
        assertEquals("building\nall green", file("tail.log").trimEnd())
        assertTrue("exit 0 after" in result.stderr && "capsule in out" in result.stderr)
    }

    @Test
    fun aFailingCommandKeepsItsExitCodeAndItsSymptoms() {
        val output = "step 1\nError: connection reset by peer\n"
        val host = host(listOf("make", "test") to CommandResult(3, output))
        val result = drift(host, "run --out out --label red -- make test")
        assertEquals(3, result.statusCode)
        assertEquals(output, result.stdout)
        val run = Run.parse(file("run.json"))
        assertEquals(RunOutcome.FAIL, run.outcome)
        assertEquals("red", run.id)
        assertTrue(run.symptoms.any { it.kind == SymptomKind.TRANSPORT })
        assertEquals(3L, CanonicalJson.parse(file("result.json")).obj().require("exitCode").long())
    }

    @Test
    fun theTailIsBoundedAndMasked() {
        val secret = "ghp_" + "z".repeat(30)
        val lines = (1..50).joinToString("\n") { "line $it" } + "\ntoken $secret\n"
        val host = host(listOf("t") to CommandResult(0, lines))
        val result = drift(host, "run --out out --tail-lines 5 -- t")
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(
            "line 47\nline 48\nline 49\nline 50\ntoken <redacted>",
            file("tail.log").trimEnd(),
        )
        val saved = CanonicalJson.parse(file("result.json")).obj()
        assertEquals(JsonBool(true), saved.require("truncated"))
        assertFalse("ghp_" in file("tail.log") + file("run.json"))
    }

    @Test
    fun outputBeyondTheWindowStillYieldsTheLastLines() {
        val lines = (1..40_000).joinToString("\n") { "row $it" } + "\n"
        val host = host(listOf("t") to CommandResult(0, lines))
        val result = drift(host, "run --out out --tail-lines 3 -- t")
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals("row 39998\nrow 39999\nrow 40000", file("tail.log").trimEnd())
        val saved = CanonicalJson.parse(file("result.json")).obj()
        assertEquals(JsonBool(true), saved.require("truncated"))
        assertEquals(lines, result.stdout)
    }

    @Test
    fun aSecretInTheCommandLineIsNotWritten() {
        val secret = "ghp_" + "q".repeat(30)
        val host = host(listOf("deploy", secret) to CommandResult(0, ""))
        drift(host, "run --out out -- deploy $secret")
        val written = disk.files.values.joinToString("\n")
        assertFalse(secret in written)
        assertTrue("deploy <redacted>" in file("result.json"))
    }

    @Test
    fun theStoredCommandLineQuotesArgumentsWithSpaces() {
        val host = host(listOf("sh", "-c", "echo hi") to CommandResult(0, "hi\n"))
        drift(host, "run --out out -- sh -c 'echo hi'")
        assertTrue("sh -c \\\"echo hi\\\"" in file("result.json"), file("result.json"))
    }

    @Test
    fun optionsAfterTheCommandBelongToTheCommand() {
        val host = host(listOf("make", "-j4", "--out", "x") to CommandResult(0, "ok\n"))
        val result = drift(host, "run --out out make -j4 --out x")
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(disk.files.containsKey("out/capsule.json"))
    }

    @Test
    fun anExistingDirectoryNeedsForce() {
        val host = host(listOf("t") to CommandResult(0, "one\n"))
        assertEquals(0, drift(host, "run --out out -- t").statusCode)
        val again = drift(host, "run --out out -- t")
        assertEquals(1, again.statusCode)
        assertTrue("already holds a capsule directory" in again.stderr, again.stderr)
        val second = host(listOf("t") to CommandResult(0, "two\n"))
        val forced = drift(second, "run --out out --force -- t")
        assertEquals(0, forced.statusCode, forced.stderr)
        assertEquals("two", file("tail.log").trim())
    }

    @Test
    fun aCommandThatCannotStartAndBadOptionsFailBeforeWriting() {
        val missing = drift(host(canRun = false), "run --out out -- nope")
        assertEquals(1, missing.statusCode)
        assertTrue("cannot start command: nope" in missing.stderr, missing.stderr)
        assertEquals(1, drift(host(), "run --out out --tail-lines 0 -- t").statusCode)
        assertEquals(1, drift(host(), "run --out out").statusCode)
        assertTrue(disk.files.isEmpty())
    }

    @Test
    fun unwritableOutputIsReported() {
        val refusing = object : dev.gmitch215.drift.case.CaseFiles {
            override fun read(path: String): String? = null

            override fun write(path: String, text: String) = false
        }
        val host = host(listOf("t") to CommandResult(0, "x\n"))
        val result = DriftCommand(host, refusing).test("run --out out -- t")
        assertEquals(1, result.statusCode)
        assertTrue("cannot write capsule file: out/capsule.json" in result.stderr, result.stderr)
    }

    @Test
    fun theDurationIsNotNegative() {
        val host = host(listOf("t") to CommandResult(0, ""))
        drift(host, "run --out out -- t")
        val duration = CanonicalJson.parse(file("result.json")).obj().require("durationMs").long()
        assertTrue(duration >= 0)
    }
}
