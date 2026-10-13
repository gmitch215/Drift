package dev.gmitch215.drift.cli

import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.fixtures.CiFixtures
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportCommandTest {
    private val disk = MemoryCaseFiles()
    private val host = FakeHost(
        canRun = false,
        files = mapOf(
            "job.log" to CiFixtures.text("github-raw.log"),
            "run.json" to CiFixtures.text("github-run.json"),
            "trace.log" to CiFixtures.text("gitlab-red.trace"),
            "pipeline.json" to CiFixtures.text("gitlab-pipeline.json"),
            "empty.log" to "nothing to see\n",
            "bad.json" to "{",
        ),
    )

    private fun drift(args: String) = DriftCommand(host, disk).test(args)

    @Test
    fun githubLogBecomesACapsuleDirectory() {
        val result = drift("import job.log --format github --out out --label green")
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(result.stderr.startsWith("wrote "), result.stderr)
        val capsule = Capsule.parse(disk.files.getValue("out/capsule.json"))
        assertEquals("green", capsule.label)
        assertTrue(capsule.attributes.any { it.path == "ci.runner.image" })
        val run = Run.parse(disk.files.getValue("out/run.json"))
        assertEquals(RunOutcome.FAIL, run.outcome)
        assertNull(run.capsule)
        assertEquals(setOf("out/capsule.json", "out/run.json"), disk.files.keys)
    }

    @Test
    fun theSummaryOverridesTheOutcomeAndAddsSteps() {
        val result = drift("import job.log --format github --out out --summary run.json")
        assertEquals(0, result.statusCode, result.stderr)
        val run = Run.parse(disk.files.getValue("out/run.json"))
        assertEquals("Run npm test", run.steps.last().name)
        assertEquals(15_000L, run.steps.last().durationMs)
    }

    @Test
    fun gitlabTraceAndPipeline() {
        val result = drift("import trace.log --format gitlab --out g --summary pipeline.json")
        assertEquals(0, result.statusCode, result.stderr)
        val capsule = Capsule.parse(disk.files.getValue("g/capsule.json"))
        val runner = capsule.attributes.single { it.path == "ci.runner.version" }
        assertEquals("17.5.1", runner.value)
        assertEquals("gitlab", capsule.label)
    }

    @Test
    fun refusalsNameTheFileAndWriteNothing() {
        val empty = drift("import empty.log --format github --out out")
        assertEquals(1, empty.statusCode)
        assertTrue("cannot import empty.log: no runner environment found" in empty.stderr)
        val wrong = drift("import trace.log --format github --out out")
        assertTrue("no runner environment found" in wrong.stderr, wrong.stderr)
        val missing = drift("import nope.log --format github --out out")
        assertTrue("cannot read log file: nope.log" in missing.stderr, missing.stderr)
        val badSummary = drift("import job.log --format github --out out --summary bad.json")
        assertTrue("cannot import job.log: not valid JSON" in badSummary.stderr, badSummary.stderr)
        val noSummary = drift("import job.log --format github --out out --summary nope.json")
        assertTrue("cannot read file: nope.json" in noSummary.stderr, noSummary.stderr)
        val job = drift("import trace.log --format gitlab --out out --job x")
        assertEquals(1, job.statusCode)
        assertEquals(1, drift("import job.log --out out").statusCode)
        assertTrue(disk.files.isEmpty())
    }

    @Test
    fun anExistingDirectoryNeedsForce() {
        assertEquals(0, drift("import job.log --format github --out out").statusCode)
        val again = drift("import job.log --format github --out out")
        assertTrue("pass --force" in again.stderr, again.stderr)
        assertEquals(0, drift("import job.log --format github --out out --force").statusCode)
    }
}
