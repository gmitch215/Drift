package dev.gmitch215.drift.scan.project

private val pinFiles = listOf(
    "node" to listOf(".nvmrc", ".node-version"),
    "python" to listOf(".python-version"),
)

internal fun pins(files: ProjectScanner.Files, out: ProjectScanner.Out) {
    for ((tool, names) in pinFiles) {
        for (name in names) {
            val version = files.read(name)?.lines()?.map { it.trim() }
                ?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?.removePrefix("v") ?: continue
            out.put(tool, version, name)
            break
        }
    }
}

private val sumLine = Regex("^([0-9a-f]{64}) [ *]([A-Za-z0-9._/-]+)$")

internal fun checksums(files: ProjectScanner.Files, out: ProjectScanner.Out) {
    val text = files.read("SHA256SUMS") ?: return
    for (line in text.lines()) {
        sumLine.find(line.trim())?.let {
            out.put(it.groupValues[2], it.groupValues[1], "SHA256SUMS")
        }
    }
}
