package dev.gmitch215.drift.scan.project

import dev.gmitch215.drift.host.Host

private const val MAX_MODULES = 50
private const val KOTLIN_PLUGIN = "org.jetbrains.kotlin."
private const val CATALOG = "gradle/libs.versions.toml"
private const val BUILD = "build.gradle(.kts)"

private val blockComment = Regex("/\\*[\\s\\S]*?\\*/")
private val lineComment = Regex("(^|\\s)//.*$", RegexOption.MULTILINE)
private val coordinate =
    Regex("[\"']([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([0-9][A-Za-z0-9_.+-]*)[\"']")
private val javaExec = Regex("\\bJavaExec\\b")
private val javaExecJvm = Regex("\\b(?:javaLauncher|executable)\\b")
private val wrapperVersion = Regex("gradle-(\\d[\\w.-]*?)-(?:bin|all)\\.zip")
private val pluginId = Regex(
    "\\bid\\s*\\(?\\s*[\"']([\\w.-]+)[\"']\\s*\\)?(?:\\s+version\\s+(?:[\"']([^\"']+)[\"']" +
        "|libs\\.versions\\.([\\w.]+)))?([^\\n;]*)",
)
private val kotlinPlugin = Regex(
    "\\bkotlin\\s*\\(\\s*[\"']([\\w.-]+)[\"']\\s*\\)" +
        "(?:\\s+version\\s+[\"']([^\"']+)[\"'])?([^\\n;]*)",
)
private val aliasPlugin = Regex("\\balias\\s*\\(\\s*libs\\.plugins\\.([\\w.]+)\\s*\\)([^\\n;]*)")
private val applyPlugin = Regex("\\bapply\\s*\\(?\\s*plugin\\s*[:=]\\s*[\"']([\\w.-]+)[\"']")
private val includeCall = Regex("\\binclude\\s*\\(([^)]*)\\)")
private val includeGroovy = Regex("\\binclude\\s+((?:[\"'][^\"']+[\"']\\s*,?\\s*)+)")
private val quoted = Regex("[\"']([^\"']+)[\"']")
private val legacyKotlin = Regex("kotlin_version\\s*=\\s*[\"']([^\"']+)[\"']")
private val targets = Regex(
    "^\\s*(jvm|js|wasmJs|wasmWasi|androidTarget|iosArm64|iosX64|iosSimulatorArm64|macosArm64|" +
        "macosX64|linuxX64|linuxArm64|mingwX64|tvosArm64|tvosX64|tvosSimulatorArm64|watchosArm64|" +
        "watchosX64|watchosSimulatorArm64|androidNativeArm64|androidNativeArm32|" +
        "androidNativeX64|androidNativeX86)\\s*[({]",
    RegexOption.MULTILINE,
)
private val jvmTarget =
    Regex("jvmTarget(?:\\s*=\\s*|\\.set\\(\\s*)(?:JvmTarget\\.JVM_([0-9_]+)|[\"']([0-9.]+)[\"'])")
private val toolchain =
    Regex("jvmToolchain\\s*\\(\\s*(\\d+)|JavaLanguageVersion\\.of\\(\\s*(\\d+)")
private val languageVersion = Regex(
    "languageVersion(?:\\s*=\\s*|\\.set\\(\\s*)KotlinVersion\\.KOTLIN_(\\d+)_(\\d+)" +
        "|-language-version[=\"',\\s]+([0-9.]+)",
)
private val annotationTarget = Regex("-Xannotation-default-target=([\\w-]+)")
private fun sdkLevel(name: String) = Regex(
    "\\b$name(?:Version)?\\s*(?:=\\s*|\\s+|\\()\\s*" +
        "(?:(\\d+(?:\\.\\d+)?)\\b|libs\\.versions\\.([\\w.]+)\\.get\\(\\))" +
        "|\\b$name\\s*\\{[^}]*?release\\(\\s*(\\d+)\\s*\\)" +
        "(?:\\s*\\{\\s*minorApiLevel\\s*=\\s*(\\d+))?",
)
private val sdkNumber = Regex("[0-9.]+")
private val sdkLevels = listOf(
    "compileSdk" to "compile-sdk",
    "minSdk" to "min-sdk",
    "targetSdk" to "target-sdk",
)
    .map { (name, key) -> key to sdkLevel(name) }
