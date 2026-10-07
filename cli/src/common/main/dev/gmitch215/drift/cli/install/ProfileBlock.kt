package dev.gmitch215.drift.cli.install

/** The marker-delimited PATH block appended to a shell startup file, and its exact removal. */
internal object ProfileBlock {
	const val START = "# >>> drift >>>"
	const val END = "# <<< drift <<<"

	sealed interface Removal {
		data class Removed(val content: String) : Removal

		data object Absent : Removal

		data object Changed : Removal
	}

	fun sh(dir: String, home: String?): String {
		val path = expr(dir, home, backtick = true)
		return "$START\ncase \":\${PATH}:\" in\n\t*\":$path:\"*) ;;\n" +
			"\t*) export PATH=\"$path:\$PATH\" ;;\nesac\n$END\n"
	}

	fun fish(dir: String, home: String?): String {
		val path = expr(dir, home, backtick = false)
		return "$START\nif not contains -- \"$path\" \$PATH\n" +
			"\tset -gx PATH \"$path\" \$PATH\nend\n$END\n"
	}

	fun has(content: String): Boolean = startAt(content) >= 0

	/** The new content, and whether a newline had to be added before the block. */
	fun append(content: String, block: String): Pair<String, Boolean> {
		val lead = content.isNotEmpty() && !content.endsWith("\n")
		return (content + (if (lead) "\n" else "") + block) to lead
	}

	fun remove(content: String, block: String, leadingNewline: Boolean): Removal {
		val at = startAt(content)
		if (at < 0) return Removal.Absent
		if (!content.startsWith(block, at)) return Removal.Changed
		val after = content.substring(at + block.length)
		var before = content.substring(0, at)
		if (leadingNewline && after.isEmpty() && before.endsWith("\n")) before = before.dropLast(1)
		return Removal.Removed(before + after)
	}

	private fun startAt(content: String): Int {
		if (content.startsWith("$START\n")) return 0
		val at = content.indexOf("\n$START\n")
		return if (at < 0) -1 else at + 1
	}

	private fun expr(dir: String, home: String?, backtick: Boolean): String {
		val under = home != null && home != "/" && (dir == home || dir.startsWith("$home/"))
		val rest = if (under) dir.removePrefix(home) else dir
		val escaped = buildString {
			for (c in rest) {
				if (c == '\\' || c == '"' || c == '$' || (backtick && c == '`')) append('\\')
				append(c)
			}
		}
		return if (under) "\$HOME$escaped" else escaped
	}
}
