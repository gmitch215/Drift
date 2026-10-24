package dev.gmitch215.drift.scan.handsoff

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue

/** Reads the manifests, lockfiles, pins and named commands of one project directory. */
internal class Ecosystems(private val checkout: Checkout, root: String) {
    private val prefix = root.trim('/').let { if (it.isEmpty()) "" else "$it/" }
    private val seen = mutableSetOf<String>()
    private val tools: List<Triple<String, String, Int>> by lazy { toolVersions() }

    val readFiles: List<String> get() = seen.sorted()

    private fun doc(path: String): Doc? =
        checkout.doc(prefix + path)?.also { seen += prefix + path }

    private fun json(path: String): JsonObject? =
        checkout.json(prefix + path)?.also { seen += prefix + path }

    private fun exists(path: String): Boolean =
        checkout.read(prefix + path)?.also { seen += prefix + path } != null

    private fun src(path: String, line: Int? = null) = Source(prefix + path, line)

    fun all(): List<EcosystemFacts> =
        listOfNotNull(node(), python(), jdk(), php(), go(), make(), just())

    /** Files at the root that mark an ecosystem this does not read. */
    fun notRead(): List<String> = NOT_READ.filter { checkout.read(prefix + it.first) != null }
        .map { "${prefix + it.first} (${it.second})" }

    // #region version files

    private fun toolVersions(): List<Triple<String, String, Int>> {
        val doc = doc(".tool-versions") ?: return emptyList()
        return doc.lines.mapIndexedNotNull { i, raw ->
            val words = raw.substringBefore('#').trim().split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
            if (words.size < 2) null else Triple(words[0], words[1], i + 1)
        }
    }

    private fun toolPin(tool: String, vararg names: String): List<Pin> =
        tools.filter { it.first in names }.map {
            Pin(tool, it.second, ".tool-versions ${it.first}", src(".tool-versions", it.third))
        }

    private fun firstLinePin(tool: String, file: String): List<Pin> {
        val doc = doc(file) ?: return emptyList()
        val at = doc.lines.indexOfFirst { it.isNotBlank() && !it.trim().startsWith("#") }
        if (at < 0) return emptyList()
        return listOf(Pin(tool, doc.lines[at].trim(), file, doc.source(at)))
    }

    // #endregion

    // #region node

    private fun node(): EcosystemFacts? {
        val doc = doc("package.json")
        val pkg = json("package.json")
        val locks = NODE_LOCKS.filter { exists(it.first) }
        if (pkg == null && locks.isEmpty()) return null
        val pins = mutableListOf<Pin>()
        pins += firstLinePin("node", ".nvmrc")
        pins += firstLinePin("node", ".node-version")
        pins += toolPin("node", "nodejs", "node")
        var manager: String? = null
        var managerSource: Source? = null
        val named = mutableListOf<Named>()
        var needsLock = false
        val workspaces = mutableListOf<Workspace>()
        if (pkg != null && doc != null) {
            fun pin(label: String, value: String?, vararg path: String) {
                if (value != null) {
                    pins += Pin("node", value, label, Source(doc.file, doc.jsonLine(*path)))
                }
            }
            val engines = (pkg["engines"] as? JsonObject)?.get("node").text()
            pin("engines.node", engines, "engines", "node")
            pin("devEngines.runtime", devRuntime(pkg), "devEngines", "runtime")
            pin("volta.node", (pkg["volta"] as? JsonObject)?.get("node").text(), "volta", "node")
            pkg["packageManager"].text()?.let {
                manager = it.substringBefore('@')
                managerSource = Source(doc.file, doc.jsonLine("packageManager"))
            }
            needsLock = listOf("dependencies", "devDependencies", "optionalDependencies")
                .any { (pkg[it] as? JsonObject)?.fields?.isNotEmpty() == true }
            val listed = when (val w = pkg["workspaces"]) {
                is JsonArray -> w.items
                is JsonObject -> (w["packages"] as? JsonArray)?.items.orEmpty()
                else -> emptyList()
            }
            for (item in listed) {
                item.text()?.let {
                    workspaces += Workspace(it, Source(doc.file, doc.jsonLine("workspaces")))
                }
            }
            (pkg["scripts"] as? JsonObject)?.fields?.forEach { (name, body) ->
                val at = Source(doc.file, doc.jsonLine("scripts", name))
                named += Named("script", name, body.text(), null, at, test = name == "test")
            }
        }
        doc("pnpm-workspace.yaml")?.let { ws ->
            val packages = YamlLite.parse(ws.text).root.map()?.get("packages")
            for (item in packages.seq()) {
                item.scalar()?.let {
                    workspaces += Workspace(it, src("pnpm-workspace.yaml", item.line))
                }
            }
        }
        return EcosystemFacts(
            "node",
            listOfNotNull(doc?.let { Source(it.file) }),
            manager,
            managerSource,
            locks.map { src(it.first) },
            needsLock,
            pins,
            null,
            named,
            workspaces,
            emptyList(),
        )
    }

