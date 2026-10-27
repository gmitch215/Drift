package dev.gmitch215.drift.triage

import dev.gmitch215.drift.diff.VersionScheme

/**
 * Tool names the text itself shows: the command that opens a prompt line, a package named in a
 * system install command and a bare `name --version` line. Each becomes a generic
 * `tool.<name>.version` field, so a mention of its version reads without the key table listing
 * every tool.
 */
internal object Evidence {
    private val prompts = listOf("$ ", "% ", "> ")
    private val wrappers = setOf("sudo", "env", "time", "nohup", "exec")
    private const val MAX_NAME = 30

    fun fields(doc: Doc): List<VersionField> {
        val known = Names.versions.flatMap { it.names }.toSet()
        val found = linkedSetOf<String>()
        for (i in doc.lines.indices) {
            if (doc.fence[i] || doc.heading[i] != null) continue
            command(doc.lines[i].trim(), doc.inCode[i])?.let { found += it }
            found += installed(doc.lower[i])
        }
        return found.filter { it !in known && valid(it) }.map {
            val key = "tool.$it.version"
            VersionField(key, listOf(it), VersionScheme.of(key), generic = true)
        }
    }

    private fun command(t: String, inCode: Boolean): String? {
        val prompt = prompts.firstOrNull { t.startsWith(it) && (it != "> " || inCode) }
        val words = (if (prompt == null) t else t.substring(prompt.length))
            .split(' ').filter { it.isNotEmpty() }
        if (prompt == null && !(words.size == 2 && words[1] in Extractor.VERSION_FLAGS)) {
            return null
        }
        var k = 0
        while (k < words.size && (words[k] in wrappers || words[k].contains('='))) k++
        val first = words.getOrNull(k) ?: return null
        val base = first.substringAfterLast('/').removeSuffix(".exe")
        return base.takeIf { it == Tokens.lower(it) }
    }

    private fun installed(low: String): List<String> {
        val out = mutableListOf<String>()
        for (phrase in Names.installers) {
            val at = low.indexOf(phrase)
            if (at < 0 || !Tokens.wordAt(low, at, phrase)) continue
            for (word in low.substring(at + phrase.length).split(' ')) {
                if (word in stops || word.endsWith(";")) break
                if (word.isEmpty() || word.startsWith("-")) continue
                out += word.trim('`', '"', '\'', ',', ')', '(').substringBefore('=')
                    .substringBefore('@').substringBefore(':')
            }
        }
        return out
    }

    private fun valid(name: String) = name.length in 2..MAX_NAME && name[0] in 'a'..'z' &&
        name.all { it in 'a'..'z' || it in '0'..'9' || it in "_-" } &&
        name !in Names.shellWords

    private val stops = setOf("&&", "||", "|", ";", "&")
}
