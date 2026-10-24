package dev.gmitch215.drift.cli

import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.fixtures.CiFixtures
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ROOT = "/work/repo"
private const val GH_FIELDS = "databaseId,workflowName,workflowDatabaseId,headSha,headBranch," +
    "url,createdAt,event,conclusion"

private const val RED = "40042665061"
private const val GREEN = "38452229524"
private const val WORKFLOW = "377446806"
private const val RED_LINUX = "120718675506"
private const val GREEN_LINUX = "115487406178"
private const val RED_MACOS = "120718675516"
private const val GREEN_MACOS = "115487406722"

private const val GL_RED = "2970143654"
private const val GL_GREEN = "2969877432"
private const val GL_RED_JOB = "17335359747"
private const val GL_GREEN_JOB = "17333588693"
private const val GL_PROJECT = "projects/gitlab-org%2Fcli"

private fun live(name: String) = CiFixtures.text("live/$name")

private fun ok(text: String) = CommandResult(0, text)

private fun git(remote: String, branch: String, head: String, ignored: Int = 1) = mapOf(
    listOf("git", "rev-parse", "--show-toplevel") to ok("$ROOT\n"),
    listOf("git", "remote", "get-url", "origin") to ok("$remote\n"),
    listOf("git", "rev-parse", "--abbrev-ref", "HEAD") to ok("$branch\n"),
    listOf("git", "rev-parse", "HEAD") to ok("$head\n"),
    listOf("git", "check-ignore", "-q", "$ROOT/.drift/ci/${head.take(7)}/case.json") to
        CommandResult(ignored, ""),
)

private val HEAD = "c775d8f53129708364803952edc93cf66ca73201"

private fun ghRuns(vararg filter: String, limit: Int) = listOf(
    "gh", "run", "list", "-R", "gmitch215/Drift", *filter,
    "--limit", "$limit", "--json", GH_FIELDS,
)

private fun ghView(id: String) =
    listOf("gh", "run", "view", id, "-R", "gmitch215/Drift", "--json", "databaseId,conclusion,jobs")

private fun ghLog(id: String, job: String) =
    listOf("gh", "run", "view", id, "-R", "gmitch215/Drift", "--log", "--job", job)

private val GH_FAILED = ghRuns("--branch", "master", "--status", "failure", limit = 20)
private val GH_PASSED = ghRuns(
    "--branch",
    "master",
    "--workflow",
    WORKFLOW,
    "--status",
    "success",
    limit = 50,
)
private val GH_ANY_PASSED = ghRuns("--workflow", WORKFLOW, "--status", "success", limit = 50)

private fun github(extra: Map<List<String>, CommandResult> = emptyMap()) =
    git("https://github.com/gmitch215/Drift.git", "master", HEAD) + mapOf(
        GH_FAILED to ok(live("github/run-list-failure.json")),
        GH_PASSED to ok(live("github/run-list-success.json")),
        ghView(RED) to ok(live("github/run-red.json")),
        ghView(GREEN) to ok(live("github/run-green.json")),
        ghLog(RED, RED_LINUX) to ok(live("github/log-red-linux-x64.log")),
        ghLog(GREEN, GREEN_LINUX) to ok(live("github/log-green-linux-x64.log")),
        ghLog(RED, RED_MACOS) to ok(live("github/log-red-macos-arm64.log")),
        ghLog(GREEN, GREEN_MACOS) to ok(live("github/log-green-macos-arm64.log")),
    ) + extra

private fun glApi(path: String) = listOf("glab", "api", path)

private fun gitlab() = git(
    "git@gitlab.com:gitlab-org/cli.git",
    "main",
    "288e506581795fd234a3b4a98e0d62cc3df85818",
) + mapOf(
    glApi("$GL_PROJECT/pipelines?status=failed&per_page=20&ref=main") to
        ok(live("gitlab/pipelines-failed.json")),
    glApi("$GL_PROJECT/pipelines?status=success&per_page=100&ref=main&source=push") to
        ok(live("gitlab/pipelines-success.json")),
    glApi("$GL_PROJECT/pipelines/$GL_RED/jobs?per_page=100") to ok(live("gitlab/jobs-red.json")),
    glApi("$GL_PROJECT/pipelines/$GL_GREEN/jobs?per_page=100") to
        ok(live("gitlab/jobs-green.json")),
    glApi("$GL_PROJECT/jobs/$GL_RED_JOB/trace") to ok(live("gitlab/trace-red.txt")),
    glApi("$GL_PROJECT/jobs/$GL_GREEN_JOB/trace") to ok(live("gitlab/trace-green.txt")),
)

class CiCommandTest {
    private val disk = MemoryCaseFiles()

    private fun host(commands: Map<List<String>, CommandResult>) =
        FakeHost(env = mapOf("PWD" to ROOT), commands = commands)

    private fun ci(host: FakeHost, args: String = "") =
        DriftCommand(host, disk).test(("ci $args").trim())

    @Test
    fun aFailingAndAPassingRunOfTheSameWorkflowAreDiagnosed() {
        val host = host(github())
        val result = ci(host)
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(live("golden/github-pair.txt"), result.stdout)
        assertEquals("", result.stderr)
        val dir = "$ROOT/.drift/ci/c775d8f"
        assertTrue("$dir/case.json" in disk.files, disk.files.keys.toString())
        assertTrue("$dir/capsules/passing/capsule.json" in disk.files)
        assertTrue("$dir/capsules/failing/run.json" in disk.files)
        assertTrue(host.ran.none { it.firstOrNull() == "drift" })
    }

