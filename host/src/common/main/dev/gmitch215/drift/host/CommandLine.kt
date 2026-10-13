package dev.gmitch215.drift.host

internal class WindowsArgumentException(message: String) : Exception(message)

private const val DEFAULT_PATHEXT = ".COM;.EXE;.BAT;.CMD"
private val BATCH_EXTENSIONS = setOf("bat", "cmd")
private const val CMD_REFUSED = "\"%\r\n\u0000"
private const val CMD_SPECIAL = " \t&|<>^()!,;="

/**
 * Joins [argv] into one Windows command line. CommandLineToArgvW parses the result back into the
 * same arguments; no shell re-parses it.
 */
internal fun windowsCommandLine(argv: List<String>): String = argv.joinToString(" ") {
    windowsArgument(it)
}

/**
 * The command line CreateProcessW runs for [argv]. A program that resolves to a `.bat` or `.cmd`
 * file runs through `cmd.exe /c` with cmd-safe quoting; any other program runs directly.
 */
internal fun windowsLaunchLine(
    argv: List<String>,
    env: Map<String, String>,
    systemDirectory: String,
    isFile: (String) -> Boolean,
): String {
    val script = argv.firstOrNull()?.let { windowsBatchScript(it, env, isFile) }
        ?: return windowsCommandLine(argv)
    return "\"$systemDirectory\\cmd.exe\" /d /e:ON /v:OFF /c \"" +
        windowsBatchCommandLine(script, argv.drop(1)) + "\""
}

/**
 * The first existing file [program] resolves to through PATH and PATHEXT, when that file is a
 * `.bat` or `.cmd`. Null when nothing matches or the first match is not a batch script.
 */
internal fun windowsBatchScript(
    program: String,
    env: Map<String, String>,
    isFile: (String) -> Boolean,
): String? {
    val explicit = program.any { it == '\\' || it == '/' || it == ':' }
    val dirs = if (explicit) {
        listOf("")
    } else {
        envValue(env, "PATH").orEmpty().split(';').map { it.trim('"') }.filter { it.isNotEmpty() }
    }
    val exts = if (hasExtension(program)) {
        listOf("")
    } else {
        (envValue(env, "PATHEXT") ?: DEFAULT_PATHEXT).split(';').filter { it.isNotEmpty() }
    }
    for (dir in dirs) {
        for (ext in exts) {
            val name = program + ext
            val candidate = if (dir.isEmpty()) name else dir.trimEnd('\\') + "\\" + name
            if (isFile(candidate)) return candidate.takeIf(::isBatchScript)
        }
    }
    return null
}

/** The `cmd.exe /c` payload for [script]: the quoted script, then each argument cmd-quoted. */
internal fun windowsBatchCommandLine(script: String, args: List<String>): String {
    refuseForCmd(script, "program path")
    return (listOf("\"$script\"") + args.map(::windowsCmdArgument)).joinToString(" ")
}

private fun windowsCmdArgument(arg: String): String {
    refuseForCmd(arg, "argument")
    return windowsArgument(arg, forceQuotes = arg.any { it in CMD_SPECIAL })
}

private fun refuseForCmd(text: String, what: String) {
    if (text.any { it in CMD_REFUSED }) {
        throw WindowsArgumentException(
            "cannot pass $what to a .bat or .cmd program: it contains a quote, '%', a line break " +
                "or NUL: $text",
        )
    }
}

private fun envValue(env: Map<String, String>, name: String): String? =
    env.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

private fun isBatchScript(path: String): Boolean =
    path.substringAfterLast('.').lowercase() in BATCH_EXTENSIONS

private fun hasExtension(program: String): Boolean =
    program.substringAfterLast('\\').substringAfterLast('/').contains('.')

private fun windowsArgument(arg: String, forceQuotes: Boolean = false): String {
    if (!forceQuotes && arg.isNotEmpty() && arg.none { it == ' ' || it == '\t' || it == '"' }) {
        return arg
    }
    val out = StringBuilder("\"")
    var backslashes = 0
    for (c in arg) {
        if (c == '\\') {
            backslashes++
        } else if (c == '"') {
            out.append("\\".repeat(backslashes * 2 + 1)).append('"')
            backslashes = 0
        } else {
            out.append("\\".repeat(backslashes)).append(c)
            backslashes = 0
        }
    }
    return out.append("\\".repeat(backslashes * 2)).append('"').toString()
}
