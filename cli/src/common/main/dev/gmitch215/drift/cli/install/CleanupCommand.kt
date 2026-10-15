package dev.gmitch215.drift.cli.install

/**
 * The `cmd.exe` line that deletes [paths] and then [directories] after a short pause, or null
 * when a name holds a character that `cmd.exe` would expand or unquote.
 */
internal fun cleanupCommand(paths: List<String>, directories: List<String>): String? {
    if ((paths + directories).any { it.contains('%') || it.contains('"') }) return null
    val commands = paths.map { "del /f /q \"$it\"" } + directories.map { "rmdir \"$it\"" }
    return "cmd.exe /d /s /c \"ping -n 3 127.0.0.1 >nul & ${commands.joinToString(" & ")}\""
}
