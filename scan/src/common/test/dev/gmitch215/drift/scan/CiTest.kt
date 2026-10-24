package dev.gmitch215.drift.scan

import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.host.Capability
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.host.TIMED_OUT_EXIT_CODE
import dev.gmitch215.drift.know.Rules
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.plan.Frame
import dev.gmitch215.drift.plan.Options
import dev.gmitch215.drift.plan.Planner
import dev.gmitch215.drift.rank.Ranker
import dev.gmitch215.drift.scan.ci.CiCheck
import dev.gmitch215.drift.scan.ci.CiJobInfo
import dev.gmitch215.drift.scan.ci.CiOutcome
import dev.gmitch215.drift.scan.ci.CiProblemKind
import dev.gmitch215.drift.scan.ci.CiProviderKind
import dev.gmitch215.drift.scan.ci.CiRepo
import dev.gmitch215.drift.scan.ci.CiReport
import dev.gmitch215.drift.scan.ci.CiRepos
import dev.gmitch215.drift.scan.ci.CiRequest
import dev.gmitch215.drift.scan.ci.CiRunInfo
import dev.gmitch215.drift.scan.ci.CiStop
import dev.gmitch215.drift.scan.ci.GithubCi
import dev.gmitch215.drift.scan.ci.GitlabCi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FIELDS = "databaseId,workflowName,workflowDatabaseId,headSha,headBranch,url," +
    "createdAt,event,conclusion"
private const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

private val REPO = CiRepo("/r", "github.com", "o/n", "origin", "main", SHA)
private val FAILED =
    CiRunInfo("10", "Build, run 10", "Build", "7", "bbb", "main", "u", "2026-10-05")

private fun runJson(id: Long, sha: String, at: String, branch: String = "main") =
    """{"conclusion":"failure","createdAt":"$at","databaseId":$id,"event":"push",""" +
        """"headBranch":"$branch","headSha":"$sha","url":"https://x/$id",""" +
        """"workflowDatabaseId":7,"workflowName":"Build"}"""

private fun passed(json: String) = json.replace("failure", "success")

private fun ghList(vararg filter: String, limit: Int) =
    listOf("gh", "run", "list", "-R", "o/n", *filter, "--limit", "$limit", "--json", FIELDS)

private fun ghView(id: String) = listOf("gh", "run", "view", id, "-R", "o/n", "--json", FIELDS)

private fun ghSummary(id: String) =
    listOf("gh", "run", "view", id, "-R", "o/n", "--json", "databaseId,conclusion,jobs")

private fun ghLog(id: String) = listOf("gh", "run", "view", id, "-R", "o/n", "--log", "--job", "9")

private val FAILURES = ghList("--branch", "main", "--status", "failure", limit = 20)
private val PASSES_ON_MAIN = ghList(
    "--branch",
    "main",
    "--workflow",
    "7",
    "--status",
    "success",
    limit = 50,
)
private val PASSES = ghList("--workflow", "7", "--status", "success", limit = 50)

private fun ok(text: String) = CommandResult(0, text)

private fun github(vararg commands: Pair<List<String>, CommandResult>) =
    GithubCi(FakeHost(commands = mapOf(*commands)), "github.com")

private fun stop(block: () -> Unit): CiStop = assertFailsWith<CiStop>(block = block)

private fun jobLog(image: String) = """
    2026-10-01T00:00:00.0000000Z Current runner version: '2.337.0'
    2026-10-01T00:00:00.1000000Z ##[group]Operating System
    2026-10-01T00:00:00.2000000Z Ubuntu
    2026-10-01T00:00:00.3000000Z 24.04.5
    2026-10-01T00:00:00.4000000Z LTS
    2026-10-01T00:00:00.5000000Z ##[endgroup]
    2026-10-01T00:00:00.6000000Z ##[group]Runner Image
    2026-10-01T00:00:00.7000000Z Image: ubuntu-24.04
    2026-10-01T00:00:00.8000000Z Version: $image
    2026-10-01T00:00:00.9000000Z ##[endgroup]
""".trimIndent().lines().joinToString("\n") {
    "t\tSet up job\t$it"
}

private fun summary() = """{"conclusion":"failure","databaseId":1,"jobs":""" +
    """[{"databaseId":9,"name":"t","conclusion":"failure","steps":[]}]}"""

