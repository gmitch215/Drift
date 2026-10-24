package dev.gmitch215.drift.scan.ci

import dev.gmitch215.drift.ci.Import
import dev.gmitch215.drift.ci.LooseJson
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.host.TIMED_OUT_EXIT_CODE
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue

/**
 * One CI run: a GitHub Actions run or a GitLab pipeline. [title] names it for the user,
 * [workflow] says what kind of run it is and [workflowKey] is what two runs of one workflow share.
 */
data class CiRunInfo(
    val id: String,
    val title: String,
    val workflow: String,
    val workflowKey: String,
    val sha: String,
    val branch: String,
    val url: String,
    val createdAt: String,
)

data class CiJobInfo(val id: String, val name: String, val failed: Boolean)

/**
 * Reads runs, jobs and logs through a provider's own command line tool, so its login does the
 * authentication. Every method throws [CiStop] when the tool cannot answer.
 */
interface CiProvider {
    val kind: CiProviderKind

    fun failedRun(repo: CiRepo, branch: String?, commit: String?, run: String?): CiRunInfo

    fun passingRun(repo: CiRepo, failed: CiRunInfo, good: String?): CiRunInfo

    /** The run's outcome and jobs as JSON, in the shape of the matching importer's `summary`. */
    fun summary(repo: CiRepo, run: CiRunInfo): String

    fun jobs(summary: String): List<CiJobInfo>

    fun log(repo: CiRepo, run: CiRunInfo, job: CiJobInfo): String

    fun import(log: String, summary: String, label: String, job: String): Import
}

internal class CiTool(
    private val host: Host,
    private val name: String,
    private val login: String,
    private val install: String,
    private val site: String,
) {
    fun call(args: List<String>, log: Boolean = false): String {
        val out = StringBuilder()
        val result = host.run(listOf(name) + args, TIMEOUT_MILLIS) { out.append(it) }
        return when (result?.exitCode) {
            null, NOT_FOUND -> throw CiStop(
                CiProblemKind.MISSING_TOOL,
                "$name is not installed or not on PATH; install it from $install, run $login, " +
                    "and try again",
            )

            TIMED_OUT_EXIT_CODE -> throw CiStop(
                CiProblemKind.TIMED_OUT,
                "$name did not answer within ${TIMEOUT_MILLIS / MILLIS} seconds; check the " +
                    "network connection and try again",
            )

            0 -> "$out"

            else -> throw failure("$out".lowercase(), result.exitCode, log)
        }
    }

    private fun failure(text: String, status: Int, log: Boolean): CiStop {
        fun has(vararg needles: String) = needles.any { it in text }
        return when {
            has("rate limit", "http 429", "429 too many") -> CiStop(
                CiProblemKind.RATE_LIMITED,
                "$site is rate limiting $name; wait a few minutes and run drift ci again",
            )

            has(
                "auth login",
                "not logged in",
                "http 401",
                "401 unauthorized",
                "bad credentials",
                "no token",
                "authentication required",
            ) -> CiStop(
                CiProblemKind.NOT_LOGGED_IN,
                "$name is not logged in to $site; run $login and try again",
            )

            has(
                "error connecting",
                "dial tcp",
                "no such host",
                "could not resolve host",
                "connection refused",
                "network is unreachable",
                "i/o timeout",
                "tls handshake",
            ) -> CiStop(
                CiProblemKind.NO_NETWORK,
                "cannot reach $site; check the network connection and run drift ci again",
            )

            log && has("http 404", "404 not found", "http 410", "log not found", "expired") ->
                CiStop(
                    CiProblemKind.LOG_EXPIRED,
                    "$site no longer keeps the log of this job (it expired or was deleted); " +
                        "choose a newer run with --run, --commit or --good",
                )

            has("http 404", "404 not found", "could not resolve to a repository") -> CiStop(
                CiProblemKind.NOT_FOUND,
                "$site has no such repository or run for this $name login; check the login " +
                    "with $login",
            )

            has("http 403", "403 forbidden") -> CiStop(
                CiProblemKind.FAILED,
                "$site refused the request from $name (403); the login may lack access to this " +
                    "repository's CI",
            )

            else -> CiStop(
                CiProblemKind.FAILED,
                "$name failed with status $status; run the same $name command by hand to see why",
            )
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 180_000L
        const val MILLIS = 1000
        const val NOT_FOUND = 127
    }
}

internal fun parse(text: String, tool: String): JsonValue = try {
    LooseJson.parse(text)
} catch (e: JsonException) {
    throw CiStop(
        CiProblemKind.UNREADABLE,
        "$tool printed output that drift cannot read (${e.message}); update $tool and try again",
    )
}

internal fun JsonObject.text(key: String): String? = (fields[key] as? JsonString)?.value

internal fun JsonObject.id(key: String): String? = when (val raw = fields[key]) {
    is JsonInt -> raw.value.toString()
    is JsonString -> raw.value
    else -> null
}

internal fun JsonValue.objects(): List<JsonObject> =
    (this as? JsonArray)?.items.orEmpty().mapNotNull { it as? JsonObject }

/** Run ids have at least nine digits; anything else is a commit prefix. */
internal fun isRunId(text: String): Boolean =
    text.length >= RUN_ID_DIGITS && text.all { it in '0'..'9' }

private const val RUN_ID_DIGITS = 9
