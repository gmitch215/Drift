package dev.gmitch215.drift.lab

/**
 * The project attributes that a container can carry as files of the workspace: a version file for
 * a tool (`project.pins.node` is `.nvmrc`), a requirements pin (`project.python.<name>` is a line
 * of `requirements.txt`) and a checksum pin (`project.checksums.<path>` is a line of
 * `SHA256SUMS`). The arm writes them into a copy of the workspace.
 */
object WorkspacePins {
    private val versionFiles = mapOf(
        "project.pins.node" to ".nvmrc",
        "project.pins.python" to ".python-version",
    )
    private val requirement = Regex("^project\\.python\\.([a-z0-9][a-z0-9._-]*)$")
    private val version = Regex("^[0-9]+(\\.[0-9]+)*$")
    private val checksum = Regex("^project\\.checksums\\.([A-Za-z0-9._/-]+)$")
    private val digest = Regex("^[0-9a-f]{64}$")

    fun handles(path: String): Boolean =
        path in versionFiles || requirement.matches(path) || checksum.matches(path)

    /** The workspace file that carries the attribute at [path], or null. */
    fun fileOf(path: String): String? =
        versionFiles[path] ?: "requirements.txt".takeIf { requirement.matches(path) }
            ?: "SHA256SUMS".takeIf { checksum.matches(path) }

    /** Whether [value] is a plain version, or a sha256 digest for a checksum pin. */
    fun valid(path: String, value: String): Boolean =
        if (checksum.matches(path)) digest.matches(value) else version.matches(value)

    /** The workspace files that [attributes] ask for, by file name. */
    fun files(attributes: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for ((path, name) in versionFiles) {
            attributes[path]?.takeIf { valid(path, it) }?.let { out[name] = "$it\n" }
        }
        val lines = attributes.entries.sortedBy { it.key }.mapNotNull { (path, value) ->
            requirement.matchEntire(path)?.takeIf { valid(path, value) }
                ?.let { "${it.groupValues[1]}==$value\n" }
        }
        if (lines.isNotEmpty()) out["requirements.txt"] = lines.joinToString("")
        val sums = attributes.entries.sortedBy { it.key }.mapNotNull { (path, value) ->
            checksum.matchEntire(path)?.takeIf { digest.matches(value) }
                ?.let { "$value  ${it.groupValues[1]}\n" }
        }
        if (sums.isNotEmpty()) out["SHA256SUMS"] = sums.joinToString("")
        return out
    }
}
