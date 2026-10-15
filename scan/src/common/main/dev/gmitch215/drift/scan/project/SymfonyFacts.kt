package dev.gmitch215.drift.scan.project

private const val FRAMEWORK = "config/packages/framework.yaml"
private val routerUtf8 = Regex("^[ \\t]+utf8:[ \\t]*([^\\s#]+)")

private fun indent(line: String) = line.takeWhile { it == ' ' || it == '\t' }.length

internal fun symfonyConfig(files: ProjectScanner.Files, out: ProjectScanner.Out) {
    val lines = files.read(FRAMEWORK)?.lines() ?: return
    if (lines.none { it.startsWith("framework:") }) return
    val start = lines.indexOfFirst { it.trim() == "router:" }
    val value = if (start < 0) {
        null
    } else {
        lines.drop(start + 1)
            .takeWhile { it.isBlank() || indent(it) > indent(lines[start]) }
            .firstNotNullOfOrNull { routerUtf8.find(it)?.groupValues?.get(1) }
    }
    val shown = value?.trim('\'', '"')?.lowercase() ?: "unset"
    out.put("config.framework-router-utf8", shown, FRAMEWORK)
}
