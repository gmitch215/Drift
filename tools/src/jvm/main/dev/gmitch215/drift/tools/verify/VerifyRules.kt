package dev.gmitch215.drift.tools.verify

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.jsonOrNull
import dev.gmitch215.drift.json.obj
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.schema.FailsafeSchema
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

class FetchResult(
	val status: Int?,
	val contentType: String?,
	val finalUrl: String?,
	val error: String? = null,
)

fun interface Fetcher {
	fun get(url: String): FetchResult
}

class JdkFetcher(private val timeout: Duration = Duration.ofSeconds(20)) : Fetcher {
	private val client = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NORMAL)
		.connectTimeout(timeout)
		.build()

	override fun get(url: String): FetchResult = try {
		val request = HttpRequest.newBuilder(URI.create(url))
			.timeout(timeout)
			.header("User-Agent", "drift-verify-rules")
			.GET()
			.build()
		val response = client.send(request, HttpResponse.BodyHandlers.discarding())
		FetchResult(
			response.statusCode(),
			response.headers().firstValue("content-type").orElse(null),
			response.uri().toString(),
		)
	} catch (e: java.io.IOException) {
		FetchResult(null, null, null, e::class.simpleName + ": " + e.message)
	} catch (e: InterruptedException) {
		Thread.currentThread().interrupt()
		FetchResult(null, null, null, "interrupted")
	} catch (e: IllegalArgumentException) {
		FetchResult(null, null, null, "bad url: " + e.message)
	}
}

enum class Outcome(val id: String) {
	OK(
	"ok",
),
	HTTP_ERROR("http-error"),
	ERROR("error"),
	SKIPPED("skipped"),
}

class UrlCheck(
	val ruleId: String,
	val url: String,
	val outcome: Outcome,
	val status: Int?,
	val contentType: String?,
	val finalUrl: String?,
	val error: String?,
	val checkedAt: String,
)

object VerifyRules {
	private val yaml = Load(LoadSettings.builder().setSchema(FailsafeSchema()).build())

	fun sources(rulesDir: Path): List<Pair<String, String>> = rulesDir
		.listDirectoryEntries("*.yml")
		.sortedBy { it.fileName.toString() }
		.flatMap { file ->
			val rule = yaml.loadFromString(file.readText()) as Map<*, *>
			val id = rule["id"] as String
			(rule["provenance"] as List<*>).map { id to (it as Map<*, *>)["url"] as String }
		}

	fun run(
		rulesDir: Path,
		fetcher: Fetcher,
		offline: Boolean,
		now: () -> Instant = Instant::now,
	): List<UrlCheck> {
		val cache = mutableMapOf<String, FetchResult>()
		return sources(rulesDir).map { (id, url) ->
			if (offline || !url.startsWith("http")) {
				val why = if (offline) null else "not an http url"
				UrlCheck(id, url, Outcome.SKIPPED, null, null, null, why, now().toString())
			} else {
				val r = cache.getOrPut(url) { fetcher.get(url) }
				val outcome = when {
					r.status == null -> Outcome.ERROR
					r.status in 200..299 -> Outcome.OK
					else -> Outcome.HTTP_ERROR
				}
				val at = now().toString()
				UrlCheck(id, url, outcome, r.status, r.contentType, r.finalUrl, r.error, at)
			}
		}
	}

	fun report(checks: List<UrlCheck>): String = CanonicalJson.encode(
		JsonArray(
			checks.map {
				obj(
					"rule" to JsonString(it.ruleId),
					"url" to JsonString(it.url),
					"outcome" to JsonString(it.outcome.id),
					"status" to (it.status?.let { s -> JsonInt(s.toLong()) } ?: jsonOrNull(null)),
					"contentType" to jsonOrNull(it.contentType),
					"finalUrl" to jsonOrNull(it.finalUrl),
					"error" to jsonOrNull(it.error),
					"checkedAt" to JsonString(it.checkedAt),
				)
			},
		),
	) + "\n"

	fun failed(checks: List<UrlCheck>): List<UrlCheck> =
		checks.filter { it.outcome == Outcome.HTTP_ERROR || it.outcome == Outcome.ERROR }
}

fun main(args: Array<String>) {
	val offline = "--offline" in args
	val positional = args.filter { !it.startsWith("--") }
	if (positional.size != 2) {
		System.err.println("usage: VerifyRules <rules-dir> <report.json> [--offline]")
		exitProcess(2)
	}
	val checks = VerifyRules.run(Path.of(positional[0]), JdkFetcher(), offline)
	Path.of(positional[1]).writeText(VerifyRules.report(checks))
	val bad = VerifyRules.failed(checks)
	for (c in bad) System.err.println("${c.ruleId}: ${c.url} ${c.status ?: c.error}")
	val ok = checks.count { it.outcome == Outcome.OK }
	println("${checks.size} urls, $ok ok, ${bad.size} failed")
	if (bad.isNotEmpty()) exitProcess(1)
}
