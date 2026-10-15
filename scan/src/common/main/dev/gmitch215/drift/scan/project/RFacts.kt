package dev.gmitch215.drift.scan.project

import dev.gmitch215.drift.json.JsonObject

internal fun renv(files: ProjectScanner.Files, out: ProjectScanner.Out) {
    val lock = files.json("renv.lock") ?: return
    out.put("r-version", lock.obj("R")?.get("Version").text(), "renv.lock")
    val resolved = linkedMapOf<String, String>()
    for ((name, entry) in lock.obj("Packages")?.fields.orEmpty()) {
        (entry as? JsonObject)?.get("Version").text()?.let {
            resolved[name] = it
        }
    }
    out.packages(emptyList(), resolved, "renv.lock")
}