class CiTest {
    @Test
    fun remoteUrlsGiveAHostAndAPathWithoutCredentials() {
        val cases = mapOf(
            "https://github.com/o/n.git" to ("github.com" to "o/n"),
            "https://user:ghp_secret@github.com/o/n" to ("github.com" to "o/n"),
            "git@github.com:o/n.git" to ("github.com" to "o/n"),
            "ssh://git@gl.example.com:2222/g/sub/n.git" to ("gl.example.com" to "g/sub/n"),
            "https://ghe.example.com:8443/o/n/" to ("ghe.example.com:8443" to "o/n"),
            "HTTPS://GitHub.com/o/n" to ("github.com" to "o/n"),
        )
        for ((url, expected) in cases) assertEquals(expected, CiRepos.parseRemote(url), url)
        for (url in listOf("/srv/git/n.git", "../n", "C:\\work\\n", "https://github.com/n", "")) {
            assertNull(CiRepos.parseRemote(url), url)
        }
    }

    @Test
    fun theProviderComesFromTheHostAndTheEnvironment() {
        val env = mapOf("GH_HOST" to "ghe.example.com", "GITLAB_HOST" to "https://gl.example.org/")
        assertEquals(CiProviderKind.GITHUB, CiRepos.providerFor("github.com", emptyMap()))
        assertEquals(CiProviderKind.GITHUB, CiRepos.providerFor("ghe.example.com", env))
        assertEquals(CiProviderKind.GITLAB, CiRepos.providerFor("gitlab.com", emptyMap()))
        assertEquals(CiProviderKind.GITLAB, CiRepos.providerFor("gl.example.org", env))
        assertNull(CiRepos.providerFor("git.example.org", env))
    }

    @Test
    fun detectReadsTheRootRemoteBranchAndHead() {
        fun git(vararg commands: Pair<List<String>, CommandResult>) =
            FakeHost(commands = mapOf(*commands))

        val root = listOf("git", "rev-parse", "--show-toplevel") to ok("/r\n")
        val remote = listOf("git", "remote", "get-url", "origin") to ok("git@github.com:o/n.git\n")
        val branch = listOf("git", "rev-parse", "--abbrev-ref", "HEAD") to ok("HEAD\n")
        val head = listOf("git", "rev-parse", "HEAD") to ok("abc123\n")
        val detached = CiRepos.detect(git(root, remote, branch, head), "origin")
        assertEquals(CiRepo("/r", "github.com", "o/n", "origin", null, "abc123"), detached)
        assertEquals(CiProblemKind.NOT_A_CHECKOUT, stop { CiRepos.detect(git(), "origin") }.kind)
        val noRemote = stop { CiRepos.detect(git(root, head), "origin") }
        assertEquals(CiProblemKind.NOT_A_CHECKOUT, noRemote.kind)
        val local = listOf("git", "remote", "get-url", "origin") to ok("/srv/git/n\n")
        val unreadable = stop { CiRepos.detect(git(root, local, head), "origin") }
        assertEquals(CiProblemKind.UNSUPPORTED_HOST, unreadable.kind)
    }

    @Test
    fun toolFailuresBecomeOneSentenceAndNeverEchoTheBody() {
        val auth = CiProblemKind.NOT_LOGGED_IN
        val rate = CiProblemKind.RATE_LIMITED
        val net = CiProblemKind.NO_NETWORK
        val cases = listOf(
            Triple(4, "please run:  gh auth login", auth),
            Triple(1, "HTTP 401: Bad credentials (https://api.github.com)", auth),
            Triple(1, "HTTP 403: API rate limit exceeded for user ID 42", rate),
            Triple(1, "error connecting to api.github.com", net),
            Triple(1, "dial tcp: lookup api.github.com: no such host", net),
            Triple(1, "HTTP 404: Not Found (https://api.github.com/o/n)", CiProblemKind.NOT_FOUND),
            Triple(1, "HTTP 403: Resource not accessible", CiProblemKind.FAILED),
            Triple(2, "boom ghp_0123456789abcdefghijABCDEFGHIJ", CiProblemKind.FAILED),
            Triple(TIMED_OUT_EXIT_CODE, "", CiProblemKind.TIMED_OUT),
            Triple(127, "", CiProblemKind.MISSING_TOOL),
        )
        for ((status, body, kind) in cases) {
            val gh = github(FAILURES to CommandResult(status, body))
            val stopped = stop { gh.failedRun(REPO, "main", null, null) }
            assertEquals(kind, stopped.kind, body)
            assertFalse("ghp_" in stopped.message.orEmpty(), body)
            assertFalse(body.isNotEmpty() && body in stopped.message.orEmpty(), body)
        }
        val offline = GithubCi(FakeHost(canRun = false), "github.com")
        val missing = stop { offline.failedRun(REPO, "main", null, null) }
        assertEquals(CiProblemKind.MISSING_TOOL, missing.kind)
    }

