package dev.gmitch215.drift.scan.project

import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject

private val bunEntry = Regex(
    "^\\s*\"([^\"]+)\":\\s*\\[\"((?:@[^/\"@]+/)?[^/\"@]+)@([^\"]+)\"",
    RegexOption.MULTILINE,
)
private val yarnVersion = Regex("^ {2}version:? \"?([^\"\\s]+)\"?", RegexOption.MULTILINE)
private val pnpmKey =
    Regex("^ {2}'?/?((?:@[^/@'\\s]+/)?[^/@'\\s]+)(?:@|/)(\\d[^:'(\\s_]*)", RegexOption.MULTILINE)
private val pnpmEngineNode = Regex("\\bnode:\\s*(?:'([^']*)'|\"([^\"]*)\"|([^,}\\s]+))")
private val pnpmSections = setOf("dependencies", "devDependencies", "optionalDependencies")
private val nodeTsScript =
    Regex("(?:^|[\\s;&|])node(?:\\s+--?[\\w-]+(?:=\\S+)?)*\\s+\\S+\\.(?:ts|mts|cts)(?:\\s|$)")

private fun isTsEntry(path: String?) =
    path != null && listOf(".ts", ".mts", ".cts").any { path.endsWith(it) } &&
        !path.endsWith(".d.ts")

private fun tsEntry(manifest: JsonObject): Boolean {
    val bin = manifest["bin"]
    val bins = if (bin is JsonObject) bin.fields.values.map { it.text() } else listOf(bin.text())
    return isTsEntry(manifest["main"].text()) || bins.any(::isTsEntry) ||
        manifest.strings("scripts").values.any { nodeTsScript.containsMatchIn(it) }
}

internal fun npm(files: ProjectScanner.Files, out: ProjectScanner.Out) {
    val manifest = files.json("package.json")
    if (manifest != null && tsEntry(manifest)) out.put("ts-entry", "true", "package.json")
    val ranges = listOf("dependencies", "devDependencies", "optionalDependencies")
        .flatMap { manifest?.strings(it).orEmpty().entries }
        .associate { it.key to it.value }
    manifest?.obj("engines")?.let { engines ->
        for (name in listOf("node", "npm")) {
            out.put("engines.$name", engines[name].text(), "package.json")
        }
    }
    val locked = lockfile(files, ranges, out) ?: return
    out.packages(ranges.keys, locked.resolved, locked.file, "node", locked.engines)
}

/** [engines] holds the `node` constraint each package records, when the format has one. */
private class Locked(
    val file: String,
    val resolved: Map<String, String>,
    val engines: Map<String, String> = emptyMap(),
)

private fun lockfile(
    files: ProjectScanner.Files,
    ranges: Map<String, String>,
    out: ProjectScanner.Out,
): Locked? {
    for (name in listOf("package-lock.json", "npm-shrinkwrap.json")) {
        files.json(name)?.let { lock ->
            val version = (lock["lockfileVersion"] as? JsonInt)?.value
            out.put("lockfile-version", version?.toString(), name)
            return packageLock(name, lock)
        }
    }
    files.read("pnpm-lock.yaml")?.let { return pnpm(it) }
    files.read("yarn.lock")?.let { return Locked("yarn.lock", yarn(it, ranges)) }
    files.read("bun.lock")?.let { return Locked("bun.lock", bun(it)) }
    return null
}

private fun packageLock(file: String, lock: JsonObject): Locked {
    val resolved = linkedMapOf<String, String>()
    val engines = linkedMapOf<String, String>()
    val prefix = "node_modules/"
    lock.obj("packages")?.fields?.forEach { (key, value) ->
        val name = key.removePrefix(prefix)
        val entry = value as? JsonObject
        if (key.startsWith(prefix) && prefix !in name && name !in resolved) {
            val version = entry?.get("version").text() ?: return@forEach
            resolved[name] = version
            entry?.obj("engines")?.get("node").text()?.let { engines[name] = it }
        }
    }
    if (resolved.isEmpty()) {
        lock.obj("dependencies")?.fields?.forEach { (name, value) ->
            (value as? JsonObject)?.get("version").text()?.let { resolved.keep(name, it) }
        }
    }
    return Locked(file, resolved, engines)
}