private const val CONFIGURATIONS = "compile|runtime|testCompile|testRuntime"
private val buildCalls = listOf(
    "compile-configuration" to Regex(
        "^[ \\t]*(?:\"(?:$CONFIGURATIONS)\"[ \\t]*\\(|(?:$CONFIGURATIONS)" +
            "(?:[ \\t]+[A-Za-z0-9_\"'(]|[ \\t]*\\())",
        RegexOption.MULTILINE,
    ),
    "convention-api" to
        Regex("(?:^|[^A-Za-z0-9_])convention[ \\t]*[.\\[]|\\bgetConvention[ \\t]*\\("),
    "project-exec" to
        Regex("(?:^|[^A-Za-z0-9_.])(?:project\\.)?(?:exec|javaexec)[ \\t]*[({]"),
)
private val archiveTasks =
    "(?:jar|war|ear|zip|tar|Jar|War|Ear|Zip|Tar|shadowJar|bootJar|bootWar)\\b[^{\\n]*"
private val baseNameSet = Regex("(?:^|[^A-Za-z0-9_.])baseName[ \\t]*(?:=|[\"'])")
private val archiveBaseNameDot = Regex("\\b(?:jar|war|ear|zip|tar)\\.baseName[ \\t]*=")
private val quotedTaskNotation =
    Regex("^[ \\t]*\"[A-Za-z0-9_:.-]+\"[ \\t]*(?:\\{|\\([ \\t]*\\))", RegexOption.MULTILINE)
private val androidPlugins = setOf("com.android.application", "com.android.library")
private val propertyPrefixes = listOf("android.", "kotlin.", "org.gradle.")

private class Catalog(
    val versions: Map<String, String>,
    val libraries: Map<String, String>,
    val plugins: Map<String, Pair<String, String?>>,
)

private fun accessor(alias: String) = alias.replace(Regex("[-_]"), ".")

private fun catalog(text: String?): Catalog {
    val tables = text?.let(Toml::parse).orEmpty().associateBy { it.name }
    val versions = tables["versions"]?.values.orEmpty().mapNotNull { (key, raw) ->
        val inline = Toml.inline(raw)
        val value = Toml.string(raw) ?: listOf("require", "strictly", "prefer")
            .firstNotNullOfOrNull { Toml.string(inline?.get(it)) }
        value?.let { accessor(key) to it }
    }.toMap()

    fun version(fields: Map<String, String>): String? {
        fields["version.ref"]?.let { return versions[accessor(Toml.string(it).orEmpty())] }
        val raw = fields["version"] ?: return null
        Toml.string(raw)?.let { return it }
        val inline = Toml.inline(raw) ?: return null
        inline["ref"]?.let { return versions[accessor(Toml.string(it).orEmpty())] }
        return listOf("require", "strictly", "prefer").firstNotNullOfOrNull {
            Toml.string(inline[it])
        }
    }

    val libraries = linkedMapOf<String, String>()
    for ((_, raw) in tables["libraries"]?.values.orEmpty()) {
        val short = Toml.string(raw)?.split(":")
        if (short != null) {
            if (short.size == 3) libraries["${short[0]}:${short[1]}"] = short[2]
            continue
        }
        val fields = Toml.inline(raw) ?: continue
        val module = Toml.string(fields["module"])
            ?: "${Toml.string(fields["group"])}:${Toml.string(fields["name"])}"
        version(fields)?.let { libraries[module] = it }
    }
    val plugins = linkedMapOf<String, Pair<String, String?>>()
    for ((alias, raw) in tables["plugins"]?.values.orEmpty()) {
        val short = Toml.string(raw)?.split(":")
        if (short != null) {
            plugins[accessor(alias)] = short[0] to short.getOrNull(1)
            continue
        }
        val fields = Toml.inline(raw) ?: continue
        val id = Toml.string(fields["id"]) ?: continue
        plugins[accessor(alias)] = id to version(fields)
    }
    return Catalog(versions, libraries, plugins)
}

private class Declared(val id: String, val version: String?, val applied: Boolean)

private fun strip(text: String) = text.replace(blockComment, "").replace(lineComment, "$1")

/** Contents of every `name { ... }` block, braces balanced. */
private fun blocks(text: String, name: String): List<String> {
    val found = mutableListOf<String>()
    for (m in Regex("\\b$name\\s*\\{").findAll(text)) {
        var depth = 1
        var i = m.range.last + 1
        while (i < text.length && depth > 0) {
            if (text[i] == '{') depth++
            if (text[i] == '}') depth--
            i++
        }
        found += text.substring(m.range.last + 1, i - 1)
    }
    return found
}

