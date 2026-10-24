package dev.gmitch215.drift.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

enum class PrivacyCategory(val key: String) {
    HOME_PATH("home-path"),
    EMAIL("email"),
    TOKEN("token"),
    NETWORK("network"),
    CAPTURE("capture-attribute"),
}

data class PrivacyHit(
    val path: String,
    val line: Int,
    val category: PrivacyCategory,
    val value: String,
)

data class AllowEntry(
    val path: String?,
    val value: String?,
    val category: PrivacyCategory,
    val reason: String,
) {
    private val glob = path?.let { Privacy.glob(it) }

    fun matches(hit: PrivacyHit): Boolean = hit.category == category &&
        (glob == null || glob.matches(hit.path)) &&
        (value == null || value == hit.value)
}

data class PrivacyResult(val remaining: List<PrivacyHit>, val stale: List<AllowEntry>)

object Privacy {
    private val genericAccounts = setOf(
        "runner", "runneradmin", "user", "users", "root", "vsts", "admin", "administrator",
        "example", "default", "public", "shared", "guest", "ubuntu", "linuxbrew", "username",
        "name", "you", "nobody",
    )
    private val genericCaptureValues = genericAccounts + setOf(
        "ci", "localhost", "host", "hostname", "null", "true", "false", "none", "unknown",
    )
    private val genericLocalParts = setOf(
        "noreply", "no-reply", "git", "user", "you", "name", "someone", "email", "foo", "test",
    )
    private val fileTlds = setOf(
        "txt", "gz", "tgz", "xz", "zip", "json", "yml", "yaml", "md", "kt", "kts", "so", "jar",
        "kexe", "exe", "png", "html", "js", "mjs", "wasm", "sh", "dll", "a", "h", "c", "toml",
        "lock", "log", "cfg", "conf", "py", "rb", "pem", "crt",
    )
    private val exampleTlds = setOf("example", "test", "invalid", "localhost", "local")
    private val exampleDomains = setOf("example.com", "example.org", "example.net")

    private val homeUnix = Regex(
        "(?<![A-Za-z0-9_:])/(?:Users|home)/([A-Za-z0-9][^/\\s\"'\\\\:;,)\\]}<>*$%{`]*)",
    )
    private val homeWindows = Regex(
        "(?i)[A-Z]:[\\\\/]{1,2}Users[\\\\/]{1,2}([A-Za-z0-9][^\\\\/\\s\"'`]*)",
    )
    private val homeEncoded = Regex("-Users-([A-Za-z0-9]+)-")
    private val email = Regex(
        "([A-Za-z0-9._%+-]+)@([A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?" +
            "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*\\.([A-Za-z]{2,}))",
    )
    private val tokens = listOf(
        Regex("gh[pousr]_[A-Za-z0-9]{20,}"),
        Regex("github_pat_[A-Za-z0-9_]{20,}"),
        Regex("glpat-[A-Za-z0-9_-]{16,}"),
        Regex("AKIA[0-9A-Z]{16}"),
        Regex("xox[abp]-[A-Za-z0-9-]{10,}"),
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        Regex("Bearer\\s+[A-Za-z0-9._~+/=-]{20,}"),
    )
    private val assignment = Regex("(?i)\\b(?:password|token)=[\"']?([A-Za-z0-9+/_.~-]{12,})")
    private val ipv4 = Regex(
        "(?<![\\w.-])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?!\\d|\\.\\d)",
    )
    private val cidrSuffix = Regex("^/\\d")
    private val ipv6 = Regex(
        "(?<![0-9A-Fa-f:])(?:fe80|f[cd][0-9a-f]{2})(?::[0-9A-Fa-f]{0,4}){2,7}(?![0-9A-Fa-f:])",
        RegexOption.IGNORE_CASE,
    )
    private val mac = Regex(
        "(?<![0-9A-Fa-f:])[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}(?![0-9A-Fa-f:])",
    )
    private const val ATTRIBUTES = "user\\.name|hostname|env\\.USER|env\\.LOGNAME|" +
        "env\\.GITHUB_ACTOR|env\\.GITHUB_TRIGGERING_ACTOR"
    private val captureJson = Regex(
        "\"path\"\\s*:\\s*\"($ATTRIBUTES)\"[^}]*?\"value\"\\s*:\\s*\"([^\"]*)\"",
    )
    private val captureText = Regex(
        "(?<![\\w.])($ATTRIBUTES)\\s*[:=]\\s*\"?([A-Za-z0-9][A-Za-z0-9._-]*)",
    )

