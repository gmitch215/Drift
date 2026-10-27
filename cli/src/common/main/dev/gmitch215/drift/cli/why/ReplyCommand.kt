package dev.gmitch215.drift.cli.why

import dev.gmitch215.drift.redact.Redactor

/**
 * The one command that carries a reply back to the folder that holds the session, for a reply
 * given on a machine that has none: `printf '%s\n' 'line' ... | drift why --reply [--works]`.
 * The text is redacted first, control and non-ASCII characters are replaced, and every line is
 * one single-quoted argument, so nothing in it can be run by the shell that prints it.
 */
internal object ReplyCommand {
    const val MAX_BYTES = 2048

    /** The command, or null when the cleaned reply is longer than [MAX_BYTES]. */
    fun build(reply: String, works: Boolean, session: String?): String? {
        val lines = clean(reply)
        if (lines.sumOf { it.length + 1 } > MAX_BYTES) return null
        val flags = (if (works) " --works" else "") +
            (session?.let { " --session ${quote(it)}" } ?: "")
        return "printf '%s\\n' " + lines.joinToString(" ") { quote(it) } +
            " | drift why --reply$flags"
    }

    /** The lines of [reply] as they are printed: redacted, ASCII, no trailing blank lines. */
    fun clean(reply: String): List<String> {
        val text = Redactor.redactText(reply.replace("\r\n", "\n").replace('\r', '\n'))
        val lines = text.split('\n').map { line ->
            line.map { c ->
                when {
                    c == '\t' -> ' '
                    c < ' ' || c == '\u007f' -> null
                    c > '~' -> '?'
                    else -> c
                }
            }.filterNotNull().joinToString("").trimEnd()
        }
        return lines.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
    }

    private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"
}