private fun legacyId(name: String) = when {
    name == "kotlin" -> "${KOTLIN_PLUGIN}jvm"
    name.startsWith("kotlin-") -> KOTLIN_PLUGIN + name.removePrefix("kotlin-")
    else -> name
}

private fun declared(text: String, catalog: Catalog): List<Declared> {
    val found = mutableListOf<Declared>()
    val scopes = blocks(text, "plugins")
    for (scope in scopes) {
        for (m in pluginId.findAll(scope)) {
            val (id, literal, ref, tail) = m.destructured
            val version = literal.ifEmpty { catalog.versions[ref.removeSuffix(".get")] ?: "" }
            found += Declared(legacyId(id), version.ifEmpty { null }, "apply false" !in tail)
        }
        for (m in kotlinPlugin.findAll(scope)) {
            val (name, version, tail) = m.destructured
            val applied = "apply false" !in tail
            found += Declared(KOTLIN_PLUGIN + name, version.ifEmpty { null }, applied)
        }
        for (m in aliasPlugin.findAll(scope)) {
            val (alias, tail) = m.destructured
            val entry = catalog.plugins[alias] ?: continue
            found += Declared(entry.first, entry.second, "apply false" !in tail)
        }
    }
    for (m in applyPlugin.findAll(text)) found += Declared(legacyId(m.groupValues[1]), null, true)
    return found
}

private fun modules(settings: String): List<String> {
    val paths = linkedSetOf<String>()
    for (m in includeCall.findAll(settings)) {
        quoted.findAll(m.groupValues[1]).forEach { paths += it.groupValues[1] }
    }
    for (m in includeGroovy.findAll(settings)) {
        quoted.findAll(m.groupValues[1]).forEach { paths += it.groupValues[1] }
    }
    return paths.map { it.trim(':') }.filter { it.isNotEmpty() }.take(MAX_MODULES)
}

private fun jvmName(raw: String) = raw.replace('_', '.')

private fun sdkValue(m: MatchResult, catalog: Catalog): String? {
    val (plain, ref, release, minor) = m.destructured
    return when {
        plain.isNotEmpty() -> plain
        ref.isNotEmpty() -> catalog.versions[ref]?.takeIf(sdkNumber::matches)
        release.isNotEmpty() -> if (minor.isEmpty()) release else "$release.$minor"
        else -> null
    }
}

/** Flutter keeps its Gradle project in `android/`; a root without Gradle files reads that. */
internal fun gradleFiles(
    host: Host,
    dir: String,
    files: ProjectScanner.Files,
): ProjectScanner.Files {
    val settings = listOf("settings.gradle.kts", "settings.gradle")
    if (settings.any { files.read(it) != null } || files.read("pubspec.yaml") == null) return files
    if (settings.none { files.read("android/$it") != null }) return files
    val prefix = if (dir.isEmpty()) "" else dir.trimEnd('/') + "/"
    return ProjectScanner.Files(host, prefix + "android")
}

