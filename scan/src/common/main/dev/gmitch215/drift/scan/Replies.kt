package dev.gmitch215.drift.scan

import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.redact.Redactor
import dev.gmitch215.drift.scan.scanner.Scanners
import dev.gmitch215.drift.scan.scanner.ToolRegistry
import dev.gmitch215.drift.triage.Ask
import dev.gmitch215.drift.triage.CleanRun
import dev.gmitch215.drift.triage.CleanRunReading

/**
 * Reads what the reporter pasted back as the output of an [Ask]. A tool is read by the scanner
 * that runs the same command, so `node --version` is parsed exactly as `drift capture --tools`
 * parses it. The facts are redacted like a capture.
 */
object Replies {
    private val ID_KEYS = setOf("ID", "VERSION_ID", "PRETTY_NAME")
    private val NUMBER = Regex("\\d+(\\.\\d+)*")
    private val WINDOWS = Regex("\\[Version ([0-9][0-9.]*)\\]")

    /**
     * The facts in [text], an empty list when the output is empty and the command prints nothing
     * for an unset variable, or null when [text] is not the output of the command of [ask].
     */
    fun read(ask: Ask, text: String): List<Attribute>? {
        val clean = text.replace("\r", "").trim()
        val facts = when {
            ask.family == "limits.nofile" -> nofile(clean)
            ask.family == "os.release" -> osRelease(clean)
            ask.family == "os.version" -> osVersion(ask, clean)
            ask.family == "kernel.release" -> kernel(clean)
            ask.family == "env.TZ" -> zone(clean)
            ask.family == "env.LANG" -> locale(clean)
            ask.family.startsWith("limits.disk") -> disk(ask, clean)
            ask.family.startsWith("env.") -> variable(ask, clean)
            ask.command.take(2) == listOf("command", "-v") -> commandV(ask, clean)
            ask.family.startsWith("tool.") && ask.console -> platform(ask, clean)
            ask.family.startsWith("tool.") -> tool(ask, clean)
            else -> null
        }
        return facts?.map { Redactor.redact(it) }
    }

    private val DOCKER_FAILURES = listOf(
        "cannot connect to the docker daemon",
        "permission denied while trying to connect to the docker",
        "error during connect",
        "docker: error response from daemon",
        "docker: command not found",
        "command not found: docker",
        "docker: invalid",
        "docker: unknown",
    )

    /**
     * Reads [output] as what a clean container printed for the command of the report [report]
     * (see [CleanRun]). Null when it is binary or Docker's own error, which says nothing about
     * the command.
     */
    fun cleanRun(report: String, output: String): CleanRunReading? {
        if (CleanRun.isBinary(output)) return null
        val low = output.lowercase()
        if (DOCKER_FAILURES.any { low.contains(it) }) return null
        return CleanRun.read(CleanRun.failure(report), output)
    }

    private fun source(ask: Ask) = "reply:" + ask.line

    private fun attribute(ask: Ask, path: String, value: String) =
        Attribute(path, value, source(ask))

    private fun tool(ask: Ask, text: String): List<Attribute>? {
        val scanner = (Scanners.all + ToolRegistry.all).firstOrNull { it.command == ask.command }
            ?: return null
        val found = Scanners.attributes(scanner, text)
        if (found.isEmpty() || found.any { it.value == "unparsed" }) return null
        return found
    }

    /** `command -v NAME`: a path, an alias or the name itself when it exists, nothing when not. */
    private fun commandV(ask: Ask, text: String): List<Attribute>? {
        val name = ask.command[2]
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        val key = "${ask.family}.installed"
        return when {
            line == null -> listOf(attribute(ask, key, "no"))

            line == name || line.startsWith("alias ") ||
                (line.startsWith("/") && !line.contains(' ')) ->
                listOf(attribute(ask, key, "yes"))

            else -> null
        }
    }

    private fun nofile(text: String): List<Attribute>? {
        val line = text.lineSequence().firstOrNull()?.trim() ?: return null
        val ok = line == "unlimited" || (line.isNotEmpty() && line.all { it in '0'..'9' })
        return if (ok) listOf(Attribute("limits.nofile", line, "reply:ulimit -n")) else null
    }

    private fun osRelease(text: String): List<Attribute>? {
        val found = text.lineSequence().mapNotNull { line ->
            val key = line.substringBefore('=', "")
            if (key in ID_KEYS) key to line.substringAfter('=').trim().trim('"') else null
        }.filter { it.second.isNotEmpty() }.toList()
        if (found.none { it.first == "ID" || it.first == "VERSION_ID" }) return null
        val out = found.map { Attribute("os.release.${it.first}", it.second, "reply:os-release") }
        return listOf(Attribute("os.name", "linux", "reply:os-release")) + out
    }

