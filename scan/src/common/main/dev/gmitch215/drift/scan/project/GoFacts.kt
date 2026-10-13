package dev.gmitch215.drift.scan.project

private val goDirective = Regex("^(go|toolchain)\\s+(\\S+)")
private val goRequire = Regex("^(?:require\\s+)?(\\S+)\\s+(v\\S+)(\\s*//\\s*indirect)?")

internal fun go(files: ProjectScanner.Files, out: ProjectScanner.Out) {
    val text = files.read("go.mod") ?: return
    val direct = mutableListOf<String>()
    val resolved = linkedMapOf<String, String>()
    var inRequire = false
    for (raw in text.lines()) {
        val line = raw.trim()
        when {
            line.startsWith("require (") -> inRequire = true

            inRequire && line == ")" -> inRequire = false

            inRequire || line.startsWith("require ") -> goRequire.find(line)?.let {
                val (name, version) = it.destructured
                resolved.keep(name, version)
                if (it.groupValues[3].isEmpty()) direct += name
            }

            else -> goDirective.find(line)?.let {
                val (kind, value) = it.destructured
                val key = if (kind == "go") "version" else "toolchain"
                out.put(key, value.removePrefix("go"), "go.mod")
            }
        }
    }
    out.packages(direct, resolved, "go.mod")
}