    private fun devRuntime(pkg: JsonObject): String? {
        val dev = pkg["devEngines"] as? JsonObject
        val runtime = dev?.get("runtime") as? JsonObject ?: return null
        return if (runtime["name"].text() == "node") runtime["version"].text() else null
    }

    // #endregion

    // #region python

    private fun python(): EcosystemFacts? {
        val pyproject = doc("pyproject.toml")
        val requirements = doc("requirements.txt")
        val pipfile = doc("Pipfile")
        val others = listOf("setup.py", "setup.cfg").filter { exists(it) }
        if (pyproject == null && requirements == null && pipfile == null && others.isEmpty()) {
            return null
        }
        val locks = mutableListOf<Source>()
        for (name in listOf("poetry.lock", "uv.lock", "Pipfile.lock")) {
            if (exists(name)) locks += src(name)
        }
        val lines = requirements?.lines.orEmpty()
            .map { it.substringBefore(" #").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("-") }
        val pinned = lines.isNotEmpty() && lines.all { "==" in it || " @ " in it }
        if (requirements != null && pinned) locks += src("requirements.txt")
        val pins = mutableListOf<Pin>()
        pins += firstLinePin("python", ".python-version")
        pins += toolPin("python", "python")
        pyproject?.let { pins += pythonPins(it) }
        val deps = Regex("^\\s*(dependencies\\s*=|\\[tool\\.poetry\\.dependencies\\])", MULTILINE)
        val hasDeps = lines.isNotEmpty() || pipfile != null ||
            (pyproject != null && deps.containsMatchIn(pyproject.text))
        val names = locks.map { it.file.removePrefix(prefix) }
        val manager = when {
            "poetry.lock" in names -> "poetry"
            "uv.lock" in names -> "uv"
            "Pipfile.lock" in names -> "pipenv"
            requirements != null -> "pip"
            else -> null
        }
        val named = mutableListOf<Named>()
        doc("tox.ini")?.let { named += toxEnvs(it) }
        doc("noxfile.py")?.let { named += noxSessions(it) }
        val manifests = listOfNotNull(pyproject, requirements, pipfile).map { Source(it.file) } +
            others.map { src(it) }
        return EcosystemFacts(
            "python", manifests, manager, null, locks, hasDeps, pins, null, named,
            emptyList(), emptyList(),
        )
    }

    private fun default(command: String, file: String) =
        Install(command, "default for $file", src(file))

    /** The install step for the lockfile [lock] (a file name below the root), or null. */
    fun install(facts: EcosystemFacts, lock: String?): Install? = when (facts.id) {
        "node" -> nodeInstall(lock)
        "python" -> pythonInstall(lock)
        else -> facts.install
    }

    private fun nodeInstall(lock: String?): Install? = when (lock) {
        "package-lock.json", "npm-shrinkwrap.json" -> default("npm ci", lock)

        "pnpm-lock.yaml" -> default("pnpm install --frozen-lockfile", lock)

        "yarn.lock" -> {
            val text = checkout.read(prefix + lock).orEmpty()
            when {
                "__metadata:" in text -> default("yarn install --immutable", lock)
                "# yarn lockfile v1" in text -> default("yarn install --frozen-lockfile", lock)
                else -> null
            }
        }

        else -> null
    }

