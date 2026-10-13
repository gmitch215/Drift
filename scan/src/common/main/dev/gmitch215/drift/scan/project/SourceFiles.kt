package dev.gmitch215.drift.scan.project

import dev.gmitch215.drift.host.Host

/** Source text per language ecosystem, and how many files the caps left out of each. */
internal class SourceTree(val texts: Map<String, List<String>>, val omitted: Map<String, Int>) {
    companion object {
        val EMPTY = SourceTree(emptyMap(), emptyMap())
    }
}

/**
 * Lists a project's source files with `find` and reads a bounded, sorted subset. Hosts that cannot
 * run `find` (Windows, wasm) give no source facts.
 */
internal object SourceFiles {
    const val MAX_FILES = 500
    const val MAX_FILE_CHARS = 200_000
    const val MAX_TOTAL_CHARS = 16_000_000
    private const val MAX_DEPTH = 12

    val skippedDirs = listOf(
        "node_modules", "vendor", "build", ".gradle", "venv", ".venv", "target", "dist", ".git",
        ".tox", "__pycache__", "site-packages",
    )

    private val ecosystems = mapOf(
        "py" to "python",
        "java" to "java",
        "kt" to "java",
        "php" to "php",
        "js" to "js",
        "mjs" to "js",
        "cjs" to "js",
        "jsx" to "js",
        "ts" to "js",
        "mts" to "js",
        "cts" to "js",
        "tsx" to "js",
    )

    private val order = listOf("python", "java", "php", "js")

    fun argv(root: String): List<String> {
        val argv = mutableListOf("find", root, "-maxdepth", "$MAX_DEPTH", "(")
        skippedDirs.forEachIndexed { i, name ->
            if (i > 0) argv += "-o"
            argv += listOf("-name", name)
        }
        argv += listOf(")", "-prune", "-o", "-type", "f", "(")
        ecosystems.keys.forEachIndexed { i, ext ->
            if (i > 0) argv += "-o"
            argv += listOf("-name", "*.$ext")
        }
        argv += listOf(")", "-print")
        return argv
    }

    fun read(host: Host, files: ProjectScanner.Files, dir: String): SourceTree {
        if (host.os == "windows") return SourceTree.EMPTY
        val root = dir.trimEnd('/').ifEmpty { "." }
        val output = host.run(argv(root))?.output ?: return SourceTree.EMPTY
        val byEcosystem = output.lines()
            .map { it.trim().removePrefix("$root/") }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .mapNotNull { path ->
                val ecosystem = ecosystems[path.substringAfterLast('.', "")]
                val segments = path.split('/').dropLast(1)
                val skipped = segments.any { it in skippedDirs }
                if (ecosystem == null || skipped) null else ecosystem to path
            }
            .groupBy({ it.first }, { it.second })
        val texts = linkedMapOf<String, List<String>>()
        val omitted = linkedMapOf<String, Int>()
        var budget = MAX_TOTAL_CHARS
        for (ecosystem in order) {
            val paths = byEcosystem[ecosystem] ?: continue
            val read = mutableListOf<String>()
            for (path in paths.take(MAX_FILES)) {
                val text = files.read(path) ?: continue
                if (text.length > MAX_FILE_CHARS || text.length > budget) continue
                budget -= text.length
                read += text
            }
            texts[ecosystem] = read
            if (paths.size > MAX_FILES) omitted[ecosystem] = paths.size - MAX_FILES
        }
        return SourceTree(texts, omitted)
    }
}