    @Test
    fun anExpiredLogIsNamedOnlyWhenALogWasAsked() {
        val gone = CommandResult(1, "HTTP 410: Gone")
        val gh = github(ghLog("10") to gone, FAILURES to gone)
        val stopped = stop { gh.log(REPO, FAILED, CiJobInfo("9", "t", true)) }
        assertEquals(CiProblemKind.LOG_EXPIRED, stopped.kind)
        val other = stop { gh.failedRun(REPO, "main", null, null) }
        assertEquals(CiProblemKind.FAILED, other.kind)
    }

    @Test
    fun theGithubPassingRunIsTheLatestEarlierOneOnTheBranchThenAnywhere() {
        val newer = runJson(11, "ccc", "2026-10-06")
        val older = runJson(9, "aaa", "2026-10-04")
        val onBranch = github(PASSES_ON_MAIN to ok("[$newer,$older]"))
        assertEquals("9", onBranch.passingRun(REPO, FAILED, null).id)
        val other = runJson(8, "ddd", "2026-10-03", "dev")
        val fallback = github(PASSES_ON_MAIN to ok("[]"), PASSES to ok("[$other]"))
        assertEquals("dev", fallback.passingRun(REPO, FAILED, null).branch)
        val none = github(PASSES_ON_MAIN to ok("[$newer]"), PASSES to ok("[$newer]"))
        val stopped = stop { none.passingRun(REPO, FAILED, null) }
        assertEquals(CiProblemKind.NO_PASSING_RUN, stopped.kind)
    }

    @Test
    fun goodTakesACommitOrARunId() {
        val workflow = arrayOf("--workflow", "7", "--status", "success")
        val byCommit = ghList("--commit", "abc1234", *workflow, limit = 20)
        val good = passed(runJson(3, "abc1234", "2026-10-01"))
        val renumbered = good.replace("\"databaseId\":3", "\"databaseId\":123456789")
        val gh = github(
            byCommit to ok("[$good]"),
            ghView("123456789") to ok(renumbered),
            ghView("987654321") to ok(runJson(987654321, "abc", "2026-10-01")),
        )
        assertEquals("3", gh.passingRun(REPO, FAILED, "abc1234").id)
        assertEquals("123456789", gh.passingRun(REPO, FAILED, "123456789").id)
        val wrong = stop { gh.passingRun(REPO, FAILED, "987654321") }
        assertEquals("run 987654321 concluded failure, not success", wrong.message)
    }

    @Test
    fun detachedHeadAndAFailureThatIsNotOneAreRefused() {
        val detached = stop { github().failedRun(REPO, null, null, null) }
        assertTrue("--commit SHA or --run ID" in detached.message.orEmpty())
        val success = ghView("123456789") to ok(passed(runJson(1, "a", "t")))
        val green = stop { github(success).failedRun(REPO, "main", null, "123456789") }
        assertEquals("run 123456789 concluded success, not failure", green.message)
        val byCommit = ghList("--commit", "abc1234", "--status", "failure", limit = 20)
        val none = github(byCommit to ok("[]"))
        val empty = stop { none.failedRun(REPO, "main", "abc1234", null) }
        assertEquals(CiProblemKind.NO_RUNS, empty.kind)
        assertTrue("commit abc1234" in empty.message.orEmpty())
    }

    @Test
    fun gitlabJobsRunInIdOrderAndSkipTheOnesAllowedToFail() {
        val jobs = """[
            {"id":30,"name":"lint","stage":"test","status":"failed","allow_failure":true},
            {"id":20,"name":"unit","stage":"test","status":"failed","allow_failure":false,
             "duration":1.5},
            {"id":10,"name":"build","stage":"build","status":"success","allow_failure":false}
        ]"""
        val parsed = GitlabCi(FakeHost(), "gitlab.com").jobs(jobs)
        assertEquals(listOf("build", "unit", "lint"), parsed.map { it.name })
        assertEquals(listOf("unit"), parsed.filter { it.failed }.map { it.name })
    }

    @Test
    fun gitlabNamesTheHostAndEscapesTheBranch() {
        val repo = CiRepo("/r", "gl.example.org", "g/sub/n", "origin", "a&b c", SHA)
        val path = "projects/g%2Fsub%2Fn/pipelines?status=failed&per_page=20&ref=a%26b%20c"
        val command = listOf("glab", "api", "--hostname", "gl.example.org", path)
        val pipeline = """[{"id":5,"sha":"abc","ref":"a&b c","source":"push","web_url":"u",""" +
            """"created_at":"t"}]"""
        val host = FakeHost(commands = mapOf(command to ok(pipeline)))
        val found = GitlabCi(host, "gl.example.org").failedRun(repo, repo.branch, null, null)
        assertEquals("pipeline 5 from push", found.title)
        assertEquals("push", found.workflowKey)
    }

