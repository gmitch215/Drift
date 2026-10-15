package dev.gmitch215.drift.scan.project

private val roots = listOf("", "wp/", "wordpress/", "web/wp/", "public/wp/", "public/", "web/")
private val wpVersion = Regex("\\\$wp_version[ \\t]*=[ \\t]*'([0-9][^']*)'")

internal fun wordpress(files: ProjectScanner.Files, out: ProjectScanner.Out) {
    for (root in roots) {
        val path = "${root}wp-includes/version.php"
        val text = files.read(path) ?: continue
        val version = wpVersion.find(text)?.groupValues?.get(1) ?: continue
        out.put("version", version, path)
        return
    }
}