    private fun pythonInstall(lock: String?): Install? = when {
        lock == "poetry.lock" -> default("poetry install", lock)

        lock == "uv.lock" -> default("uv sync", lock)

        lock == null || lock == "requirements.txt" ->
            default("python -m pip install -r requirements.txt", "requirements.txt")
                .takeIf { exists("requirements.txt") }

        else -> null
    }

    private fun pythonPins(doc: Doc): List<Pin> {
        val pins = mutableListOf<Pin>()
        val tables = listOf(
            Triple("project", Regex("^\\s*requires-python\\s*=\\s*$QUOTED"), "requires-python"),
            Triple(
                "tool.poetry.dependencies",
                Regex("^\\s*python\\s*=\\s*[\"']([^\"']+)[\"']"),
                "tool.poetry.dependencies python",
            ),
        )
        for ((table, key, label) in tables) {
            val range = doc.section(table) ?: continue
            val hit = doc.find(key, range.first, range.last + 1) ?: continue
            pins += Pin("python", hit.match.groupValues[1], label, doc.source(hit.index))
        }
        return pins
    }

    private fun toxEnvs(doc: Doc): List<Named> {
        val named = mutableListOf<Named>()
        fun add(name: String, index: Int) {
            if (named.none { it.name == name }) {
                named += Named("tox environment", name, null, null, doc.source(index))
            }
        }
        val list = Regex("^\\s*(?:env_list|envlist)\\s*=\\s*(.*)$")
        doc.find(list)?.let { hit ->
            val items = mutableListOf(hit.match.groupValues[1])
            var i = hit.index + 1
            while (i < doc.lines.size && doc.lines[i].startsWith(" ")) {
                items += doc.lines[i].trim()
                i++
            }
            items.joinToString(",").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { add(it, hit.index) }
        }
        val section = Regex("^\\[testenv:([^\\]]+)\\]")
        doc.lines.forEachIndexed { i, line ->
            section.find(line.trim())?.let { add(it.groupValues[1], i) }
        }
        return named
    }

    private fun noxSessions(doc: Doc): List<Named> {
        val named = mutableListOf<Named>()
        val decorator = Regex("^@nox\\.session\\b(.*)$")
        val define = Regex("^def\\s+(\\w+)\\s*\\(")
        val rename = Regex("name\\s*=\\s*[\"']([^\"']+)[\"']")
        doc.lines.forEachIndexed { i, line ->
            val d = decorator.find(line.trim()) ?: return@forEachIndexed
            val def = (i + 1 until minOf(i + 6, doc.lines.size))
                .firstNotNullOfOrNull { define.find(doc.lines[it].trim()) }
                ?: return@forEachIndexed
            val name = rename.find(d.groupValues[1])?.groupValues?.get(1) ?: def.groupValues[1]
            val test = name == "test" || name == "tests"
            named += Named("nox session", name, null, "nox -s $name", doc.source(i), test)
        }
        return named
    }

    // #endregion

    // #region jdk