    @Test
    fun aMissingProcessCapabilityStopsBeforeAnyCall() {
        val host = FakeHost(capabilities = setOf(Capability.FILES))
        val outcome = CiCheck.run(host, MemoryCaseFiles(), CiRequest()) as CiOutcome.Stopped
        assertEquals(CiProblemKind.MISSING_TOOL, outcome.kind)
        assertTrue(host.ran.isEmpty())
    }

    private fun compared(outcome: CiOutcome): CiReport = when (outcome) {
        is CiOutcome.Compared -> outcome.report
        is CiOutcome.Stopped -> error("${outcome.kind}: ${outcome.message}")
    }

    private fun twoRunsOfOneCommit(ignored: Int): FakeHost {
        val red = runJson(222222222, SHA, "2026-10-05")
        val green = passed(runJson(111111111, SHA, "2026-10-04"))
        val ignore = CommandResult(ignored, "")
        val commands = mapOf(
            listOf("git", "rev-parse", "--show-toplevel") to ok("/r\n"),
            listOf("git", "remote", "get-url", "origin") to ok("git@github.com:o/n.git\n"),
            listOf("git", "rev-parse", "--abbrev-ref", "HEAD") to ok("main\n"),
            listOf("git", "rev-parse", "HEAD") to ok("$SHA\n"),
            FAILURES to ok("[$red]"),
            PASSES_ON_MAIN to ok("[$green]"),
            ghSummary("222222222") to ok(summary()),
            ghSummary("111111111") to ok(summary()),
            ghLog("222222222") to ok(jobLog("20261012.1")),
            ghLog("111111111") to ok(jobLog("20261004.1")),
            listOf("git", "check-ignore", "-q", "/r/.drift/ci/aaaaaaa/case.json") to ignore,
            listOf("git", "check-ignore", "-q", "/r/out/x/case.json") to ignore,
        )
        return FakeHost(env = mapOf("PWD" to "/elsewhere"), commands = commands)
    }

    @Test
    fun twoRunsOfOneCommitAreToldApartByRunId() {
        val disk = MemoryCaseFiles()
        val report = compared(CiCheck.run(twoRunsOfOneCommit(0), disk, CiRequest()))
        assertEquals("aaaaaaa@111111111", report.passingCapsule.label)
        assertEquals("aaaaaaa@222222222", report.failingCapsule.label)
        assertEquals("/r/.drift/ci/aaaaaaa", report.shownDir)
        assertNull(report.ignoreLine)
        assertTrue("/r/.drift/ci/aaaaaaa/case.json" in disk.files)
        val text = report.text(null)
        assertTrue("Note:" !in text && "git diff --stat" !in text, text)
        assertTrue("ci.runner.image.version: 20261004.1 -> 20261012.1" in text, text)
    }

    @Test
    fun outPicksTheDirectoryAndTheIgnoreLineNamesIt() {
        val disk = MemoryCaseFiles()
        val request = CiRequest(out = "/r/out/x/")
        val report = compared(CiCheck.run(twoRunsOfOneCommit(1), disk, request))
        assertEquals("out/x/", report.ignoreLine)
        assertTrue("/r/out/x/case.json" in disk.files)
        val outside = CiCheck.run(twoRunsOfOneCommit(1), disk, CiRequest(out = "/tmp/x"))
        assertNull(compared(outside).ignoreLine)
    }

    @Test
    fun aRuleBackedChangeNamesTheCauseAndDropsTheLimitsParagraph() {
        fun capsule(label: String, version: String) = Capsule(
            label,
            listOf(
                Attribute("ci.runner.label", "ubuntu-latest", "log"),
                Attribute("os.release.VERSION_ID", version, "log"),
            ),
        )

        val green = capsule("g", "24.04")
        val red = capsule("r", "26.04")
        val ranking = Ranker.rank(green, red, Rules.all)
        val plan = Planner.from(ranking, Frame.shas("g", "r"), Options(5, 1, 1))
        val report = CiReport(
            REPO,
            CiProviderKind.GITHUB,
            FAILED,
            FAILED,
            "t",
            null,
            emptyList(),
            emptyList(),
            green,
            red,
            ranking,
            plan,
            "/r/.drift/ci/a",
            ".drift/ci/a",
            null,
        )
        assertTrue(report.namesCause)
        val text = report.text(Detail.SUMMARY)
        assertTrue("moderate" in text || "high" in text, text)
        assertFalse("cannot find an ordinary code regression" in text, text)
        assertFalse("TEST_COMMAND" in text, text)
    }
}
