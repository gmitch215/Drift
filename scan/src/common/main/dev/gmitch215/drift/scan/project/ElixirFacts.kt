package dev.gmitch215.drift.scan.project

private val mixEntry = Regex("^\\s*\"([^\"]+)\":\\s*\\{:hex,\\s*:[A-Za-z0-9_]+,\\s*\"([^\"]+)\"")

internal fun mix(files: ProjectScanner.Files, out: ProjectScanner.Out) {
    val text = files.read("mix.lock") ?: return
    val resolved = linkedMapOf<String, String>()
    for (line in text.lines()) {
        mixEntry.find(line)?.let { resolved.keep(it.groupValues[1], it.groupValues[2]) }
    }
    out.packages(emptyList(), resolved, "mix.lock")
}
