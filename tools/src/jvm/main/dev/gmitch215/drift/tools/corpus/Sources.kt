package dev.gmitch215.drift.tools.corpus

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.string
import java.io.IOException
import java.net.URLEncoder

/** One page of issues, newest first. [next] is the cursor of the next page, null at the end. */
class Page(val issues: List<RawIssue>, val next: String?)

fun interface PageSource {
    /** Reads up to [size] issues of [repo] after [cursor] (null for the first page). */
    fun page(repo: CorpusRepo, cursor: String?, size: Int): Page
}

class SourceException(message: String) : IOException(message)

/** Runs a command and returns its output; the real one starts `gh` or `glab`. */
fun interface Shell {
    fun run(args: List<String>): String
}

object ProcessShell : Shell {
    private const val ERR_CHARS = 300

    override fun run(args: List<String>): String {
        val process = ProcessBuilder(args).start()
        val err = StringBuilder()
        val reader = Thread { err.append(process.errorStream.readBytes().toString(Charsets.UTF_8)) }
        reader.start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        reader.join()
        process.waitFor()
        if (process.exitValue() != 0) {
            val why = err.toString().take(ERR_CHARS)
            throw SourceException("${args.first()} exited ${process.exitValue()}: $why")
        }
        return out
    }
}

/** GitHub issues through `gh api graphql`, which lists issues only and never pull requests. */
class GithubSource(private val shell: Shell = ProcessShell) : PageSource {
    override fun page(repo: CorpusRepo, cursor: String?, size: Int): Page {
        val (owner, name) = repo.repo.split("/", limit = 2)
        val args = mutableListOf(
            "gh", "api", "graphql",
            "-f", "query=$QUERY",
            "-f", "owner=$owner",
            "-f", "name=$name",
            "-F", "size=$size",
        )
        if (cursor != null) args += listOf("-f", "cursor=$cursor")
        return parse(shell.run(args))
    }

    companion object {
        private const val QUERY = "query(\$owner: String!, \$name: String!, \$size: Int!, " +
            "\$cursor: String) { repository(owner: \$owner, name: \$name) { " +
            "issues(first: \$size, after: \$cursor, orderBy: {field: CREATED_AT, " +
            "direction: DESC}) { pageInfo { hasNextPage endCursor } nodes { number createdAt " +
            "state title body author { login } } } } }"

        fun parse(text: String): Page {
            val root = CanonicalJson.parse(text) as? JsonObject
                ?: throw SourceException("no object")
            (root["errors"] as? JsonArray)?.let { throw SourceException("graphql: ${it.items}") }
            val data = (root["data"] as? JsonObject)?.get("repository") as? JsonObject
                ?: throw SourceException("no repository in the response")
            val issues = data["issues"] as JsonObject
            val info = issues["pageInfo"] as JsonObject
            val more = (info["hasNextPage"] as? JsonBool)?.value == true
            val issuesList = (issues["nodes"] as JsonArray).items.map { node ->
                val n = node as JsonObject
                RawIssue(
                    n["number"]!!.long().toInt(),
                    n["createdAt"]!!.string(),
                    n["state"]!!.string().lowercase(),
                    n["title"]!!.string(),
                    login(n["author"], "login"),
                    (n["body"] as? JsonString)?.value.orEmpty(),
                )
            }
            val next = if (more) info["endCursor"]!!.string() else null
            return Page(issuesList, next)
        }
    }
}

/** GitLab issues through `glab api`, paged by number; the cursor is the next page number. */
class GitlabSource(private val shell: Shell = ProcessShell) : PageSource {
    override fun page(repo: CorpusRepo, cursor: String?, size: Int): Page {
        val number = cursor?.toInt() ?: 1
        val project = URLEncoder.encode(repo.repo, Charsets.UTF_8)
        val path = "projects/$project/issues?state=all&order_by=created_at&sort=desc" +
            "&per_page=$size&page=$number"
        return parse(shell.run(listOf("glab", "api", path)), size, number)
    }

    companion object {
        fun parse(text: String, size: Int, number: Int): Page {
            val list = CanonicalJson.parse(text) as? JsonArray ?: throw SourceException("no array")
            val items = list.items
            val issues = items.map { node ->
                val n = node as JsonObject
                RawIssue(
                    n["iid"]!!.long().toInt(),
                    n["created_at"]!!.string(),
                    if (n["state"]!!.string() == "opened") "open" else "closed",
                    n["title"]!!.string(),
                    login(n["author"], "username"),
                    (n["description"] as? JsonString)?.value.orEmpty(),
                )
            }
            return Page(issues, if (items.size >= size) (number + 1).toString() else null)
        }
    }
}

private fun login(author: JsonValue?, key: String): String? =
    ((author as? JsonObject)?.get(key) as? JsonString)?.value

/** Sends each repo to the source for its host. */
class HostSource(private val github: PageSource, private val gitlab: PageSource) : PageSource {
    override fun page(repo: CorpusRepo, cursor: String?, size: Int): Page = when (repo.host) {
        "github" -> github.page(repo, cursor, size)
        "gitlab" -> gitlab.page(repo, cursor, size)
        else -> throw SourceException("no source for host ${repo.host}")
    }
}
