package dev.gmitch215.drift.scan.project

import dev.gmitch215.drift.ci.LooseJson
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.know.Rules
import dev.gmitch215.drift.know.paths
import dev.gmitch215.drift.model.Attribute

/**
 * Reads dependency and platform facts from the manifests and lockfiles of one project directory,
 * and the imports and call-site signals of its source files (listed with `find`). Nothing
 * touches the network; a file that is absent or does not parse adds no attributes. Keys are
 * `project.<ecosystem>.<name>`.
 */
object ProjectScanner {
    /** Most package keys one ecosystem adds; rule-named and direct packages come first. */
    const val MAX_PACKAGES = 200

    /** Most imported names one language adds; rule-named names come first, then by name. */
    const val MAX_IMPORTS = 500

    private const val MAX_CHARS = 32_000_000

    /** Every `project.` path an embedded rule reads; the package cap keeps these first. */
    private val ruleKeys: Set<String> by lazy {
        Rules.all.flatMap { it.detect.paths() }.filter { it.startsWith("project.") }.toSet()
    }

    /** [dir] is where the project files are; empty means the working directory. */
    fun scan(host: Host, dir: String = ""): List<Attribute> {
        val files = Files(host, dir)
        val source = SourceFiles.read(host, files, dir)
        return listOf(
            Out("composer").also { composer(files, it) },
            Out("npm").also { npm(files, it) },
            Out("gradle").also { gradle(files, it) },
            Out("maven").also { maven(files, it) },
            Out("python").also { python(files, it) },
            Out("go").also { go(files, it) },
        ).plus(
            sourceLanguages.map { language ->
                Out(language.ecosystem).also { sourceFacts(language, source, it) }
            },
        ).flatMap { it.attributes }
    }

    internal class Files(private val host: Host, dir: String) {
        private val prefix = if (dir.isEmpty()) "" else dir.trimEnd('/') + "/"

        fun read(path: String): String? = host.readText(prefix + path)
            ?.takeIf { it.length <= MAX_CHARS }
            ?.replace("\r\n", "\n")

        fun json(path: String): JsonObject? {
            val text = read(path) ?: return null
            return try {
                LooseJson.parse(text) as? JsonObject
            } catch (_: JsonException) {
                null
            }
        }
    }

    internal class Out(private val ecosystem: String) {
        val attributes = mutableListOf<Attribute>()

        fun put(key: String, value: String?, file: String) {
            val v = value?.trim()
            if (v.isNullOrEmpty() || key.any { it.isWhitespace() }) return
            attributes += Attribute("project.$ecosystem.$key", v, "project:$file")
        }

        /** Keys `imports.<name>`, [MAX_IMPORTS] in all; `imports-omitted` counts the rest. */
        fun imports(names: Set<String>, file: String) {
            val prefix = "project.$ecosystem.imports."
            val usable = names.filter { it.length <= 200 && it.all(::nameChar) }
            val named = ruleKeys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
            val priority = named.sorted().filter { it in usable }
            val rest = (usable - priority.toSet()).sorted()
            val kept = (priority + rest).take(MAX_IMPORTS)
            for (name in kept) put("imports.$name", "true", file)
            if (kept.size < usable.size) put("imports-omitted", "${usable.size - kept.size}", file)
        }

        /** Rule-named, then direct, then the rest by name; [MAX_PACKAGES] keys in all. */
        fun packages(direct: Collection<String>, resolved: Map<String, String>, file: String) {
            val prefix = "project.$ecosystem."
            val named = ruleKeys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
            val priority = (named.sorted() + direct.sorted()).filter { it in resolved }.distinct()
            val seen = priority.toSet()
            val rest = resolved.keys.filter { it !in seen }.sorted()
            val kept = (priority + rest).take(MAX_PACKAGES)
            for (name in kept) put(name, resolved.getValue(name), file)
            if (kept.size < resolved.size) put("omitted", "${resolved.size - kept.size}", file)
        }
    }
}

private fun nameChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "_.:-/@"

internal fun JsonObject.strings(key: String): Map<String, String> =
    (this[key] as? JsonObject)?.fields.orEmpty().mapNotNull { (k, v) ->
        (v as? JsonString)?.value?.let { k to it }
    }.toMap()

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonValue?.text(): String? = (this as? JsonString)?.value

internal fun MutableMap<String, String>.keep(key: String, value: String) {
    if (key !in this) this[key] = value
}
