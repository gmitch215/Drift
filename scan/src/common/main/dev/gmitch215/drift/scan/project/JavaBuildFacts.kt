package dev.gmitch215.drift.scan.project

private val buildFiles =
    listOf("build.gradle", "build.gradle.kts", "pom.xml", "Makefile", "build.xml")
private val javah = Regex("(?<![\\w.-])javah(?![\\w-])")
private val springModules = listOf(
    "org.springframework:spring-core",
    "org.springframework:spring-context",
    "org.springframework:spring-beans",
    "org.springframework:spring-web",
    "org.springframework:spring-webmvc",
)

internal fun javaBuild(files: ProjectScanner.Files, out: ProjectScanner.Out) {
    val hit = buildFiles.firstOrNull { name ->
        files.read(name)?.let(javah::containsMatchIn) == true
    }
    if (hit != null) out.put("javah-invocation", "true", hit)
}

internal fun springFramework(libraries: Map<String, String>, out: ProjectScanner.Out) {
    val module = springModules.firstOrNull { it in libraries } ?: return
    out.put("version", libraries[module], module)
}
