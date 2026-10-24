package dev.gmitch215.drift.scan.ci

import dev.gmitch215.drift.host.Host

enum class CiProviderKind(val id: String) {
    GITHUB("github"),
    GITLAB("gitlab"),
}

/** The checkout [root], the remote's [host] and [path] (`owner/name`, or `group/sub/name`). */
data class CiRepo(
    val root: String,
    val host: String,
    val path: String,
    val remote: String,
    val branch: String?,
    val head: String,
)

enum class CiProblemKind {
    NOT_A_CHECKOUT,
    UNSUPPORTED_HOST,
    MISSING_TOOL,
    NOT_LOGGED_IN,
    NO_NETWORK,
    RATE_LIMITED,
    NOT_FOUND,
    NO_RUNS,
    NO_PASSING_RUN,
    NO_JOB,
    LOG_EXPIRED,
    TIMED_OUT,
    UNREADABLE,
    FAILED,
    USAGE,
    WRITE,
}

/** Why `drift ci` stopped; [message] holds fixed text and never a service response. */
class CiStop(val kind: CiProblemKind, message: String) : Exception(message)

object CiRepos {
    fun detect(host: Host, remote: String): CiRepo {
        val root = git(host, "rev-parse", "--show-toplevel")
            ?: throw CiStop(
                CiProblemKind.NOT_A_CHECKOUT,
                "this directory is not inside a git checkout; run drift ci from your repository, " +
                    "or save the logs and use drift import",
            )
        val url = git(host, "remote", "get-url", remote) ?: throw CiStop(
            CiProblemKind.NOT_A_CHECKOUT,
            "this checkout has no remote named $remote; pass --remote NAME (git remote lists them)",
        )
        val (site, path) = parseRemote(url) ?: throw CiStop(
            CiProblemKind.UNSUPPORTED_HOST,
            "cannot read a host and repository name from the URL of remote $remote",
        )
        val branch = git(host, "rev-parse", "--abbrev-ref", "HEAD")?.takeIf { it != "HEAD" }
        val head = git(host, "rev-parse", "HEAD")
            ?: throw CiStop(CiProblemKind.NOT_A_CHECKOUT, "this checkout has no commits yet")
        return CiRepo(root, site, path, remote, branch, head)
    }

    /** The host and path of a remote URL without its credentials; null if unreadable. */
    fun parseRemote(url: String): Pair<String, String>? {
        val text = url.trim()
        val site: String
        val rest: String
        if ("://" in text) {
            val scheme = text.substringBefore("://")
            val after = text.substringAfter("://")
            val hostPort = after.substringBefore('/').substringAfterLast('@')
            site = if (scheme.startsWith("http")) hostPort else hostPort.substringBefore(':')
            rest = after.substringAfter('/', "")
        } else {
            val colon = text.indexOf(':')
            if (colon < 1 || '/' in text.substring(0, colon)) return null
            site = text.substring(0, colon).substringAfterLast('@')
            rest = text.substring(colon + 1)
        }
        val parts = rest.removeSuffix("/").removeSuffix(".git").split('/')
            .filter(String::isNotEmpty)
        if (site.length < 2 || parts.size < 2) return null
        return site.lowercase() to parts.joinToString("/")
    }

    /** `github.com` and `GH_HOST` are GitHub; `gitlab.com` and `GITLAB_HOST` are GitLab. */
    fun providerFor(site: String, env: Map<String, String>): CiProviderKind? {
        fun bare(value: String?) = value?.substringAfter("://")?.trimEnd('/')?.lowercase()
        return when {
            site == "github.com" || site == bare(env["GH_HOST"]) -> CiProviderKind.GITHUB
            site == "gitlab.com" || site == bare(env["GITLAB_HOST"]) -> CiProviderKind.GITLAB
            else -> null
        }
    }

    private fun git(host: Host, vararg args: String): String? {
        val result = host.run(listOf("git") + args) ?: return null
        return result.output.trim().takeIf { result.exitCode == 0 && it.isNotEmpty() }
    }
}
