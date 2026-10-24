package dev.gmitch215.drift.scan.handsoff

internal sealed interface Yaml {
    val line: Int
}

internal data class YScalar(val text: String, override val line: Int) : Yaml

internal data class YSeq(val items: List<Yaml>, override val line: Int) : Yaml

internal data class YEntry(val key: String, val line: Int, val value: Yaml)

internal data class YMap(val entries: List<YEntry>, override val line: Int) : Yaml {
    operator fun get(key: String): Yaml? = entries.firstOrNull { it.key == key }?.value

    fun entry(key: String): YEntry? = entries.firstOrNull { it.key == key }
}

internal fun Yaml?.scalar(): String? = (this as? YScalar)?.text

internal fun Yaml?.map(): YMap? = this as? YMap

internal fun Yaml?.seq(): List<Yaml> = (this as? YSeq)?.items.orEmpty()

/** A construct [YamlLite] does not read, with the line it starts on. */
internal data class YamlGap(val line: Int, val what: String)

internal class YamlDoc(val root: Yaml?, val gaps: List<YamlGap>)

/**
 * The block-style subset that CI files use: mappings, sequences, plain and quoted scalars, block
 * scalars (`|` and `>`) and one-line flow collections, each with its line. Anchors, aliases,
 * merge keys, tags, tab indentation, multi-line flow collections and later documents are not
 * read; each is reported as a gap so a caller can refuse the file instead of guessing.
 */
internal object YamlLite {
    fun parse(text: String): YamlDoc {
        val parser = Parser(text.replace("\r\n", "\n").split('\n'))
        val root = parser.block(0)
        return YamlDoc(root, parser.gaps)
    }

    private class Parser(source: List<String>) {
        val lines = source.toMutableList()
        val gaps = mutableListOf<YamlGap>()
        var pos = 0
        var started = false

        fun indent(text: String) = text.length - text.trimStart(' ').length

        fun blank(text: String) = text.isBlank() || text.trimStart().startsWith("#")

        fun skip() {
            while (pos < lines.size) {
                val text = lines[pos]
                val trimmed = text.trim()
                if (trimmed == "---" || trimmed.startsWith("--- ")) {
                    if (started) {
                        gaps += YamlGap(pos + 1, "a second document")
                        pos = lines.size
                        return
                    }
                    pos++
                } else if (trimmed == "...") {
                    pos = lines.size
                } else if (blank(text)) {
                    pos++
                } else {
                    started = true
                    val tab = text.trimStart(' ').startsWith("\t")
                    if (tab && gaps.none { it.line == pos + 1 }) {
                        gaps += YamlGap(pos + 1, "a tab used as indentation")
                    }
                    return
                }
            }
        }

        fun block(min: Int): Yaml? {
            skip()
            if (pos >= lines.size) return null
            val text = lines[pos]
            val at = indent(text)
            if (at < min) return null
            val body = text.substring(at)
            return when {
                body == "-" || body.startsWith("- ") -> seq(at)

                keySplit(body) != null -> map(at)

                else -> {
                    val line = pos + 1
                    pos++
                    value(strip(body), line, at - 1)
                }
            }
        }

        fun seq(at: Int): Yaml {
            val first = pos + 1
            val items = mutableListOf<Yaml>()
            while (true) {
                skip()
                if (pos >= lines.size || indent(lines[pos]) != at) break
                val body = lines[pos].substring(at)
                if (body != "-" && !body.startsWith("- ")) break
                val rest = body.removePrefix("-").trimStart(' ')
                val col = at + (body.length - rest.length)
                val line = pos + 1
                if (rest.isEmpty() || rest.startsWith("#")) {
                    pos++
                    items += block(at + 1) ?: YScalar("", line)
                } else if (rest == "-" || rest.startsWith("- ") || keySplit(rest) != null) {
                    lines[pos] = " ".repeat(col) + rest
                    items += block(col) ?: YScalar("", line)
                } else {
                    pos++
                    items += value(strip(rest), line, at)
                }
            }
            return YSeq(items, first)
        }

        fun map(at: Int): Yaml {
            val first = pos + 1
            val entries = mutableListOf<YEntry>()
            while (true) {
                skip()
                if (pos >= lines.size || indent(lines[pos]) != at) break
                val body = lines[pos].substring(at)
                if (body == "-" || body.startsWith("- ")) break
                val (key, rest) = keySplit(body) ?: break
                val line = pos + 1
                if (key == "<<") gaps += YamlGap(line, "a merge key")
                pos++
                val text = strip(rest)
                val value = if (text.isEmpty()) {
                    skip()
                    val next = lines.getOrNull(pos)
                    val sameIndentSeq = next != null && indent(next) == at &&
                        next.substring(at).let { it == "-" || it.startsWith("- ") }
                    if (next != null && (indent(next) > at || sameIndentSeq)) {
                        block(if (sameIndentSeq) at else at + 1) ?: YScalar("", line)
                    } else {
                        YScalar("", line)
                    }
                } else {
                    value(text, line, at)
                }
                entries += YEntry(key, line, value)
            }
            return YMap(entries, first)
        }