    private fun jdk(): EcosystemFacts? {
        val gradle = GRADLE_FILES.mapNotNull { doc(it) }
        val pom = doc("pom.xml")
        if (gradle.isEmpty() && pom == null) return null
        val pins = mutableListOf<Pin>()
        pins += firstLinePin("java", ".java-version")
        pins += toolPin("java", "java")
        pins += sdkman()
        val toolchain = Regex("(?:jvmToolchain\\(|JavaLanguageVersion\\.of\\()\\s*(\\d+)\\s*\\)")
        for (file in gradle) {
            val hit = file.find(toolchain) ?: continue
            pins += file.pin("java", hit.match.groupValues[1], "gradle toolchain", hit.index)
        }
        val wrapper = Regex("distributionUrl=.*gradle-([0-9][^-/]*)-(?:bin|all)\\.zip")
        doc("gradle/wrapper/gradle-wrapper.properties")?.let { file ->
            file.find(wrapper)?.let {
                pins += file.pin("gradle", it.match.groupValues[1], "gradle wrapper", it.index)
            }
        }
        if (pom != null) {
            val level = Regex(
                "<(?:maven\\.compiler\\.release|maven\\.compiler\\.source|java\\.version" +
                    "|release)>\\s*(\\d+)\\s*<",
            )
            pom.find(level)?.let {
                val value = ">=${it.match.groupValues[1]}"
                pins += pom.pin("java", value, "pom.xml java level", it.index)
            }
            val mvn = Regex("distributionUrl=.*apache-maven-([0-9][^-/]*)-bin")
            doc(".mvn/wrapper/maven-wrapper.properties")?.let { file ->
                file.find(mvn)?.let {
                    pins += file.pin("maven", it.match.groupValues[1], "maven wrapper", it.index)
                }
            }
        }
        val manager = when {
            gradle.isNotEmpty() && exists("gradlew") -> "gradle wrapper"
            gradle.isNotEmpty() -> "gradle"
            exists("mvnw") -> "maven wrapper"
            else -> "maven"
        }
        val locks = if (exists("gradle.lockfile")) listOf(src("gradle.lockfile")) else emptyList()
        return EcosystemFacts(
            "jdk",
            (gradle + listOfNotNull(pom)).map { Source(it.file) },
            manager, null, locks, false, pins, null, emptyList(), emptyList(),
            listOf("Gradle tasks and Maven goals are not read"),
        )
    }

    private fun sdkman(): List<Pin> {
        val doc = doc(".sdkmanrc") ?: return emptyList()
        val hit = doc.find(Regex("^\\s*java\\s*=\\s*(\\S+)")) ?: return emptyList()
        return listOf(doc.pin("java", hit.match.groupValues[1], ".sdkmanrc java", hit.index))
    }

    // #endregion

    // #region php

    private fun php(): EcosystemFacts? {
        val doc = doc("composer.json")
        val pkg = json("composer.json")
        val locked = exists("composer.lock")
        if (pkg == null && !locked) return null
        val pins = mutableListOf<Pin>()
        pins += toolPin("php", "php")
        val named = mutableListOf<Named>()
        var needsLock = false
        if (pkg != null && doc != null) {
            val require = pkg["require"] as? JsonObject
            require?.get("php").text()?.let {
                val at = Source(doc.file, doc.jsonLine("require", "php"))
                pins += Pin("php", it, "require.php", at)
            }
            needsLock = require?.fields?.keys
                ?.any { it != "php" && !it.startsWith("ext-") } == true
            val config = pkg["config"] as? JsonObject
            ((config?.get("platform") as? JsonObject)?.get("php")).text()?.let {
                val at = Source(doc.file, doc.jsonLine("config", "platform", "php"))
                pins += Pin("php", it, "config.platform.php", at)
            }
            (pkg["scripts"] as? JsonObject)?.fields?.keys?.forEach { name ->
                val at = Source(doc.file, doc.jsonLine("scripts", name))
                named += Named("script", name, null, null, at)
            }
        }
        return EcosystemFacts(
            "php",
            listOfNotNull(doc?.let { Source(it.file) }),
            "composer", null,
            if (locked) listOf(src("composer.lock")) else emptyList(),
            needsLock, pins,
            default("composer install", "composer.lock").takeIf { locked },
            named, emptyList(), emptyList(),
        )
    }

    // #endregion

    // #region go

