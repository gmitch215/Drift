package dev.gmitch215.drift.tools.corpus

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.string
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path

/** Reads the corpus file written by [CorpusBuild.assemble], one issue at a time. */
object CorpusReader {
    fun parse(line: String): CorpusIssue {
        val o = CanonicalJson.parse(line.removePrefix(",")) as JsonObject
        return CorpusIssue(
            o["repo"]!!.string(),
            o["number"]!!.long().toInt(),
            o["created"]!!.string(),
            o["state"]!!.string(),
            o["title"]!!.string(),
            (o["author"] as? JsonString)?.value,
            o["body"]!!.string(),
        )
    }

    /** Issues of [lines] in file order: the first line and the closing line are framing. */
    fun issues(lines: Sequence<String>): Sequence<CorpusIssue> =
        lines.drop(1).takeWhile { !it.startsWith("]") }.map(::parse)

    /** Runs [block] over the issues of a `.json` or `.json.zst` file (read with `zstd -dc`). */
    fun <T> use(path: Path, block: (Sequence<CorpusIssue>) -> T): T {
        if (!path.toString().endsWith(".zst")) {
            return Files.newBufferedReader(path).use { block(issues(it.lineSequence())) }
        }
        val process = ProcessBuilder("zstd", "-dc", "--long=27", path.toString()).start()
        val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8), BUFFER)
        try {
            val result = block(issues(reader.lineSequence()))
            reader.close()
            process.destroy()
            return result
        } catch (e: IOException) {
            process.destroyForcibly()
            throw e
        }
    }

    private const val BUFFER = 1 shl 20
}