    @Test
    fun theWrittenCaseAndCapsulesWorkWithTheExistingCommands() {
        ci(host(github()))
        val dir = "$ROOT/.drift/ci/c775d8f"
        val shown = DriftCommand(host(emptyMap()), disk).test("case show $dir --detail summary")
        assertEquals(0, shown.statusCode, shown.stderr)
        assertTrue("integrity: ok" in shown.stdout, shown.stdout)
        val again = FakeHost(files = disk.files)
        val diagnosed = DriftCommand(again, disk).test(
            "diagnose $dir/capsules/passing $dir/capsules/failing --detail summary",
        )
        assertEquals(0, diagnosed.statusCode, diagnosed.stderr)
        assertTrue(shown.stdout.endsWith(diagnosed.stdout), diagnosed.stdout)
    }

    @Test
    fun noPassingRunSaysWhatToDoInstead() {
        val empty = ok("[]")
        val host = host(github(mapOf(GH_PASSED to empty, GH_ANY_PASSED to empty)))
        val result = ci(host)
        assertEquals(1, result.statusCode)
        assertTrue("no passing run of Build Project found before run $RED" in result.stderr)
        assertTrue("--good SHA|RUN_ID" in result.stderr, result.stderr)
        assertTrue("drift run --tools --project" in result.stderr, result.stderr)
        assertTrue(disk.files.isEmpty())
    }

    @Test
    fun aRunWithSeveralFailedJobsNamesTheOthersAndTakesTheFirst() {
        val result = ci(host(github()))
        assertTrue("Job      Test Project (linux-x64), failed in step Run Tests" in result.stdout)
        assertTrue(
            "Other failed jobs: Test Project (macos-arm64), Test Project (windows-x64), " +
                "Test Project (linux-arm64). Pass --job NAME to compare one of them." in
                result.stdout.replace("\n         ", " "),
            result.stdout,
        )
    }

    @Test
    fun jobComparesTheNamedJobInBothRuns() {
        val result = ci(host(github()), "--job \"Test Project (macos-arm64)\" --out /tmp/macos")
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue("Job      Test Project (macos-arm64)" in result.stdout, result.stdout)
        assertTrue("ci.runner.image: macos-15-arm64" !in result.stdout)
        assertTrue("/tmp/macos/case.json" in disk.files)
        val missing = ci(host(github()), "--job nope")
        assertEquals(1, missing.statusCode)
        assertTrue("has no job named nope; its jobs are Lint Project" in missing.stderr)
    }

    @Test
    fun aMissingToolNamesTheInstallAndAnOutOfDateLoginNamesTheCommand() {
        val noGh = github().filterKeys { it.firstOrNull() != "gh" }
        val missing = ci(host(noGh))
        assertEquals(1, missing.statusCode)
        assertTrue(
            "gh is not installed or not on PATH; install it from https://cli.github.com" in
                missing.stderr,
            missing.stderr,
        )
        val body = "To get started with GitHub CLI, please run:  gh auth login\nghp_secret"
        val loggedOut = ci(host(github(mapOf(GH_FAILED to CommandResult(4, body)))))
        assertEquals(1, loggedOut.statusCode)
        assertTrue("gh is not logged in to github.com; run gh auth login" in loggedOut.stderr)
        assertFalse("ghp_secret" in loggedOut.stderr + loggedOut.stdout)
    }

    @Test
    fun aGitlabPipelinePairIsDiagnosedThroughGlab() {
        val host = host(gitlab())
        val result = ci(host)
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(live("golden/gitlab-pair.txt"), result.stdout)
        assertTrue("$ROOT/.drift/ci/288e506/case.json" in disk.files, disk.files.keys.toString())
        assertTrue(host.ran.all { it.first() in setOf("git", "glab") })
    }

    @Test
    fun jsonPrintsTheComparisonAndNoText() {
        val result = ci(host(github()), "--json")
        assertEquals(0, result.statusCode, result.stderr)
        val json = CanonicalJson.parse(result.stdout) as JsonObject
        assertEquals("github", json.require("provider").string())
        assertEquals("Test Project (linux-x64)", json.require("job").string())
        assertEquals(JsonBool(false), json.require("namesCause"))
        assertEquals(JsonString(".drift/ci/c775d8f"), json.require("directory"))
        assertTrue(json.require("ranking") is JsonObject)
    }

    @Test
    fun flagsThatCannotWorkTogetherAreRefusedBeforeAnyCall() {
        val host = host(github())
        val refused = mapOf(
            "--json --detail full" to "--json and --detail cannot be combined",
            "--run $RED --commit abcdef0" to "both name the failing run",
            "--run 12" to "--run takes a run id of digits",
            "--good zzzz" to "--good takes a commit hash",
        )
        for ((args, message) in refused) {
            val result = ci(host, args)
            assertEquals(1, result.statusCode, args)
            assertTrue(message in result.stderr, "$args: ${result.stderr}")
        }
        assertTrue(host.ran.isEmpty())
    }

    @Test
    fun anUnsupportedHostListsTheProvidersAndTheManualPath() {
        val commands = git("git@git.example.org:team/app.git", "main", HEAD)
        val result = ci(host(commands))
        assertEquals(1, result.statusCode)
        assertTrue("supports GitHub" in result.stderr && "GitLab" in result.stderr)
        assertTrue("drift import" in result.stderr)
    }
}