    private fun go(): EcosystemFacts? {
        val mod = doc("go.mod")
        val work = doc("go.work")
        if (mod == null && work == null) return null
        val pins = mutableListOf<Pin>()
        pins += toolPin("go", "golang", "go")
        var needsLock = false
        if (mod != null) {
            mod.find(Regex("^go\\s+(\\S+)"))?.let {
                val value = ">=${it.match.groupValues[1]}"
                pins += mod.pin("go", value, "go directive", it.index)
            }
            mod.find(Regex("^toolchain\\s+(\\S+)"))?.let {
                val value = it.match.groupValues[1].removePrefix("go")
                pins += mod.pin("go", value, "toolchain directive", it.index)
            }
            needsLock = mod.find(Regex("^require\\b")) != null
        }
        val workspaces = mutableListOf<Workspace>()
        if (work != null) {
            var inBlock = false
            work.lines.forEachIndexed { i, raw ->
                val line = raw.substringBefore("//").trim()
                val at = work.source(i)
                when {
                    inBlock && line == ")" -> inBlock = false

                    inBlock && line.isNotEmpty() -> workspaces += Workspace(line, at)

                    line.startsWith("use (") -> inBlock = true

                    line.startsWith("use ") ->
                        workspaces += Workspace(line.removePrefix("use ").trim(), at)
                }
            }
        }
        val locked = exists("go.sum")
        return EcosystemFacts(
            "go",
            listOfNotNull(mod?.let { Source(it.file) }, work?.let { Source(it.file) }),
            "go", null,
            if (locked) listOf(src("go.sum")) else emptyList(),
            needsLock, pins,
            default("go mod download", "go.mod").takeIf { mod != null },
            emptyList(), workspaces, emptyList(),
        )
    }

    // #endregion

    // #region task runners

    private fun make(): EcosystemFacts? {
        val name = listOf("Makefile", "GNUmakefile", "makefile").firstOrNull { exists(it) }
            ?: return null
        val doc = doc(name) ?: return null
        val target = Regex("^([A-Za-z0-9_][A-Za-z0-9_./ -]*?)\\s*:(?![=:])")
        val named = mutableListOf<Named>()
        doc.lines.forEachIndexed { i, line ->
            if (line.startsWith("\t") || line.startsWith(" ") || line.startsWith("#")) {
                return@forEachIndexed
            }
            val hit = target.find(line) ?: return@forEachIndexed
            for (word in hit.groupValues[1].split(' ').filter { it.isNotEmpty() }) {
                if (named.none { it.name == word }) {
                    val make = "make $word"
                    named += Named("make target", word, null, make, doc.source(i), word == "test")
                }
            }
        }
        return taskRunner("make", doc, named)
    }

    private fun just(): EcosystemFacts? {
        val name = listOf("justfile", "Justfile", ".justfile").firstOrNull { exists(it) }
            ?: return null
        val doc = doc(name) ?: return null
        val recipe = Regex("^@?([A-Za-z_][A-Za-z0-9_-]*)(?:\\s[^:=]*)?:(?!=)")
        val named = mutableListOf<Named>()
        doc.lines.forEachIndexed { i, line ->
            if (line.startsWith(" ") || line.startsWith("\t") || line.startsWith("#")) {
                return@forEachIndexed
            }
            val word = recipe.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
            named += Named("just recipe", word, null, "just $word", doc.source(i), word == "test")
        }
        return taskRunner("just", doc, named)
    }

    private fun taskRunner(id: String, doc: Doc, named: List<Named>) = EcosystemFacts(
        id, listOf(Source(doc.file)), null, null, emptyList(), false, emptyList(), null, named,
        emptyList(), emptyList(),
    )

    // #endregion

    private companion object {
        val MULTILINE = RegexOption.MULTILINE
        const val QUOTED = "[\"']([^\"']+)[\"']"
        val GRADLE_FILES =
            listOf("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle")

        val NODE_LOCKS = listOf(
            "package-lock.json" to "npm",
            "npm-shrinkwrap.json" to "npm",
            "yarn.lock" to "yarn",
            "pnpm-lock.yaml" to "pnpm",
            "bun.lock" to "bun",
            "bun.lockb" to "bun",
        )

        val NOT_READ = listOf(
            "Cargo.toml" to "rust",
            "Gemfile" to "ruby",
            "global.json" to "dotnet",
            "mix.exs" to "elixir",
            "pubspec.yaml" to "dart",
            "deno.json" to "deno",
            "Package.swift" to "swift",
            "build.sbt" to "scala",
            "Project.toml" to "julia",
            "renv.lock" to "r",
        )
    }
}

private fun Doc.pin(tool: String, value: String, label: String, index: Int) =
    Pin(tool, value, label, source(index))

private fun JsonValue?.text(): String? = (this as? JsonString)?.value
