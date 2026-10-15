package dev.gmitch215.drift.scan.project

internal fun manifest(files: ProjectScanner.Files, out: ProjectScanner.Out) {
    val text = files.read("Manifest.toml") ?: return
    val tables = Toml.parse(text)
    tables.firstOrNull { it.name.isEmpty() }?.let { header ->
        out.put("julia-version", Toml.string(header.values["julia_version"]), "Manifest.toml")
        out.put("manifest-format", Toml.string(header.values["manifest_format"]), "Manifest.toml")
    }
    val resolved = linkedMapOf<String, String>()
    for (table in tables.filter { it.name.startsWith("deps.") }) {
        val version = Toml.string(table.values["version"]) ?: continue
        resolved.keep(table.name.removePrefix("deps."), version)
    }
    val direct = files.read("Project.toml")?.let(Toml::parse).orEmpty()
        .filter { it.name == "deps" }.flatMap { it.values.keys }
    out.packages(direct, resolved, "Manifest.toml")
}