    fun glob(pattern: String): Regex {
        val out = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '*' && pattern.startsWith("**", i) -> {
                    out.append(".*")
                    i++
                }

                c == '*' -> out.append("[^/]*")

                c == '?' -> out.append("[^/]")

                else -> out.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(out.toString())
    }

    /** First and last character and the length; never the whole value. */
    fun mask(value: String): String = when {
        value.length <= 2 -> "*** (length ${value.length})"
        else -> "${value.first()}...${value.last()} (length ${value.length})"
    }

    fun scan(path: String, text: String): List<PrivacyHit> {
        val hits = linkedSetOf<PrivacyHit>()
        text.lines().forEachIndexed { index, line ->
            val n = index + 1
            fun add(category: PrivacyCategory, value: String) {
                hits += PrivacyHit(path, n, category, value)
            }
            for (m in homeUnix.findAll(line)) {
                if (m.groupValues[1].lowercase() !in genericAccounts) {
                    add(PrivacyCategory.HOME_PATH, m.value.trimEnd('.'))
                }
            }
            for (m in homeWindows.findAll(line)) {
                if (m.groupValues[1].lowercase() !in genericAccounts) {
                    add(PrivacyCategory.HOME_PATH, m.value)
                }
            }
            for (m in homeEncoded.findAll(line)) {
                if (m.groupValues[1].lowercase() !in genericAccounts) {
                    add(PrivacyCategory.HOME_PATH, m.value)
                }
            }
            for (m in email.findAll(line)) {
                if (!genericEmail(m.groupValues[1], m.groupValues[2], m.groupValues[3])) {
                    add(PrivacyCategory.EMAIL, m.value)
                }
            }
            for (re in tokens) for (m in re.findAll(line)) add(PrivacyCategory.TOKEN, m.value)
            for (m in assignment.findAll(line)) {
                val v = m.groupValues[1]
                if (v.any { it.isDigit() } && v.any { it.isLetter() }) {
                    add(PrivacyCategory.TOKEN, m.value)
                }
            }
            for (m in ipv4.findAll(line)) {
                val octets = m.groupValues.drop(1).map { it.toInt() }
                val block = octets[3] == 0 && cidrSuffix.containsMatchIn(
                    line.substring(m.range.last + 1),
                )
                if (privateV4(octets) && !block) add(PrivacyCategory.NETWORK, m.value)
            }
            for (m in ipv6.findAll(line)) add(PrivacyCategory.NETWORK, m.value)
            for (m in mac.findAll(line)) {
                val v = m.value.lowercase()
                if (v != "00:00:00:00:00:00" && v != "ff:ff:ff:ff:ff:ff") {
                    add(PrivacyCategory.NETWORK, m.value)
                }
            }
            for (re in listOf(captureJson, captureText)) {
                for (m in re.findAll(line)) {
                    if (m.groupValues[2].lowercase() !in genericCaptureValues &&
                        m.groupValues[2].isNotEmpty()
                    ) {
                        add(PrivacyCategory.CAPTURE, m.groupValues[2])
                    }
                }
            }
        }
        return hits.toList()
    }

    private fun genericEmail(local: String, domain: String, tld: String): Boolean {
        val d = domain.lowercase()
        return tld.lowercase() in fileTlds || tld.lowercase() in exampleTlds ||
            exampleDomains.any { d == it || d.endsWith(".$it") } ||
            local.lowercase() in genericLocalParts
    }

    private fun privateV4(o: List<Int>): Boolean {
        if (o.any { it > 255 }) return false
        val (a, b) = o
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
            (a == 100 && b in 64..127) || (a == 169 && b == 254)
    }

