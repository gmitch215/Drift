package dev.gmitch215.drift.triage

import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj

/**
 * A check the report does not answer. [family] is an attribute family key prefix (`tool.node`,
 * `limits.nofile`, `os.release`), [command] the fixed command to run where it fails and [why]
 * what the report leaves out. [CAPTURE] is the family of the whole-machine capture.
 */
data class Ask(val family: String, val command: List<String>, val why: String) {
    /** The command as typed. */
    val line: String get() = command.joinToString(" ")

    /**
     * The command with its output fed to `drift why --reply`; [session] names the session file
     * when it is not the default one.
     */
    fun piped(session: String? = null): String {
        val reply = if (session == null) REPLY else "$REPLY --session $session"
        return when {
            family == CAPTURE -> "$line && $reply < ${command.last()}"
            family == CleanRun.FAMILY || family == CleanRun.ERROR_TEXT -> "$line 2>&1 | $reply"
            console -> "$reply < $CONSOLE_FILE"
            else -> "$line | $reply"
        }
    }

    /**
     * True when the same check can also be run where the program works and its output compared:
     * a single command whose output is read as facts (not the capture, the clean run, the error
     * text or a console command).
     */
    val supportsWorks: Boolean
        get() = family != CAPTURE && family != CleanRun.FAMILY && family != CleanRun.ERROR_TEXT &&
            !console

    /** The command with its output fed to the works-side answer of `drift why --reply`. */
    fun pipedWorks(session: String? = null): String {
        val reply = if (session == null) "$REPLY --works" else "$REPLY --works --session $session"
        return "$line | $reply"
    }

    private fun worksCommand(session: String?) = if (session == null) {
        "drift why --reply --works"
    } else {
        "drift why --reply --works --session $session"
    }

    /**
     * The paste form of the works-side step: the session lives where the first answer was given,
     * so the check runs on the other machine and its output is pasted here.
     */
    fun pasteWorks(session: String? = null): String =
        "Run $line where it works. If you run it there with `| ${worksCommand(session)}`, Drift " +
            "prints one line to copy back to where you started; or copy the output, go back to " +
            "where you started (the folder where you ran drift why), run " +
            "`${worksCommand(session)}`, paste it and press Ctrl-D (Ctrl-Z then Enter on Windows)."

    /** The works-side step for a machine that can be reached by ssh. */
    fun sshWorks(session: String? = null): String =
        "If you can reach that machine by ssh: `ssh USER@HOST $line | ${worksCommand(session)}`"

    /** A command typed into a server console, not a shell: its output goes to a file. */
    val console: Boolean get() = command.first().startsWith("/")

    /** The one plain sentence that asks for it. */
    fun sentence(): String = if (family == CleanRun.ERROR_TEXT) {
        "To tell, run the command from the report where it fails and paste what it prints " +
            "(the command comes from the report, so read it before you run it): $why."
    } else if (family == CleanRun.FAMILY) {
        "To tell, run the command from the report in a clean container and paste what it " +
            "prints (the command comes from the report, so read it before you run it): $why."
    } else if (family == CAPTURE) {
        "To tell, run $line where it fails and feed the file to Drift: $why."
    } else if (console) {
        "To tell, run $line in the server console, save the output to $CONSOLE_FILE and feed " +
            "the file to Drift: $why."
    } else {
        "To tell, run $line where it fails and paste the result: $why."
    }

    /** [works] adds `pipedWorks` for a check that can also be run where the program works. */
    fun toJson(session: String? = null, works: Boolean = false): JsonObject {
        val base = obj(
            "family" to JsonString(family),
            "command" to JsonString(line),
            "piped" to JsonString(piped(session)),
        )
        return if (works && supportsWorks) {
            JsonObject(
                base.fields + mapOf(
                    "pipedWorks" to JsonString(pipedWorks(session)),
                    "pasteWorks" to JsonString(pasteWorks(session)),
                ),
            )
        } else {
            base
        }
    }

    companion object {
        const val CAPTURE = "capture"
        private const val REPLY = "drift why --reply"
        private const val CONSOLE_FILE = "console-output.txt"
        const val MAX = 3
    }
}
