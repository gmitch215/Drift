package dev.gmitch215.drift.scan.project

private val dartPackage = Regex("^ {2}([A-Za-z0-9_]+):\\s*$")
private val dartField = Regex("^ {4}(dependency|source|version):\\s*\"?([^\"]*?)\"?\\s*$")
private val dartLowerBound = Regex(">=\\s*(\\d[^\\s<,]*)")
private val dartSdk = Regex("^ {2}([A-Za-z0-9_]+):\\s*\"?([^\"]*?)\"?\\s*$")

internal fun pubspec(files: ProjectScanner.Files, out: ProjectScanner.Out) {
    val text = files.read("pubspec.lock") ?: return
    val direct = mutableListOf<String>()
    val resolved = linkedMapOf<String, String>()
    var section = ""
    var name = ""
    var dependency = ""
    var source = ""
    var version = ""
    fun close() {
        if (name.isNotEmpty() && version.isNotEmpty() && source != "sdk") {
            resolved.keep(name, version)
            if (dependency.startsWith("direct")) direct += name
        }
        name = ""
        dependency = ""
        source = ""
        version = ""
    }
    for (line in text.lines()) {
        when {
            line.isBlank() || line.startsWith("#") -> Unit

            !line.startsWith(" ") -> {
                close()
                section = line.trimEnd().removeSuffix(":")
            }

            section == "packages" -> {
                dartPackage.find(line)?.let {
                    close()
                    name = it.groupValues[1]
                }
                dartField.find(line)?.let {
                    when (it.groupValues[1]) {
                        "dependency" -> dependency = it.groupValues[2]
                        "source" -> source = it.groupValues[2]
                        else -> version = it.groupValues[2]
                    }
                }
            }

            section == "sdks" -> dartSdk.find(line)?.let {
                val (sdk, constraint) = it.destructured
                out.put("sdks.$sdk", constraint, "pubspec.lock")
                if (sdk == "dart") {
                    dartLowerBound.find(constraint)?.let { m ->
                        out.put("sdk-min", m.groupValues[1], "pubspec.lock")
                    }
                }
            }
        }
    }
    close()
    out.packages(direct, resolved, "pubspec.lock")
}
