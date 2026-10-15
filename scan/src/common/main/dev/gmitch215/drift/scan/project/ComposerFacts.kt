package dev.gmitch215.drift.scan.project

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonValue

private val concrete = Regex("^\\d+(\\.\\d+){1,3}$")

private val platformNames = Regex("^(php|php-64bit|php-ipv6|php-zts|php-debug|hhvm|(ext|lib)-.+)$")

internal fun composer(files: ProjectScanner.Files, out: ProjectScanner.Out) {
    val manifest = files.json("composer.json")
    val lock = files.json("composer.lock")
    val require =
        manifest?.strings("require").orEmpty() + manifest?.strings("require-dev").orEmpty()
    val required = if (manifest != null) require else lock?.strings("platform").orEmpty()
    val requiredFile = if (manifest != null) "composer.json" else "composer.lock"
    for ((name, constraint) in required) {
        if (platformNames.matches(name)) out.put("require.$name", constraint, requiredFile)
    }
    val configured = manifest?.obj("config")?.strings("platform")
        ?: lock?.strings("platform-overrides").orEmpty()
    for ((name, version) in configured) {
        if (platformNames.matches(name) && concrete.matches(version)) {
            out.put("platform.$name", version, requiredFile)
        }
    }
    val config = manifest?.obj("config")
    if (config?.get("policy") != null) out.put("json.config.policy", "true", "composer.json")
    if (lock == null) return
    if (manifest != null) {
        val unlisted = unlistedPlugins(lock, config?.get("allow-plugins"))
        if (unlisted.isNotEmpty()) {
            out.put("json.plugins.unlisted", unlisted.joinToString(","), "composer.lock")
        }
    }
    val resolved = linkedMapOf<String, String>()
    val phpRequires = linkedMapOf<String, String>()
    for (section in listOf("packages", "packages-dev")) {
        for (entry in (lock[section] as? JsonArray)?.items.orEmpty()) {
            val pkg = entry as? JsonObject ?: continue
            val name = pkg["name"].text() ?: continue
            if (name in resolved) continue
            resolved[name] = pkg["version"].text() ?: continue
            pkg.obj("require")?.get("php").text()?.let { phpRequires[name] = it }
        }
    }
    out.packages(
        require.keys.filter { '/' in it },
        resolved,
        "composer.lock",
        "php",
        phpRequires,
    )
}

private fun unlistedPlugins(lock: JsonObject, allowed: JsonValue?): List<String> {
    if (allowed is JsonBool && allowed.value) return emptyList()
    val patterns = (allowed as? JsonObject)?.fields?.keys.orEmpty().map { glob ->
        Regex(glob.split('*').joinToString(".*") { Regex.escape(it) })
    }
    val plugins = mutableSetOf<String>()
    for (section in listOf("packages", "packages-dev")) {
        for (entry in (lock[section] as? JsonArray)?.items.orEmpty()) {
            val pkg = entry as? JsonObject ?: continue
            val name = pkg["name"].text() ?: continue
            if (pkg["type"].text() == "composer-plugin") plugins += name
        }
    }
    return plugins.sorted().filter { name -> patterns.none { it.matches(name) } }
}