private fun bun(text: String): Map<String, String> {
    val resolved = linkedMapOf<String, String>()
    for (m in bunEntry.findAll(text)) {
        val (key, name, version) = m.destructured
        if (key == name) resolved.keep(name, version)
    }
    return resolved
}

/** Classic (`version "1.2.3"`) and berry (`version: 1.2.3`) lockfiles; the exact range wins. */
private fun yarn(text: String, ranges: Map<String, String>): Map<String, String> {
    val bySpec = hashMapOf<String, String>()
    val byName = linkedMapOf<String, String>()
    for (block in text.split("\n\n")) {
        val lines = block.trim('\n').lines()
        val header = lines.firstOrNull() ?: continue
        if (header.startsWith("#") || header.startsWith(" ") || !header.endsWith(":")) continue
        val version = yarnVersion.find(block)?.groupValues?.get(1) ?: continue
        for (spec in header.dropLast(1).split(",").map { it.trim().trim('"') }) {
            val at = spec.indexOf('@', 1)
            if (at < 0 || spec.substring(at + 1).startsWith("workspace:")) continue
            bySpec[spec] = version
            byName.keep(spec.substring(0, at), version)
        }
    }
    val resolved = byName.toMutableMap()
    for ((name, range) in ranges) {
        (bySpec["$name@$range"] ?: bySpec["$name@npm:$range"])?.let { resolved[name] = it }
    }
    return resolved
}

private fun pnpm(text: String): Locked {
    val all = text.lines()
    val lines = all.drop(all.lastIndexOf("---") + 1)
    val resolved = linkedMapOf<String, String>()
    for ((name, version) in pnpmDirect(lines)) resolved[name] = version
    val engines = hashMapOf<String, String>()
    val start = lines.indexOf("packages:")
    if (start >= 0) {
        val body = lines.drop(start + 1).takeWhile { it.isEmpty() || it.startsWith(" ") }
        var entry = ""
        for (line in body) {
            val key = pnpmKey.find(line)
            if (key != null) {
                entry = "${key.groupValues[1]}@${key.groupValues[2]}"
                resolved.keep(key.groupValues[1], key.groupValues[2])
            } else if (line.startsWith("    engines:")) {
                pnpmEngineNode.find(line)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
                    ?.let { engines[entry] = it }
            }
        }
    }
    val kept = resolved.mapNotNull { (name, version) ->
        engines["$name@$version"]?.let { name to it }
    }
    return Locked("pnpm-lock.yaml", resolved, kept.toMap())
}

/** Versions of the root importer's dependencies, from the v5, v6 and v9 layouts. */
private fun pnpmDirect(lines: List<String>): Map<String, String> {
    val importers = lines.indexOf("importers:")
    val scope = if (importers < 0) {
        lines to 0
    } else {
        val root = lines.drop(importers + 1).dropWhile { it != "  .:" }.drop(1)
        root.takeWhile { it.startsWith("   ") } to 4
    }
    val (body, indent) = scope
    val found = linkedMapOf<String, String>()
    var i = 0
    while (i < body.size) {
        val line = body[i++]
        if (line.trim().removeSuffix(":") !in pnpmSections || !line.endsWith(":") ||
            line.length - line.trimStart().length != indent
        ) {
            continue
        }
        while (i < body.size && body[i].length - body[i].trimStart().length > indent) {
            val entry = body[i++]
            if (entry.length - entry.trimStart().length != indent + 2) continue
            val text = entry.trim()
            val name = text.substringBefore(": ").removeSuffix(":").trim('\'', '"')
            var version = text.substringAfter(": ", "").trim('\'', '"')
            if (version.isEmpty()) {
                var j = i
                while (j < body.size && body[j].length - body[j].trimStart().length > indent + 2) {
                    if (body[j].trim().startsWith("version:")) {
                        version = body[j].substringAfter("version:").trim().trim('\'', '"')
                    }
                    j++
                }
            }
            version = version.substringBefore('(')
            if (version.firstOrNull()?.isDigit() == true) found[name] = version
        }
    }
    return found
}
