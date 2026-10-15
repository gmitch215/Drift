package dev.gmitch215.drift.scan.project

private val extensionFiles = listOf(
    "config/sync/core.extension.yml",
    "config/default/core.extension.yml",
    "sites/default/config/sync/core.extension.yml",
    "web/sites/default/config/sync/core.extension.yml",
)
private val settingsFiles = listOf(
    "web/sites/default/settings.php",
    "sites/default/settings.php",
    "docroot/sites/default/settings.php",
)
private val extensionEntry = Regex("^[ \\t]+([a-z0-9_]+):")
private val tablePrefix = Regex("'prefix'[ \\t]*=>[ \\t]*(?:\\[|array[ \\t]*\\()")
private val renamedModules = mapOf("ckeditor" to "ckeditor4")

internal fun drupal(files: ProjectScanner.Files, out: ProjectScanner.Out) {
    extensionFiles.firstNotNullOfOrNull { files.read(it) }?.let { text ->
        val known = mapOf("module" to "modules", "theme" to "themes")
            .mapValues { (_, group) -> group to out.ruleNames("$group.") }
        var section = ""
        for (line in text.lines()) {
            if (line.isNotEmpty() && !line[0].isWhitespace()) {
                section = line.substringBefore(':').trim()
                continue
            }
            val (group, names) = known[section] ?: continue
            val raw = extensionEntry.find(line)?.groupValues?.get(1) ?: continue
            val name = if (section == "module") renamedModules[raw] ?: raw else raw
            if (name in names) out.put("$group.$name", "true", "core.extension.yml")
        }
    }
    val settings = settingsFiles.firstNotNullOfOrNull { path ->
        files.read(path)?.let { path to it }
    }
    if (settings != null && tablePrefix.containsMatchIn(settings.second)) {
        out.put("settings.db-prefix-table-array", "true", settings.first)
    }
}
