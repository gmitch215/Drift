package dev.gmitch215.drift.scan.project

/** One `[name]` table or `[[name]]` array element; values keep their raw right-hand side. */
internal class TomlTable(val name: String, val values: Map<String, String>)

/**
 * The subset of TOML that version catalogs and Python lockfiles use: tables, array tables, and
 * `key = value` lines where a value may span lines inside brackets or braces.
 */
internal object Toml {
    fun parse(text: String): List<TomlTable> {
        val tables = mutableListOf<TomlTable>()
        var name = ""
        var values = linkedMapOf<String, String>()
        fun close() {
            if (name.isNotEmpty() || values.isNotEmpty()) tables += TomlTable(name, values)
        }
        val lines = text.lines().iterator()
        while (lines.hasNext()) {
            val line = stripComment(lines.next()).trim()
            if (line.startsWith("[")) {
                close()
                name = line.trim('[', ']', ' ')
                values = linkedMapOf()
                continue
            }
            val eq = equals(line)
            if (eq < 0) continue
            var value = line.substring(eq + 1).trim()
            while (depth(value) > 0 &&
                lines.hasNext()
            ) {
                value += " " + stripComment(lines.next()).trim()
            }
            values[line.substring(0, eq).trim().trim('"')] = value
        }
        close()
        return tables
    }

    fun string(raw: String?): String? {
        val v = raw?.trim() ?: return null
        return if (v.length >= 2 && v[0] in "\"'" &&
            v.last() == v[0]
        ) {
            v.substring(1, v.length - 1)
        } else {
            null
        }
    }

    fun strings(raw: String?): List<String> =
        Regex("\"([^\"]*)\"|'([^']*)'").findAll(raw.orEmpty()).map {
            it.groupValues[1].ifEmpty { it.groupValues[2] }
        }.toList()

    /** The keys of an inline table, or `null` when [raw] is not one. */
    fun inline(raw: String?): Map<String, String>? {
        val v = raw?.trim() ?: return null
        if (!v.startsWith("{") || !v.endsWith("}")) return null
        val out = linkedMapOf<String, String>()
        for (part in split(v.substring(1, v.length - 1))) {
            val eq = equals(part)
            if (eq > 0) out[part.substring(0, eq).trim().trim('"')] = part.substring(eq + 1).trim()
        }
        return out
    }

    private fun split(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var quote = ' '
        var start = 0
        for ((i, c) in text.withIndex()) {
            when {
                quote != ' ' -> if (c == quote) quote = ' '

                c == '"' || c == '\'' -> quote = c

                c == '{' || c == '[' -> depth++

                c == '}' || c == ']' -> depth--

                c == ',' && depth == 0 -> {
                    parts += text.substring(start, i)
                    start = i + 1
                }
            }
        }
        parts += text.substring(start)
        return parts.filter { it.isNotBlank() }
    }

    private fun equals(line: String): Int {
        var quote = ' '
        for ((i, c) in line.withIndex()) {
            when {
                quote != ' ' -> if (c == quote) quote = ' '
                c == '"' || c == '\'' -> quote = c
                c == '=' -> return i
            }
        }
        return -1
    }

    private fun depth(value: String): Int {
        var depth = 0
        var quote = ' '
        for (c in value) {
            when {
                quote != ' ' -> if (c == quote) quote = ' '
                c == '"' || c == '\'' -> quote = c
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> depth--
            }
        }
        return depth
    }

    private fun stripComment(line: String): String {
        var quote = ' '
        for ((i, c) in line.withIndex()) {
            when {
                quote != ' ' -> if (c == quote) quote = ' '
                c == '"' || c == '\'' -> quote = c
                c == '#' -> return line.substring(0, i)
            }
        }
        return line
    }
}
