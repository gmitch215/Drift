package dev.gmitch215.drift.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.cli.command.RunCommand
import dev.gmitch215.drift.host.Capability
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.host.ResourceUsage
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.lab.TrialGate
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import dev.gmitch215.drift.symptom.SymptomKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

private class Scripted(val exit: Int, val wallMs: Long, val usage: ResourceUsage? = null)

private class ScriptedHost(
    private val clock: TestTimeSource,
    private val script: List<Scripted>,
    private val files: Map<String, String> = emptyMap(),
) : Host {
    var calls = 0

    override val platform = "fake"
    override val capabilities = Capability.entries.toSet()
    override val os = "linux"
    override val arch = "x86_64"

    override fun env() = mapOf("TZ" to "UTC")

    override fun readText(path: String) = files[path]

    override fun run(argv: List<String>): CommandResult? = run(argv, null) {}

    override fun run(
        argv: List<String>,
        timeoutMillis: Long?,
        onOutput: (String) -> Unit,
    ): CommandResult? {
        if (argv != listOf("t")) return CommandResult(127, "")
        val step = script[calls++]
        clock += step.wallMs.milliseconds
        onOutput("run $calls\n")
        return CommandResult(step.exit, "", step.usage)
    }
}

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

    private fun steps(exits: List<Int>, walls: List<Long>, usage: ResourceUsage? = null) =
        exits.indices.map { Scripted(exits[it], walls[it], usage) }

    private fun repeated(
        args: String,
        script: List<Scripted>,
        files: Map<String, String> = emptyMap(),
    ): Pair<ScriptedHost, CliktCommandTestResult> {
        val clock = TestTimeSource()
        val host = ScriptedHost(clock, script, files)
        return host to RunCommand(host, disk, clock).test(args)
    }

    @Test
    fun repeatRecordsEveryRunAndSkipsTheWarmUps() {
        val usage = ResourceUsage(80, 10, 4096)
        val script = steps(List(7) { 0 }, listOf(500, 500, 100, 101, 102, 103, 104), usage)
        val clock = TestTimeSource()
        val host = ScriptedHost(clock, script)
        val command = RunCommand(host, disk, clock)
        val result = command.test("--out out --repeat 5 --warmup 2 -- t")
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(7, host.calls)
        assertEquals("run 7\n", result.stdout)
        val run = Run.parse(file("run.json"))
        assertEquals(listOf<Long>(100, 101, 102, 103, 104), run.samples.map { it.wallMs })
        assertEquals(listOf(80L), run.samples.map { it.userMs }.distinct())
        assertEquals(4096L, run.samples.first().peakKb)
        assertEquals(RunOutcome.PASS, run.outcome)
        assertEquals(102L, run.steps.single().durationMs)
        assertTrue("drift: 5 runs, 5 passed (100%)" in result.stderr, result.stderr)
        assertTrue("wall time median 102 ms, min 100 ms, max 104 ms" in result.stderr)
        assertTrue("cpu time median 80 ms user, 10 ms system" in result.stderr)
        assertTrue("peak memory median 4096 KiB" in result.stderr)
        assertTrue("history state" !in result.stderr)
    }

    @Test
    fun mixedOutcomesPrintTheFlakyVerdictAndKeepTheFirstFailingExitCode() {
        val gate = TrialGate(350, 7, "G")
        val exits = List(20) { if (gate.armed(it)) 3 else 0 }
        assertTrue(exits.any { it != 0 } && exits.any { it == 0 }, "gate pattern is not mixed")
        val (_, result) = repeated("--out out --repeat 20 -- t", steps(exits, List(20) { 10L }))
        assertEquals(3, result.statusCode)
        val failed = exits.count { it != 0 }
        val rate = "${20 - failed} passed (${(20 - failed) * 5}%), $failed failed"
        assertTrue("drift: 20 runs, $rate; history state FLAKY" in result.stderr, result.stderr)
        assertEquals("run ${exits.indexOfFirst { it != 0 } + 1}\n", result.stdout)
        val run = Run.parse(file("run.json"))
        assertEquals(RunOutcome.FAIL, run.outcome)
        assertEquals(20, run.samples.size)
        assertEquals(3L, CanonicalJson.parse(file("result.json")).obj().require("exitCode").long())
    }

    @Test
    fun aMeasurementTheHostCannotMakeIsRecordedAsAbsent() {
        val script = steps(listOf(0, 0, 0), listOf(5, 6, 7))
        val (_, result) = repeated("--out out --repeat 3 -- t", script)
        assertEquals(0, result.statusCode, result.stderr)
        val text = file("run.json")
        assertFalse("userMs" in text || "systemMs" in text || "peakKb" in text, text)
        assertFalse("cpu time" in result.stderr || "peak memory" in result.stderr)
        assertTrue("wall time median 6 ms" in result.stderr)
    }

    @Test
    fun withoutRepeatTheRecordAndTheOutputAreUnchanged() {
        val (_, result) = repeated("--out out -- t", steps(listOf(0), listOf(40)))
        assertEquals(0, result.statusCode)
        assertEquals("run 1\n", result.stdout)
        assertFalse("samples" in file("run.json"))
        assertTrue("drift: exit 0 after 40 ms; capsule in out" in result.stderr, result.stderr)
    }

    @Test
    fun badRepeatOptionsFailBeforeRunningAnything() {
        val (host, zero) = repeated("--out out --repeat 0 -- t", steps(listOf(0), listOf(1)))
        assertEquals(1, zero.statusCode)
        assertTrue("--repeat must be at least 1" in zero.stderr, zero.stderr)
        val (_, negative) = repeated("--out out --warmup -1 -- t", steps(listOf(0), listOf(1)))
        assertEquals(1, negative.statusCode)
        assertTrue("--warmup must not be negative" in negative.stderr, negative.stderr)
        assertEquals(0, host.calls)
        assertTrue(disk.files.isEmpty())
    }

    private fun record(name: String, cpu: String, walls: List<Long>, exits: List<Int>? = null) {
        val clock = TestTimeSource()
        val files = mapOf("/sys/fs/cgroup/cpu.max" to cpu)
        val script = steps(exits ?: walls.map { 0 }, walls)
        val host = ScriptedHost(clock, script, files)
        val result = RunCommand(host, disk, clock)
            .test("--out $name --label $name --repeat ${walls.size} -- t")
        assertTrue(result.statusCode == 0 || exits != null, result.stderr)
    }

    private fun diagnose(args: String) =
        DriftCommand(FakeHost(canRun = false, files = disk.files.toMap()), disk)
            .test("diagnose $args")

    @Test
    fun diagnoseRanksTheCpuLimitWhenTheSecondRunIsClearlySlower() {
        record("fast", "max 100000", listOf(100, 100, 101, 102, 103))
        record("slow", "100000 100000", listOf(249, 250, 250, 251, 252))
        val text = diagnose("fast slow --detail summary")
        assertEquals(0, text.statusCode, text.stderr)
        assertTrue("Wall time: the second run is 2.5 times slower than the first" in text.stdout)
        assertTrue("cgroup.cpu.max differs most" in text.stdout, text.stdout)
        val json = CanonicalJson.parse(diagnose("fast slow").stdout).obj()
        val performance = json.require("performance").obj()
        assertEquals(
            "2.5 times slower",
            performance.require("symptoms").array().single().obj().require("value").string(),
        )
        assertEquals(
            "cgroup.cpu.max",
            json.require("relevant").array().first().obj().require("path").string(),
        )
    }

    @Test
    fun diagnoseSaysNothingBeyondTheSpreadWhenTheRangesOverlap() {
        record("a", "max 100000", listOf(100, 120, 101, 140, 110))
        record("b", "100000 100000", listOf(105, 130, 99, 150, 112))
        val text = diagnose("a b --detail summary").stdout
        assertTrue("no difference larger than the run to run spread" in text, text)
        assertFalse("times slower" in text)
        val json = CanonicalJson.parse(diagnose("a b").stdout).obj()
        assertEquals(
            0,
            json.require("performance").obj().require("symptoms").array().size,
        )
    }

    @Test
    fun diagnoseSaysWhenTheFailingRunIsIntermittent() {
        record("good", "max 100000", listOf(10, 10, 10, 10, 10, 10))
        record("bad", "max 100000", List(6) { 10L }, exits = listOf(0, 1, 0, 0, 1, 0))
        val text = diagnose("good bad --detail summary")
        assertEquals(0, text.statusCode, text.stderr)
        val note = "The second run is intermittent: 2 of 6 samples failed"
        assertTrue(note in text.stdout, text.stdout)
        assertTrue("history state FLAKY" in text.stdout)
        val json = CanonicalJson.parse(diagnose("good bad").stdout).obj()
        assertTrue(json.fields.containsKey("performance"))
    }

    @Test
    fun diagnoseOfRunsWithoutSamplesHasNoPerformanceKey() {
        val host = host(listOf("t") to CommandResult(0, "ok\n"))
        drift(host, "run --out one -- t")
        drift(host, "run --out two -- t")
        val json = CanonicalJson.parse(diagnose("one two").stdout).obj()
        assertFalse(json.fields.containsKey("performance"))
    }
}
