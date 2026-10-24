package dev.gmitch215.drift.scan.ci

import dev.gmitch215.drift.ci.GitlabImport
import dev.gmitch215.drift.ci.Import
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonObject

/** GitLab pipelines through `glab api`; `GITLAB_HOST` and `glab auth login` decide the server. */
class GitlabCi(host: Host, private val site: String) : CiProvider {
    override val kind = CiProviderKind.GITLAB
    private val glab =
        CiTool(host, "glab", "glab auth login", "https://gitlab.com/gitlab-org/cli", site)

    private fun api(path: String, log: Boolean = false): String {
        val target = if (site == "gitlab.com") listOf(path) else listOf("--hostname", site, path)
        return glab.call(listOf("api") + target, log)
    }

    private fun project(repo: CiRepo) = "projects/" + repo.path.replace("/", "%2F")

    private fun info(o: JsonObject): CiRunInfo? {
        val source = o.text("source").orEmpty()
        val id = o.id("id") ?: return null
        return CiRunInfo(
            id,
            "pipeline $id from $source",
            "pipeline ($source)",
            source,
            o.text("sha") ?: return null,
            o.text("ref").orEmpty(),
            o.text("web_url").orEmpty(),
            o.text("created_at").orEmpty(),
        )
    }

    private fun list(repo: CiRepo, status: String, perPage: Int, filter: String): List<CiRunInfo> {
        val path = "${project(repo)}/pipelines?status=$status&per_page=$perPage$filter"
        return parse(api(path), "glab").objects().mapNotNull(::info)
    }

    private fun view(repo: CiRepo, id: String, status: String): CiRunInfo {
        val o = parse(api("${project(repo)}/pipelines/$id"), "glab") as? JsonObject
        val found = o?.let(::info) ?: throw CiStop(
            CiProblemKind.UNREADABLE,
            "glab printed pipeline $id in a form drift cannot read; update glab and try again",
        )
        if (o.text("status") != status) {
            throw CiStop(
                CiProblemKind.NO_RUNS,
                "pipeline $id has status ${o.text("status") ?: "unknown"}, not $status",
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
        if (run != null) return view(repo, run, "failed")
        val filter = when {
            commit != null -> "&sha=$commit"

            branch != null -> "&ref=${escape(branch)}"

            else -> throw CiStop(
                CiProblemKind.NO_RUNS,
                "HEAD is detached, so there is no branch to look at; pass --commit SHA or --run ID",
            )
        }
        return list(repo, "failed", LATEST, filter).firstOrNull() ?: throw CiStop(
            CiProblemKind.NO_RUNS,
            "no failed pipeline found for ${commit?.let { "commit $it" } ?: "branch $branch"} " +
                "in ${repo.path}; pass --run ID to name one",
        )
    }

    override fun passingRun(repo: CiRepo, failed: CiRunInfo, good: String?): CiRunInfo {
        if (good != null && isRunId(good)) return view(repo, good, "success")
        val source = "&source=${escape(failed.workflowKey)}"
        val found = if (good != null) {
            list(repo, "success", HISTORY, "&sha=$good$source").firstOrNull()
        } else {
            val earlier = { runs: List<CiRunInfo> ->
                runs.firstOrNull { it.createdAt < failed.createdAt && it.id != failed.id }
            }
            earlier(list(repo, "success", HISTORY, "&ref=${escape(failed.branch)}$source"))
                ?: earlier(list(repo, "success", HISTORY, source))
        }
        return found ?: throw CiStop(
            CiProblemKind.NO_PASSING_RUN,
            "no passing ${failed.workflow} found before pipeline ${failed.id}, so there is " +
                "nothing to compare it with. Name a known good commit or pipeline with " +
                "--good SHA|PIPELINE_ID. Without a CI baseline, run your test command with " +
                "drift run --tools --project on a commit that passes and on this one, then " +
                "drift diagnose the two capsule directories.",
        )
    }

    override fun summary(repo: CiRepo, run: CiRunInfo): String =
        api("${project(repo)}/pipelines/${run.id}/jobs?per_page=$JOBS")

    override fun jobs(summary: String): List<CiJobInfo> = parse(summary, "glab").objects()
        .sortedBy { it.id("id")?.toLongOrNull() ?: Long.MAX_VALUE }
        .mapNotNull {
            val id = it.id("id") ?: return@mapNotNull null
            val name = it.text("name") ?: return@mapNotNull null
            val optional = (it.fields["allow_failure"] as? JsonBool)?.value == true
            CiJobInfo(id, name, it.text("status") == "failed" && !optional)
        }

    override fun log(repo: CiRepo, run: CiRunInfo, job: CiJobInfo): String =
        api("${project(repo)}/jobs/${job.id}/trace", log = true)

    override fun import(log: String, summary: String, label: String, job: String): Import =
        GitlabImport.run(log, summary, label)

    private fun escape(value: String) = value.replace("%", "%25").replace("&", "%26")
        .replace("#", "%23").replace("+", "%2B").replace(" ", "%20")

    private companion object {
        const val LATEST = 20
        const val HISTORY = 100
        const val JOBS = 100
    }
}