    fun parseAllow(yaml: String, name: String): List<AllowEntry> {
        if (yaml.isBlank()) return emptyList()
        val root = RuleYaml.load(yaml, name) ?: return emptyList()
        if (root !is List<*>) throw RuleYamlException("$name: top level must be a list")
        return root.mapIndexed { i, item ->
            val where = "$name: entry ${i + 1}"
            if (item !is Map<*, *>) throw RuleYamlException("$where must be a mapping")
            val extra = item.keys - setOf("path", "value", "category", "reason")
            if (extra.isNotEmpty()) throw RuleYamlException("$where has unknown keys $extra")
            val category = PrivacyCategory.entries.firstOrNull { it.key == item["category"] }
                ?: throw RuleYamlException("$where needs a category")
            val reason = item["reason"] as? String
            if (reason.isNullOrBlank()) throw RuleYamlException("$where needs a reason")
            val path = item["path"] as? String
            val value = item["value"] as? String
            if (path == null && value == null) {
                throw RuleYamlException("$where needs a path or a value")
            }
            AllowEntry(path, value, category, reason)
        }
    }

    fun apply(hits: List<PrivacyHit>, allow: List<AllowEntry>): PrivacyResult {
        val used = mutableSetOf<AllowEntry>()
        val remaining = hits.filter { hit ->
            val entry = allow.firstOrNull { it.matches(hit) }
            entry?.let { used += it }
            entry == null
        }
        return PrivacyResult(sorted(remaining), allow.filter { it !in used })
    }

    fun sorted(hits: List<PrivacyHit>): List<PrivacyHit> = hits.sortedWith(
        compareBy({ it.path }, { it.line }, { it.category.key }, { it.value }),
    )

    fun render(hits: List<PrivacyHit>): String {
        val all = sorted(hits)
        val out = StringBuilder()
        out.append("hits: ${all.size}\n")
        for (c in PrivacyCategory.entries) {
            out.append("  ${c.key}: ${all.count { it.category == c }}\n")
        }
        out.append("\nby directory\n")
        all.groupBy { it.path.substringBeforeLast('/', ".") }.toSortedMap().forEach { (dir, hs) ->
            val files = hs.map { it.path }.toSet().size
            val kinds = hs.map { it.category.key }.toSortedSet().joinToString(",")
            out.append("  $dir: ${hs.size} hits in $files files ($kinds)\n")
        }
        out.append("\nby file\n")
        for (h in all) out.append("  ${h.path}:${h.line} ${h.category.key} ${mask(h.value)}\n")
        return out.toString()
    }

    private val skipDirs = setOf(".gradle", ".kotlin", ".git", "node_modules")

    /** A `build` directory is output only beside a Gradle build file; packages share the name. */
    fun outputDir(dir: File): Boolean = dir.name in skipDirs || (
        dir.name == "build" && listOf("build.gradle.kts", "settings.gradle.kts")
            .any { File(dir.parentFile, it).exists() }
        )

    fun isBinary(bytes: ByteArray): Boolean = bytes.take(8192).any { it == 0.toByte() }

    fun files(root: File): List<String> {
        if (File(root, ".git").exists()) {
            val p = ProcessBuilder("git", "ls-files", "-z").directory(root)
                .redirectErrorStream(false).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            if (p.waitFor() == 0) return out.split('\u0000').filter { it.isNotEmpty() }.sorted()
        }
        return root.walkTopDown().onEnter { !outputDir(it) }.filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
    }
}

abstract class PrivacyTask : DefaultTask() {
    @get:Internal
    abstract val root: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val allowFile: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val base = root.get().asFile
        val allowPath = allowFile.get().asFile.relativeTo(base).invariantSeparatorsPath
        val allow = Privacy.parseAllow(allowFile.get().asFile.readText(), allowPath)
        val hits = Privacy.files(base)
            .filter { it != allowPath }
            .flatMap { path ->
                val file = File(base, path)
                if (!file.isFile) return@flatMap emptyList()
                val bytes = file.readBytes()
                if (Privacy.isBinary(bytes)) {
                    emptyList()
                } else {
                    Privacy.scan(path, bytes.toString(Charsets.UTF_8))
                }
            }
        val result = Privacy.apply(hits, allow)
        val text = Privacy.render(result.remaining)
        report.get().asFile.also { it.parentFile.mkdirs() }.writeText(text)
        val problems = mutableListOf<String>()
        if (result.remaining.isNotEmpty()) {
            problems += "${result.remaining.size} privacy hits, report: ${report.get().asFile}"
        }
        result.stale.forEach {
            problems += "$allowPath: allowlist entry matches nothing: ${it.reason}"
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n"))
    }
}
