package dev.gmitch215.drift.cli.install

/** Edits to the raw `Path` registry value that keep every other entry byte for byte. */
internal object WindowsPath {
	data class Added(val value: String, val separator: String)

	fun contains(raw: String, dir: String, env: Map<String, String>): Boolean {
		val want = key(dir, env)
		return raw.split(';').any { it.isNotEmpty() && key(it, env) == want }
	}

	fun add(raw: String, dir: String): Added {
		val separator = if (raw.isEmpty() || raw.endsWith(";")) "" else ";"
		return Added(raw + separator + dir, separator)
	}

	/** The value without the last entry equal to [dir]; null when no entry matches. */
	fun remove(raw: String, dir: String, separator: String, env: Map<String, String>): String? {
		val want = key(dir, env)
		val tokens = raw.split(';').toMutableList()
		val at = tokens.indexOfLast { it.isNotEmpty() && key(it, env) == want }
		if (at < 0) return null
		if (separator.isEmpty() && at == tokens.lastIndex && at > 0) {
			tokens[at] = ""
		} else {
			tokens.removeAt(at)
		}
		return tokens.joinToString(";")
	}

	fun expand(text: String, env: Map<String, String>): String {
		val out = StringBuilder()
		var i = 0
		while (i < text.length) {
			val close = if (text[i] == '%') text.indexOf('%', i + 1) else -1
			val name = if (close > i + 1) text.substring(i + 1, close) else null
			val value = name?.let { n -> env.entries.firstOrNull { it.key.equals(n, true) }?.value }
			if (value != null) {
				out.append(value)
				i = close + 1
			} else {
				out.append(text[i])
				i++
			}
		}
		return out.toString()
	}

	private fun key(entry: String, env: Map<String, String>): String =
		expand(entry.trim().trim('"'), env).replace('/', '\\').trimEnd('\\').lowercase()
}
