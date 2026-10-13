package dev.gmitch215.drift.scan.project

private val comments = Regex("<!--[\\s\\S]*?-->")
private val skipped = listOf("dependencyManagement", "build", "profiles", "reporting").map {
    Regex("<$it>[\\s\\S]*?</$it>")
}
private val exclusions = Regex("<exclusions>[\\s\\S]*?</exclusions>")
private val dependency = Regex("<dependency>([\\s\\S]*?)</dependency>")
private val parent = Regex("<parent>([\\s\\S]*?)</parent>")
private val properties = Regex("<properties>([\\s\\S]*?)</properties>")
private val property = Regex("<([\\w.\\-]+)>\\s*([^<]*?)\\s*</\\1>")
private val reference = Regex("\\$\\{([^}]+)}")
private val javaProperties = setOf(
    "java.version",
    "maven.compiler.source",
    "maven.compiler.target",
    "maven.compiler.release",
)

private fun tag(body: String, name: String) =
    Regex("<$name>\\s*([^<]*?)\\s*</$name>").find(body)?.groupValues?.get(1)

internal fun maven(files: ProjectScanner.Files, out: ProjectScanner.Out) {
    val pom = files.read("pom.xml")?.replace(comments, "") ?: return
    val props = properties.find(pom)?.groupValues?.get(1)?.let { block ->
        property.findAll(block).associate { it.groupValues[1] to it.groupValues[2] }
    }.orEmpty()
    fun resolve(value: String?) = value?.replace(reference) { props[it.groupValues[1]] ?: it.value }
        ?.takeIf { "\${" !in it }

    for (name in javaProperties) out.put("property.$name", resolve(props[name]), "pom.xml")
    val resolved = linkedMapOf<String, String>()
    parent.find(pom)?.groupValues?.get(1)?.let { body ->
        val id = "${tag(body, "groupId")}:${tag(body, "artifactId")}"
        resolve(tag(body, "version"))?.let { resolved[id] = it }
    }
    val direct = skipped.fold(pom.replace(parent, "")) { text, re -> text.replace(re, "") }
        .replace(exclusions, "")
    for (m in dependency.findAll(direct)) {
        val body = m.groupValues[1]
        val group = tag(body, "groupId") ?: continue
        val artifact = tag(body, "artifactId") ?: continue
        resolve(tag(body, "version"))?.let { resolved.keep("$group:$artifact", it) }
    }
    out.packages(resolved.keys, resolved, "pom.xml")
}