internal fun gradle(files: ProjectScanner.Files, out: ProjectScanner.Out): Map<String, String> {
    val catalogText = files.read(CATALOG)
    val catalog = catalog(catalogText)
    val settings = listOf("settings.gradle.kts", "settings.gradle").firstNotNullOfOrNull {
        files.read(it)
    }?.let(::strip)
    val builds = mutableListOf<Triple<String, String, Boolean>>()
    for (module in listOf("") + modules(settings.orEmpty())) {
        val dir = if (module.isEmpty()) "" else module.replace(':', '/') + "/"
        for (name in listOf("build.gradle.kts", "build.gradle")) {
            files.read(dir + name)?.let {
                builds += Triple(
                    if (module.isEmpty()) ":" else ":$module",
                    strip(it),
                    name.endsWith(".kts"),
                )
                break
            }
        }
    }
    val sources = builds.map { it.second } + listOfNotNull(settings)

    files.read("gradle/wrapper/gradle-wrapper.properties")?.let { text ->
        wrapperVersion.find(text)?.let {
            out.put("wrapper", it.groupValues[1], "gradle/wrapper/gradle-wrapper.properties")
        }
    }
    files.read("gradle/gradle-daemon-jvm.properties")?.lines()
        ?.map { it.trim() }?.firstOrNull { it.startsWith("toolchainVersion") }
        ?.substringAfter('=')?.let {
            out.put("jdk.version", it, "gradle/gradle-daemon-jvm.properties")
        }
    files.read("gradle.properties")?.lines()?.forEach { raw ->
        val line = raw.trim()
        val at = line.indexOfFirst { it == '=' || it == ':' }
        if (line.startsWith("#") || at <= 0) return@forEach
        val key = line.substring(0, at).trim()
        if (propertyPrefixes.any { key.startsWith(it) }) {
            out.put("properties.$key", line.substring(at + 1), "gradle.properties")
        }
    }

    val plugins = linkedMapOf<String, String>()
    val androidKmp = mutableListOf<String>()
    for (text in sources) {
        for (plugin in declared(text, catalog)) {
            val old = plugins[plugin.id]
            if (old == null || (old == "true" && plugin.version != null)) {
                plugins[plugin.id] = plugin.version ?: "true"
            }
        }
    }
    for ((module, text, _) in builds) {
        val applied = declared(text, catalog).filter { it.applied }.map { it.id }.toSet()
        if (applied.any { it == "${KOTLIN_PLUGIN}multiplatform" } &&
            applied.any { it in androidPlugins }
        ) {
            androidKmp += module
        }
    }
    for ((id, version) in plugins) out.put("plugin.$id", version, BUILD)
    if (androidKmp.isNotEmpty()) {
        out.put("kmp-android-module", androidKmp.joinToString(","), BUILD)
    }

    val libraries = catalog.libraries.toMutableMap()
    for (text in sources) {
        for (m in coordinate.findAll(text)) {
            libraries.keep("${m.groupValues[1]}:${m.groupValues[2]}", m.groupValues[3])
        }
    }
    out.packages(libraries.keys, libraries, BUILD)

    val kotlin = plugins.entries.firstOrNull {
        it.key.startsWith(KOTLIN_PLUGIN) && it.value != "true"
    }?.value ?: libraries["org.jetbrains.kotlin:kotlin-gradle-plugin"]
        ?: sources.firstNotNullOfOrNull { legacyKotlin.find(it)?.groupValues?.get(1) }
    out.put("kotlin.version", kotlin, BUILD)
    val agp = plugins.entries.firstOrNull {
        it.key.startsWith("com.android.") && it.value != "true"
    }?.value ?: libraries["com.android.tools.build:gradle"]
    out.put("agp.version", agp, BUILD)

    val modulesText = builds.map { it.second }
    builds.firstOrNull()?.let { out.put("dsl", if (it.third) "kotlin" else "groovy", BUILD) }
    for ((name, regex) in buildCalls) {
        if (modulesText.any { regex.containsMatchIn(it) }) out.put("calls.$name", "true", BUILD)
    }
    if (modulesText.any { text ->
            archiveBaseNameDot.containsMatchIn(text) ||
                blocks(text, archiveTasks).any { baseNameSet.containsMatchIn(it) }
        }
    ) {
        out.put("calls.archive-basename", "true", BUILD)
    }
    if (builds.any { it.third && quotedTaskNotation.containsMatchIn(it.second) }) {
        out.put("calls.kotlin-dsl-quoted-notation", "true", BUILD)
    }
    for (text in modulesText) {
        for (m in targets.findAll(text)) {
            out.put("kotlin.target.${m.groupValues[1]}", "true", BUILD)
        }
    }
    modulesText.firstNotNullOfOrNull { jvmTarget.find(it) }?.let {
        val raw = it.groupValues[1].ifEmpty { it.groupValues[2] }
        out.put("kotlin.jvm-target", jvmName(raw), BUILD)
    }
    modulesText.firstNotNullOfOrNull { toolchain.find(it) }?.let {
        out.put("kotlin.toolchain", it.groupValues[1].ifEmpty { it.groupValues[2] }, BUILD)
    }
    modulesText.firstNotNullOfOrNull { languageVersion.find(it) }?.let {
        val (major, minor, flag) = it.destructured
        out.put("kotlin.language-version", flag.ifEmpty { "$major.$minor" }, BUILD)
    }
    modulesText.firstNotNullOfOrNull { annotationTarget.find(it) }?.let {
        out.put("kotlin.annotation-default-target", it.groupValues[1], BUILD)
    }
    for ((key, regex) in sdkLevels) {
        val level = modulesText.firstNotNullOfOrNull { text ->
            regex.find(text)?.let { sdkValue(it, catalog) }
        }
        out.put("android.$key", level, BUILD)
    }
    if (modulesText.any { javaExec.containsMatchIn(it) && !javaExecJvm.containsMatchIn(it) }) {
        out.put("javaexec-without-jvm-set", "true", BUILD)
    }
    return libraries
}
