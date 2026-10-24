package dev.gmitch215.drift.scan.ci

import dev.gmitch215.drift.ci.GithubImport
import dev.gmitch215.drift.ci.Import
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonObject

/** GitHub Actions through `gh`; `GH_HOST` and `gh auth login` decide the server and the login. */
class GithubCi(host: Host, site: String) : CiProvider {
    override val kind = CiProviderKind.GITHUB
    private val gh = CiTool(host, "gh", "gh auth login", "https://cli.github.com", site)

    private fun slug(repo: CiRepo) =
        if (repo.host == "github.com") repo.path else "${repo.host}/${repo.path}"

    private fun info(o: JsonObject): CiRunInfo? {
        val id = o.id("databaseId") ?: return null
        val workflow = o.text("workflowName").orEmpty()
        return CiRunInfo(
            id,
            "$workflow, run $id",
            workflow,
            o.id("workflowDatabaseId").orEmpty(),
            o.text("headSha") ?: return null,
            o.text("headBranch").orEmpty(),
            o.text("url").orEmpty(),
            o.text("createdAt").orEmpty(),
        )
    }

    private fun list(repo: CiRepo, filter: List<String>, limit: Int): List<CiRunInfo> {
        val args = listOf("run", "list", "-R", slug(repo)) + filter +
            listOf("--limit", "$limit", "--json", RUN_FIELDS)
        return parse(gh.call(args), "gh").objects().mapNotNull(::info)
    }

    private fun view(repo: CiRepo, run: String, wants: Set<String>): CiRunInfo {
        val args = listOf("run", "view", run, "-R", slug(repo), "--json", RUN_FIELDS)
        val o = parse(gh.call(args), "gh") as? JsonObject
        val found = o?.let(::info) ?: throw CiStop(
            CiProblemKind.UNREADABLE,
            "gh printed run $run in a form drift cannot read; update gh and try again",
        )
        if (o.text("conclusion") !in wants) {
            throw CiStop(
                CiProblemKind.NO_RUNS,
                "run $run concluded ${o.text("conclusion") ?: "without a result"}, not " +
                    wants.first(),
            )
        }
        return found
    }

    override fun failedRun(
        repo: CiRepo,
        branch: String?,
        commit: String?,
        run: String?,
    ): CiRunInfo {
        if (run != null) return view(repo, run, FAILED)
        val filter = when {
            commit != null -> listOf("--commit", commit)

            branch != null -> listOf("--branch", branch)

            else -> throw CiStop(
                CiProblemKind.NO_RUNS,
                "HEAD is detached, so there is no branch to look at; pass --commit SHA or --run ID",
            )
        }
        return list(repo, filter + listOf("--status", "failure"), LATEST).firstOrNull()
            ?: throw CiStop(
                CiProblemKind.NO_RUNS,
                "no failed run found for ${commit?.let { "commit $it" } ?: "branch $branch"} " +
                    "in ${slug(repo)}; pass --run ID to name one",
            )
    }

    override fun passingRun(repo: CiRepo, failed: CiRunInfo, good: String?): CiRunInfo {
        if (good != null && isRunId(good)) return view(repo, good, PASSED)
        val workflow = listOf("--workflow", failed.workflowKey, "--status", "success")
        val found = if (good != null) {
            list(repo, listOf("--commit", good) + workflow, LATEST).firstOrNull()
        } else {
            val earlier = { runs: List<CiRunInfo> ->
                runs.firstOrNull { it.id != failed.id && it.createdAt < failed.createdAt }
            }
            earlier(list(repo, listOf("--branch", failed.branch) + workflow, HISTORY))
                ?: earlier(list(repo, workflow, HISTORY))
        }
        return found ?: throw CiStop(
            CiProblemKind.NO_PASSING_RUN,
            "no passing run of ${failed.workflow} found before run ${failed.id}, so there is " +
                "nothing to compare it with. Name a known good commit or run with " +
                "--good SHA|RUN_ID. Without a CI baseline, run your test command with " +
                "drift run --tools --project on a commit that passes and on this one, then " +
                "drift diagnose the two capsule directories.",
        )
    }

    override fun summary(repo: CiRepo, run: CiRunInfo): String =
        gh.call(listOf("run", "view", run.id, "-R", slug(repo), "--json", SUMMARY_FIELDS))

    override fun jobs(summary: String): List<CiJobInfo> {
        val root = parse(summary, "gh") as? JsonObject
        val jobs = (root?.fields?.get("jobs") as? JsonArray)?.items.orEmpty()
        return jobs.filterIsInstance<JsonObject>().mapNotNull {
            val id = it.id("databaseId") ?: return@mapNotNull null
            val name = it.text("name") ?: return@mapNotNull null
            CiJobInfo(id, name, it.text("conclusion") in FAILED)
        }
    }

    override fun log(repo: CiRepo, run: CiRunInfo, job: CiJobInfo): String = gh.call(
        listOf("run", "view", run.id, "-R", slug(repo), "--log", "--job", job.id),
        log = true,
    )

    override fun import(log: String, summary: String, label: String, job: String): Import =
        GithubImport.run(log, summary, label, job)

    private companion object {
        const val RUN_FIELDS = "databaseId,workflowName,workflowDatabaseId,headSha,headBranch," +
            "url,createdAt,event,conclusion"
        const val SUMMARY_FIELDS = "databaseId,conclusion,jobs"
        const val LATEST = 20
        const val HISTORY = 50
        val FAILED = setOf("failure", "timed_out")
        val PASSED = setOf("success")
    }
}