    private fun osVersion(ask: Ask, text: String): List<Attribute>? {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: return null
        if (ask.command.first() == "ver") {
            val v = WINDOWS.find(line)?.groupValues?.get(1) ?: return null
            return listOf(
                attribute(ask, "os.name", "windows"),
                attribute(ask, "os.version", v),
            )
        }
        if (!NUMBER.matches(line)) return null
        return listOf(attribute(ask, "os.name", "macos"), attribute(ask, "os.version", line))
    }

    /** The release alone (`uname -r`), or the fields of `uname -a` and `uname -sr`. */
    private fun kernel(text: String): List<Attribute>? {
        val words = text.lineSequence().firstOrNull()?.trim()?.split(' ')
            ?.filter { it.isNotEmpty() }.orEmpty()
        val system = words.firstOrNull()
        val release = when {
            words.size == 1 && words[0][0] in '0'..'9' -> words[0]
            words.size == 2 && system in SYSTEMS -> words[1]
            words.size >= 3 && system in SYSTEMS && words[2][0] in '0'..'9' -> words[2]
            else -> return null
        }
        val os = SYSTEMS[system]
        val source = "reply:uname"
        val out = mutableListOf(Attribute("kernel.release", release, source))
        if (os != null) out += Attribute("os.name", os, source)
        return out
    }

    private val SYSTEMS = mapOf("Linux" to "linux", "Darwin" to "macos")

    /** `df -h .`: the use percentage of the last row, as `limits.disk-*`. */
    private fun disk(ask: Ask, text: String): List<Attribute>? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2 || !lines.first().startsWith("Filesystem")) return null
        val use = lines.last().split(' ').firstOrNull { it.endsWith("%") && it.length > 1 }
            ?: return null
        if (!use.dropLast(1).all { it in '0'..'9' }) return null
        return listOf(attribute(ask, ask.family, use))
    }

    /** `printenv NAME`: the value, or nothing when the variable is not set. */
    private fun variable(ask: Ask, text: String): List<Attribute> {
        val value = text.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (value.isEmpty()) emptyList() else listOf(attribute(ask, ask.family, value))
    }

    private val PLATFORM_LINE = Regex("running (\\S+) version (\\S+)")
    private val MINECRAFT = Regex("\\(MC: ([0-9][0-9.]*)\\)")
    private val BUILD = Regex("git-[A-Za-z]+-(\\d+)")
    private val DATED = Regex("version (\\d+\\.\\d+(?:\\.\\d+)?)-(\\d+)-")

    /** `/version` in a Bukkit-family console: the Minecraft version and the build number. */
    private fun platform(ask: Ask, text: String): List<Attribute>? {
        val line = PLATFORM_LINE.find(text) ?: return null
        val dated = DATED.find(text)?.groupValues
        val mc = MINECRAFT.find(text)?.groupValues?.get(1) ?: dated?.get(1) ?: return null
        val build = BUILD.find(line.groupValues[2])?.groupValues?.get(1) ?: dated?.get(2)
        val name = ask.family.removePrefix("tool.")
        return listOf(
            attribute(ask, "runtime.platform", name),
            attribute(ask, "${ask.family}.version", if (build == null) mc else "$mc-$build"),
        )
    }

    private val ZONE = Regex("^([A-Za-z_]+(/[A-Za-z0-9_+-]+)+|[A-Z][A-Z0-9+-]{1,9})$")

    /** `printenv TZ`: a zone name (`Europe/Paris`, `UTC`, `EST5EDT`), or nothing when unset. */
    private fun zone(text: String): List<Attribute>? {
        val value = text.lineSequence().firstOrNull()?.trim().orEmpty()
        return when {
            value.isEmpty() -> emptyList()
            ZONE.matches(value) -> listOf(Attribute("env.TZ", value, "reply:printenv TZ"))
            else -> null
        }
    }

    private fun locale(text: String): List<Attribute>? {
        val found = text.lineSequence().mapNotNull { line ->
            val name = line.substringBefore('=', "")
            val value = line.substringAfter('=', "").trim().trim('"')
            if ((name == "LANG" || name == "LC_ALL") && value.isNotEmpty()) name to value else null
        }.toList()
        if (text.lineSequence().none { it.startsWith("LANG=") || it.startsWith("LC_") }) {
            return null
        }
        return found.map { Attribute("env.${it.first}", it.second, "reply:locale") }
    }
}
