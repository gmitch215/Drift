package dev.gmitch215.drift.scan.project

private val pinned =
    Regex("^([A-Za-z0-9][A-Za-z0-9._-]*)\\s*(?:\\[[^\\]]*])?\\s*===?\\s*([^\\s;,*]+)")
private val requirement = Regex("^\\s*([A-Za-z0-9][A-Za-z0-9._-]*)")

/** PEP 503 name: lowercase, with runs of `-`, `_` and `.` as one `-`. */
internal fun normalizeName(name: String) = name.lowercase().replace(Regex("[-_.]+"), "-")

internal fun python(files: ProjectScanner.Files, out: ProjectScanner.Out) {
    val manifest = files.read("pyproject.toml")?.let(Toml::parse).orEmpty()
    val direct = mutableListOf<String>()
    for (table in manifest) {
        when {
            table.name == "project" -> {
                Toml.string(table.values["requires-python"])?.let {
                    out.put("requires-python", it, "pyproject.toml")
                }
                Toml.strings(table.values["dependencies"]).forEach { spec ->
                    requirement.find(spec)?.let { direct += normalizeName(it.groupValues[1]) }
                }
            }

            table.name == "tool.poetry.dependencies" -> for ((key, value) in table.values) {
                if (key == "python") {
                    Toml.string(value)?.let { out.put("requires-python", it, "pyproject.toml") }
                } else {
                    direct += normalizeName(key)
                }
            }
        }
    }
    val resolved = linkedMapOf<String, String>()
    var file = ""
    for (lock in listOf("uv.lock", "poetry.lock")) {
        val text = files.read(lock) ?: continue
        for (table in Toml.parse(text).filter { it.name == "package" }) {
            val name = Toml.string(table.values["name"]) ?: continue
            val version = Toml.string(table.values["version"]) ?: continue
            val source = table.values["source"].orEmpty()
            if ("virtual" in source || "editable" in source) continue
            resolved.keep(normalizeName(name), version)
        }
        if (resolved.isNotEmpty()) {
            file = lock
            break
        }
    }
    if (file.isEmpty()) {
        files.read("requirements.txt")?.let { text ->
            for (line in text.lines()) {
                val spec = line.substringBefore(" #").substringBefore(";").trim()
                pinned.find(spec)?.takeIf { '*' !in spec }?.let {
                    resolved.keep(normalizeName(it.groupValues[1]), it.groupValues[2])
                }
            }
            file = "requirements.txt"
        }
    }
    out.packages(direct, resolved, file)
}
