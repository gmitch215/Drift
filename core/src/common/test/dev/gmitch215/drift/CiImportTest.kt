package dev.gmitch215.drift

import dev.gmitch215.drift.ci.GithubImport
import dev.gmitch215.drift.ci.GitlabImport
import dev.gmitch215.drift.ci.Import
import dev.gmitch215.drift.ci.LooseJson
import dev.gmitch215.drift.fixtures.CiFixtures
import dev.gmitch215.drift.fixtures.RunFixtures
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import dev.gmitch215.drift.model.StepConclusion
import dev.gmitch215.drift.symptom.SymptomKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CiImportTest {
    private val green = "36702683742"
    private val red = "37114464625"

    private fun done(import: Import): Run = assertIs<Import.Done>(import).run

    private fun github(id: String, job: String? = "Docker E2E") =
        done(GithubImport.log(RunFixtures.text("logs/$id.log"), id, job))

    private fun attrs(run: Run) = run.capsule!!.attributes.associate { it.path to it.value }

    @Test
    fun githubLogReadsTheRunnerImageBlock() {
        val values = attrs(github(green))
        assertEquals("ubuntu-24.04", values["ci.runner.image"])
        assertEquals("20260920.314.1", values["ci.runner.image.version"])
        assertEquals("Ubuntu", values["os.release.name"])
        assertEquals("24.04.5", values["os.version"])
        assertEquals("2.337.0", values["ci.runner.version"])
        assertEquals("Hosted Compute Agent", values["ci.provisioner.name"])
    }

    @Test
    fun githubLogAgreesWithTheFrozenCapsule() {
        for (id in listOf(green, red)) {
            val frozen = Capsule.parse(RunFixtures.text("capsules/$id.json")).attributes
                .associate { it.path to it.value }
            val read = attrs(github(id))
            assertTrue(read.size >= 10, "$id: $read")
            for ((path, value) in read) assertEquals(frozen[path], value, "$id $path")
        }
    }

    @Test
    fun githubLogOutcomeAndSymptoms() {
        val failed = github(red)
        assertEquals(RunOutcome.FAIL, failed.outcome)
        assertTrue(failed.symptoms.any { it.kind == SymptomKind.SIGNATURE })
        assertEquals(RunOutcome.UNKNOWN, github(green).outcome)
    }

    @Test
    fun githubLogPicksTheFirstJobWithARunnerImageBlockByDefault() {
        val text = RunFixtures.text("logs/$green.log")
        val byDefault = done(GithubImport.log(text, "x"))
        val named = done(GithubImport.log(text, "x", "What the Diff Touches"))
        assertEquals(named.capsule, byDefault.capsule)
        val other = done(GithubImport.log(text, "x", "Broken VPS"))
        assertTrue(other.capsule!!.attributes.isNotEmpty())
    }

    @Test
    fun githubRawLogReadsTheEnvironmentBlock() {
        val run = done(GithubImport.log(CiFixtures.text("github-raw.log"), "raw"))
        val values = attrs(run)
        assertEquals("test", values["env.NODE_ENV"])
        assertNull(values["env.API_TOKEN"])
        assertEquals("20260927.320.1", values["ci.runner.image.version"])
        assertEquals(RunOutcome.FAIL, run.outcome)
        assertTrue(run.symptoms.any { it.kind == SymptomKind.EXIT && it.value == "1" })
    }

    @Test
    fun githubSummaryReadsOutcomeStepsAndDurations() {
        val run = done(GithubImport.summary(RunFixtures.text("runs/36995781138.json")))
        assertEquals("36995781138", run.id)
        assertEquals(RunOutcome.FAIL, run.outcome)
        val failed = run.steps.single { it.conclusion == StepConclusion.FAILURE }
        assertEquals("Docker E2E", failed.job)
        assertEquals("Run Integration Suite with Coverage", failed.name)
        assertEquals(199_000L, failed.durationMs)
        assertNull(run.capsule)
    }

    @Test
    fun githubRunMergesTheSummaryOverTheLog() {
        val log = RunFixtures.text("logs/$green.log")
        val summary = RunFixtures.text("runs/$green.json")
        val run = done(GithubImport.run(log, summary, "g", "Docker E2E"))
        assertEquals(RunOutcome.PASS, run.outcome)
        assertTrue(run.steps.isNotEmpty())
        assertEquals("ubuntu-24.04", attrs(run)["ci.runner.image"])
        assertIs<Import.Refused>(GithubImport.run(log, "{", "g"))
        assertIs<Import.Refused>(GithubImport.run("", summary, "g"))
    }

    @Test
    fun githubInputsThatAreNotTheFormatAreRefused() {
        assertIs<Import.Refused>(GithubImport.log("", "x"))
        assertIs<Import.Refused>(GithubImport.log("just some text\nwith lines\n", "x"))
        assertIs<Import.Refused>(GithubImport.log("##[error]boom\n", "x"))
        assertIs<Import.Refused>(GitlabImport.trace("ERROR: Job failed: exit code 1\n", "x"))
        assertIs<Import.Refused>(GithubImport.summary("[1]"))
        assertIs<Import.Refused>(GithubImport.summary("{}"))
        assertIs<Import.Refused>(GithubImport.summary("{\"jobs\":"))
    }

    @Test
    fun githubSummaryToleratesMissingFields() {
        val run = done(GithubImport.summary("{\"conclusion\":\"cancelled\",\"jobs\":[1,{}]}"))
        assertEquals(RunOutcome.CANCELLED, run.outcome)
        assertEquals("run", run.id)
        assertTrue(run.steps.isEmpty())
        val step = "{\"name\":\"s\",\"startedAt\":\"x\"}"
        val partial = "{\"jobs\":[{\"name\":\"a\",\"steps\":[$step]}]}"
        val steps = done(GithubImport.summary(partial)).steps
        assertEquals(RunOutcome.UNKNOWN, done(GithubImport.summary(partial)).outcome)
        assertNull(steps.single().durationMs)
    }

    @Test
    fun gitlabTraceReadsRunnerExecutorAndImage() {
        val run = done(GitlabImport.trace(CiFixtures.text("gitlab-red.trace"), "red"))
        val values = attrs(run)
        assertEquals("17.5.1", values["ci.runner.version"])
        assertEquals("docker", values["ci.runner.executor"])
        assertEquals("ruby:3.3", values["ci.runner.image"])
        assertEquals("ruby@sha256:" + "b".repeat(64), values["ci.runner.image.digest"])
        assertEquals(RunOutcome.FAIL, run.outcome)
        assertTrue(run.symptoms.any { it.kind == SymptomKind.EXIT && it.value == "1" })
        assertTrue(run.symptoms.any { it.kind == SymptomKind.TRANSPORT })
        val pass = done(GitlabImport.trace(CiFixtures.text("gitlab-green.trace"), "green"))
        assertEquals(RunOutcome.PASS, pass.outcome)
        assertEquals("17.4.0", attrs(pass)["ci.runner.version"])
    }

    @Test
    fun gitlabPipelineAndJobs() {
        val pipeline = done(GitlabImport.summary(CiFixtures.text("gitlab-pipeline.json")))
        assertEquals("4242", pipeline.id)
        assertEquals(RunOutcome.FAIL, pipeline.outcome)
        val jobs = done(GitlabImport.summary(CiFixtures.text("gitlab-jobs.json")))
        assertEquals(RunOutcome.FAIL, jobs.outcome)
        assertEquals(listOf("build", "rspec", "deploy"), jobs.steps.map { it.name })
        assertEquals(listOf("build", "test", "deploy"), jobs.steps.map { it.job })
        assertEquals(31_512L, jobs.steps[0].durationMs)
        assertEquals(192L, jobs.steps[1].durationMs)
        assertNull(jobs.steps[2].durationMs)
        assertEquals(StepConclusion.SKIPPED, jobs.steps[2].conclusion)
        val wrapped = "{\"status\":\"success\",\"jobs\":[{\"name\":\"a\",\"status\":\"success\"}]}"
        assertEquals(RunOutcome.PASS, done(GitlabImport.summary(wrapped)).outcome)
        val allGreen = "[{\"name\":\"a\",\"status\":\"success\"}]"
        assertEquals(RunOutcome.PASS, done(GitlabImport.summary(allGreen)).outcome)
        assertEquals(RunOutcome.UNKNOWN, done(GitlabImport.summary("[{\"name\":\"a\"}]")).outcome)
    }

    @Test
    fun gitlabRunMergesAndRefuses() {
        val trace = CiFixtures.text("gitlab-green.trace")
        val merged = done(GitlabImport.run(trace, CiFixtures.text("gitlab-pipeline.json"), "g"))
        assertEquals(RunOutcome.FAIL, merged.outcome)
        assertEquals("docker", attrs(merged)["ci.runner.executor"])
        assertIs<Import.Refused>(GitlabImport.run(trace, "nope", "g"))
        assertIs<Import.Refused>(GitlabImport.trace("", "x"))
        assertIs<Import.Refused>(GitlabImport.summary("{}"))
        assertIs<Import.Refused>(GitlabImport.summary("["))
        assertIs<Import.Done>(GitlabImport.run(trace, null, "g"))
    }

    @Test
    fun truncatedInputNeverThrows() {
        val inputs = mapOf(
            "github" to CiFixtures.text("github-raw.log"),
            "gitlab" to CiFixtures.text("gitlab-red.trace"),
            "jobs" to CiFixtures.text("gitlab-jobs.json"),
            "run" to RunFixtures.text("runs/36995781138.json"),
        )
        for ((name, text) in inputs) {
            for (cut in 0..text.length) {
                val head = text.substring(0, cut)
                when (name) {
                    "github" -> GithubImport.log(head, "x")
                    "gitlab" -> GitlabImport.trace(head, "x")
                    "jobs" -> GitlabImport.summary(head)
                    else -> GithubImport.summary(head)
                }
            }
        }
        val big = RunFixtures.text("logs/$red.log")
        for (cut in big.indices step 1999) GithubImport.log(big.substring(0, cut), "x")
    }

    @Test
    fun looseJsonQuotesFractionsOutsideStringsOnly() {
        val text = "{\"a\":1.5,\"b\":\"2.5\",\"c\":[3,-4.25e2],\"d\":\"x\\\"1.5\"}"
        assertEquals(
            "{\"a\":\"1.5\",\"b\":\"2.5\",\"c\":[3,\"-4.25e2\"],\"d\":\"x\\\"1.5\"}",
            LooseJson.quoteDecimals(text),
        )
        assertEquals(1500L, LooseJson.millis(JsonString("1.5")))
        assertEquals(2000L, LooseJson.millis(JsonInt(2)))
        assertEquals(12L, LooseJson.millis(JsonString("0.0129")))
        assertNull(LooseJson.millis(JsonString("1e3")))
        assertNull(LooseJson.millis(JsonInt(-1)))
        assertNull(LooseJson.millis(null))
    }
}
