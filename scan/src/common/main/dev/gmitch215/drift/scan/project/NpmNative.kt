package dev.gmitch215.drift.scan.project

import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue

internal const val MAX_NATIVE = 50

internal fun nativeArgv(root: String) = listOf(
    "find", "$root/node_modules", "-maxdepth", "3", "-name", "package.json",
    "-exec", "grep", "-l", "-E", "\"(os|cpu)\"[[:space:]]*:", "{}", "+",
)

private fun JsonValue?.list(): List<String> = when (this) {
    is JsonString -> listOf(value)
    is JsonArray -> items.mapNotNull { it.text() }
    else -> emptyList()
}

/**
 * Packages installed in `node_modules` whose manifest has an `os` or `cpu` field: the variant npm
 * picked on the machine that ran the install. `native.<name>` is the version and
 * `native.<name>.platform` is `os-cpu[-libc]`; `native.platforms` lists the distinct platforms.
 */
internal fun npmNative(
    host: Host,
    files: ProjectScanner.Files,
    dir: String,
    out: ProjectScanner.Out,
) {
    if (host.os == "windows") return
    val root = dir.trimEnd('/').ifEmpty { "." }
    val listing = host.run(nativeArgv(root))?.output ?: return
    val found = linkedMapOf<String, Pair<String, String>>()
    for (line in listing.lines().map { it.trim().removePrefix("$root/") }.sorted()) {
        val parts = line.split('/')
        val scoped = parts.getOrNull(1)?.startsWith("@") == true
        val depth = if (scoped) 4 else 3
        if (parts.size != depth || parts.first() != "node_modules" ||
            parts.last() != "package.json"
        ) {
            continue
        }
        val name = parts.subList(1, depth - 1).joinToString("/")
        val manifest = files.json(line) ?: continue
        val os = manifest["os"].list()
        val cpu = manifest["cpu"].list()
        val version = manifest["version"].text()
        if (version == null || (os.isEmpty() && cpu.isEmpty())) continue
        val libc = manifest["libc"].list()
        val platform = listOf(os, cpu, libc).filter { it.isNotEmpty() }
            .joinToString("-") { it.joinToString("+") }
        found[name] = version to platform
    }
    val kept = found.entries.take(MAX_NATIVE)
    for ((name, entry) in kept) {
        out.put("native.$name", entry.first, "$name/package.json")
        out.put("native.$name.platform", entry.second, "$name/package.json")
    }
    val platforms = found.values.map { it.second }.distinct().sorted()
    out.put("native.platforms", platforms.joinToString(","), "node_modules")
    if (found.size >
        kept.size
    ) {
        out.put("native-omitted", "${found.size - kept.size}", "node_modules")
    }
}