        fun value(text: String, line: Int, parent: Int): Yaml {
            val head = text.firstOrNull()
            return when {
                head == '|' || head == '>' -> blockScalar(text, line, parent)

                head == '[' || head == '{' -> flow(text, line)

                head == '&' || head == '*' || head == '!' ->
                    YScalar(text, line).also {
                        gaps += YamlGap(line, "an anchor, alias or tag")
                    }

                else -> YScalar(unquote(text), line)
            }
        }

        fun blockScalar(header: String, line: Int, parent: Int): Yaml {
            val folded = header.startsWith(">")
            val body = mutableListOf<String>()
            var floor = -1
            while (pos < lines.size) {
                val text = lines[pos]
                if (text.isBlank()) {
                    body += ""
                    pos++
                    continue
                }
                val at = indent(text)
                if (at <= parent) break
                if (floor < 0) floor = at
                if (at < floor) break
                body += text.substring(floor)
                pos++
            }
            while (body.lastOrNull() == "") body.removeLast()
            val joined = if (folded) body.joinToString(" ") else body.joinToString("\n")
            return YScalar(joined, line)
        }

        fun flow(text: String, line: Int): Yaml {
            if (depth(text) != 0) {
                gaps += YamlGap(line, "a flow collection that spans lines")
                return YScalar(text, line)
            }
            val end = if (text.startsWith("[")) ']' else '}'
            val inner = text.substring(1, text.lastIndexOf(end).takeIf { it > 0 } ?: text.length)
            val parts = splitTop(inner)
            if (text.startsWith("[")) {
                return YSeq(parts.map { value(it.trim(), line, -1) }, line)
            }
            val entries = parts.mapNotNull { part ->
                val (key, rest) = keySplit(part.trim()) ?: return@mapNotNull null
                YEntry(key, line, value(strip(rest), line, -1))
            }
            return YMap(entries, line)
        }
    }

    private fun depth(text: String): Int {
        var depth = 0
        var quote = ' '
        for (c in text) {
            when {
                quote != ' ' -> if (c == quote) quote = ' '
                c == '"' || c == '\'' -> quote = c
                c == '[' || c == '{' -> depth++
                c == ']' || c == '}' -> depth--
            }
        }
        return depth
    }

    private fun splitTop(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var quote = ' '
        var start = 0
        for ((i, c) in text.withIndex()) {
            when {
                quote != ' ' -> if (c == quote) quote = ' '

                c == '"' || c == '\'' -> quote = c

                c == '[' || c == '{' -> depth++

                c == ']' || c == '}' -> depth--

                c == ',' && depth == 0 -> {
                    parts += text.substring(start, i)
                    start = i + 1
                }
            }
        }
        parts += text.substring(start)
        return parts.filter { it.isNotBlank() }
    }

    /** The key and the text after its colon, or null when [body] is not a `key: value` line. */
    private fun keySplit(body: String): Pair<String, String>? {
        if (body.startsWith("[") || body.startsWith("{") || body.startsWith("#")) return null
        var quote = ' '
        for ((i, c) in body.withIndex()) {
            when {
                quote != ' ' -> if (c == quote) quote = ' '

                (c == '"' || c == '\'') && i == 0 -> quote = c

                c == ':' && (i == body.length - 1 || body[i + 1] == ' ') ->
                    return unquote(body.substring(0, i).trim()) to body.substring(i + 1)

                c == '#' && i > 0 && body[i - 1] == ' ' -> return null
            }
        }
        return null
    }

    /** Removes a trailing ` # comment` that sits outside quotes, then trims. */
    private fun strip(text: String): String {
        var quote = ' '
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote == '\'' && c == '\'' && text.getOrNull(i + 1) == '\'' -> i++

                quote != ' ' -> if (c == quote) quote = ' '

                (c == '"' || c == '\'') && (i == 0 || text[i - 1] == ' ' || text[i - 1] == '[') ->
                    quote = c

                c == '#' && (i == 0 || text[i - 1] == ' ') -> return text.substring(0, i).trim()
            }
            i++
        }
        return text.trim()
    }

    private fun unquote(text: String): String {
        val t = text.trim()
        if (t.length < 2 || t.first() != t.last()) return t
        return when (t.first()) {
            '"' -> t.substring(1, t.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            '\'' -> t.substring(1, t.length - 1).replace("''", "'")
            else -> t
        }
    }
}
