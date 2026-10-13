package dev.gmitch215.drift.tools.corpus

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import java.io.BufferedWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/** What a build wrote: hashes and sizes of the JSON and of its zstd file, and counts per repo. */
class CorpusSummary(
    val jsonSha256: String,
    val jsonBytes: Long,
    val zstSha256: String?,
    val zstBytes: Long?,
    val counts: Map<String, Int>,
) {
    fun toJson(): JsonObject = obj(
        "counts" to JsonObject(counts.toSortedMap().mapValues { JsonInt(it.value.toLong()) }),
        "jsonBytes" to JsonInt(jsonBytes),
        "jsonSha256" to JsonString(jsonSha256),
        "total" to JsonInt(counts.values.sum().toLong()),
        "zstBytes" to JsonInt(zstBytes ?: 0),
        "zstSha256" to JsonString(zstSha256.orEmpty()),
    )
}

/**
 * Builds the triage corpus. Each repo is paged newest first into `<work>/<owner>__<name>.jsonl`
 * (already redacted, one canonical JSON issue per line) with a `.state` file beside it, so a
 * stopped run resumes at the last whole page. [assemble] sorts everything into one file whose
 * bytes depend only on those lines.
 */
class CorpusBuild(
    private val manifest: CorpusManifest,
    private val source: PageSource,
    private val work: Path,
    private val log: (String) -> Unit = {},
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val clean = CorpusClean(manifest.salt)

    private class State(val cursor: String?, val count: Int, val done: Boolean)

    private fun file(repo: CorpusRepo, ext: String): Path =
        work.resolve(repo.repo.replace('/', '_').lowercase() + "__" + ext)

    private fun load(repo: CorpusRepo): State {
        val state = file(repo, "state")
        if (!state.exists()) return State(null, 0, false)
        val map = state.readLines().associate { it.substringBefore('=') to it.substringAfter('=') }
        return State(
            map["cursor"]?.takeIf { it.isNotEmpty() },
            map.getValue("count").toInt(),
            map["done"] == "true",
        )
    }

    private fun save(repo: CorpusRepo, state: State) {
        val tmp = file(repo, "state.tmp")
        val text = "cursor=${state.cursor.orEmpty()}\ncount=${state.count}\ndone=${state.done}\n"
        tmp.writeText(text)
        Files.move(tmp, file(repo, "state"), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Fetches [repo] up to the cap and returns how many issues are stored. */
    fun fetch(repo: CorpusRepo): Int {
        Files.createDirectories(work)
        var state = load(repo)
        val data = file(repo, "jsonl")
        if (data.exists()) {
            val lines = data.readLines()
            if (lines.size != state.count) {
                data.writeText(lines.take(state.count).joinToString("") { it + "\n" })
            }
        } else if (state.count > 0) {
            throw IOException("${repo.repo}: state says ${state.count} issues but $data is missing")
        }
        var pages = 0
        while (!state.done) {
            val page = pageWithRetry(repo, state.cursor)
            var count = state.count
            val kept = StringBuilder()
            for (raw in page.issues) {
                if (count >= manifest.cap) break
                if (raw.created.take(DAY) > manifest.cutoff) continue
                kept.append(CanonicalJson.encode(clean.issue(repo.repo, raw).toJson())).append('\n')
                count++
            }
            Files.writeString(
                data,
                kept,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
            state = State(page.next, count, page.next == null || count >= manifest.cap)
            save(repo, state)
            pages++
            if (pages % LOG_EVERY == 0) log("${repo.repo}: ${state.count} issues, $pages pages")
        }
        log("${repo.repo}: done, ${state.count} issues")
        return state.count
    }

    private fun pageWithRetry(repo: CorpusRepo, cursor: String?): Page {
        var size = PAGE
        var attempt = 0
        while (true) {
            try {
                return source.page(repo, cursor, size)
            } catch (e: IOException) {
                attempt++
                if (attempt >= ATTEMPTS) throw e
                log("${repo.repo}: attempt $attempt failed (${e.message}), retrying at size $size")
                size = maxOf(MIN_PAGE, size / 2)
                pause(BACKOFF_MS * attempt)
            }
        }
    }

    fun fetchAll(): Map<String, Int> = manifest.repos.associate { it.repo to fetch(it) }

    /** Writes the corpus JSON to [out]; every repo must be done. */
    fun assemble(out: Path): CorpusSummary {
        val repos = manifest.repos.sortedBy { it.repo.lowercase() }
        val digest = MessageDigest.getInstance("SHA-256")
        val counts = mutableMapOf<String, Int>()
        var bytes = 0L
        Files.newBufferedWriter(out).use { writer ->
            fun emit(w: BufferedWriter, text: String) {
                val raw = text.toByteArray(Charsets.UTF_8)
                digest.update(raw)
                bytes += raw.size
                w.write(text)
            }
            val cutoff = CanonicalJson.encode(JsonString(manifest.cutoff))
            emit(writer, "{\"cutoff\":$cutoff,\"issues\":[\n")
            var first = true
            for (repo in repos) {
                check(load(repo).done) { "${repo.repo} is not fully fetched" }
                val lines = file(repo, "jsonl").takeIf { it.exists() }?.readLines().orEmpty()
                val sorted = lines.sortedBy { NUMBER.find(it)!!.groupValues[1].toInt() }
                counts[repo.repo] = sorted.size
                for (line in sorted) {
                    emit(writer, (if (first) "" else ",") + line + "\n")
                    first = false
                }
            }
            val sources = JsonArray(
                repos.map {
                    obj(
                        "count" to JsonInt(counts.getValue(it.repo).toLong()),
                        "form_policy" to JsonString(it.formPolicy),
                        "host" to JsonString(it.host),
                        "repo" to JsonString(it.repo),
                        "stratum" to JsonString(it.stratum),
                    )
                },
            )
            emit(writer, "],\"schema\":1,\"sources\":${CanonicalJson.encode(sources)}}\n")
        }
        return CorpusSummary(hex(digest.digest()), bytes, null, null, counts)
    }

    companion object {
        private const val PAGE = 100
        private const val MIN_PAGE = 5
        private const val ATTEMPTS = 6
        private const val BACKOFF_MS = 15_000L
        private const val LOG_EVERY = 10
        private const val DAY = 10
        private val NUMBER = Regex("\"number\":(\\d+)")

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        fun sha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return hex(digest.digest())
        }

        /** `zstd -19` over [json] into [zst], then `zstd -t`; returns the summary with hashes. */
        fun compress(summary: CorpusSummary, json: Path, zst: Path): CorpusSummary {
            val flags = listOf("-19", "--long=27", "-T1", "-q", "-f")
            run(listOf("zstd") + flags + listOf("-o", zst.toString(), json.toString()))
            run(listOf("zstd", "-t", "-q", zst.toString()))
            return CorpusSummary(
                summary.jsonSha256,
                summary.jsonBytes,
                sha256(zst),
                Files.size(zst),
                summary.counts,
            )
        }

        private fun run(args: List<String>) {
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
            if (p.waitFor() != 0) throw IOException("${args.first()} failed: $text")
        }
    }
}
