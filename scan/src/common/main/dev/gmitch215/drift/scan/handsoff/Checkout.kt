package dev.gmitch215.drift.scan.handsoff

import dev.gmitch215.drift.ci.LooseJson
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonObject

/** A file, and the line of it that a fact came from when the line is known. */
data class Source(val file: String, val line: Int? = null) {
    override fun toString() = if (line == null) file else "$file:$line"
}

/** The files of a checkout. Paths are relative to the checkout root, with `/` separators. */
interface Checkout {
    fun read(path: String): String?

    /** The names in [dir]; null when this host cannot list, empty when [dir] is not there. */
    fun list(dir: String): List<String>?
}

/**
 * Reads a checkout through [Host.readText]. Listing a directory is the one thing a [Host] has no
 * call for, so it starts `ls -1 <dir>`, which reads a directory and executes nothing from it.
 */
class HostCheckout(private val host: Host, root: String) : Checkout {
    private val prefix = root.trimEnd('/').let { if (it.isEmpty()) "" else "$it/" }

    override fun read(path: String): String? = host.readText(prefix + path)?.replace("\r\n", "\n")

    override fun list(dir: String): List<String>? {
        if (host.os == "windows") return null
        val result = host.run(listOf("ls", "-1", prefix + dir)) ?: return null
        if (result.exitCode != 0) return emptyList()
        return result.output.lines().filter { it.isNotBlank() }.sorted()
    }
}

/** A text file with line lookups, so each fact can name where it was read. */
internal class Doc(val file: String, val text: String) {
    val lines: List<String> = text.split('\n')

    /** The first line at or after [from] (0-based index) that matches, with its match. */
    fun find(pattern: Regex, from: Int = 0, until: Int = lines.size): Hit? {
        for (i in from until minOf(until, lines.size)) {
            pattern.find(lines[i])?.let { return Hit(i, it) }
        }
        return null
    }

    /** The 0-based range of the lines under the `[header]` line, or null when there is none. */
    fun section(header: String): IntRange? {
        val start = lines.indexOfFirst { it.trim() == "[$header]" }
        if (start < 0) return null
        val end = (start + 1 until lines.size).firstOrNull {
            lines[it].trimStart().startsWith("[")
        } ?: lines.size
        return start + 1 until end
    }

    /** The line of a pretty-printed JSON key reached through [path], or null. */
    fun jsonLine(vararg path: String): Int? {
        var at = 0
        var found = -1
        for (key in path) {
            val pattern = Regex("^\\s*\"" + Regex.escape(key) + "\"\\s*:")
            found = (at until lines.size).firstOrNull { pattern.containsMatchIn(lines[it]) }
                ?: return null
            at = found
        }
        return found + 1
    }

    fun source(index: Int?) = Source(file, index?.plus(1))
}

internal class Hit(val index: Int, val match: MatchResult) {
    val line: Int get() = index + 1
}

internal fun Checkout.doc(path: String): Doc? = read(path)?.let { Doc(path, it) }

internal fun Checkout.json(path: String): JsonObject? {
    val text = read(path) ?: return null
    return try {
        LooseJson.parse(text) as? JsonObject
    } catch (_: JsonException) {
        null
    }
}
