package dev.gmitch215.drift.redact

/** Replaces the account name, host name and home directory of the machine that made a value. */
object Anonymizer {
	const val HOME = "~"
	const val USER = "user"
	const val HOST = "host"

	private val homePath = Regex(
		"(/Users/|/home/|[A-Za-z]:[\\\\/]Users[\\\\/])[^/\\\\\\s:;,\"']+",
	)
	private val accountVars = listOf("USER", "LOGNAME", "USERNAME")
	private val hostVars = listOf("HOSTNAME", "COMPUTERNAME")
	private const val MIN_IDENTITY = 3

	/** Names to replace, from the `env.*` values in [attrs]; names under 3 characters stay. */
	fun owners(attrs: Map<String, String>): Map<String, String> {
		val found = mutableMapOf<String, String>()
		for (n in accountVars) attrs["env.$n"]?.let { found[it.trim()] = USER }
		attrs["env.HOME"]?.trim()?.trimEnd('/', '\\')?.let {
			found[it.substringAfterLast('/').substringAfterLast('\\')] = USER
		}
		for (n in hostVars) attrs["env.$n"]?.let { found[it.trim()] = HOST }
		return found.filterKeys { it.length >= MIN_IDENTITY }
	}

	/** The directory in `env.HOME`, or null when it is missing or the root. */
	fun home(attrs: Map<String, String>): String? =
		attrs["env.HOME"]?.trim()?.trimEnd('/', '\\')?.takeIf { it.length > 1 }

	/**
	 * Other users' home paths become `.../user`; names equal to an owner become its placeholder;
	 * with [home], that exact directory becomes `~` first.
	 */
	fun anonymize(value: String, owners: Map<String, String>, home: String? = null): String {
		var out = if (home == null) value else replaceHome(value, home)
		out = homePath.replace(out) { it.groupValues[1] + USER }
		for ((name, placeholder) in owners) out = replaceToken(out, name, placeholder)
		return out
	}

	private fun replaceHome(text: String, home: String): String {
		val sb = StringBuilder()
		var i = 0
		while (i < text.length) {
			val at = text.indexOf(home, i)
			if (at < 0) break
			val end = at + home.length
			val bounded = (at == 0 || !text[at - 1].isLetterOrDigit()) &&
				(end == text.length || !(text[end].isLetterOrDigit() || text[end] in "_-."))
			sb.append(text, i, at).append(if (bounded) HOME else home)
			i = end
		}
		return sb.append(text, i, text.length).toString()
	}

	private fun replaceToken(text: String, token: String, with: String): String {
		val sb = StringBuilder()
		var i = 0
		while (i < text.length) {
			val at = text.indexOf(token, i)
			if (at < 0) break
			val end = at + token.length
			val bounded = (at == 0 || !text[at - 1].isLetterOrDigit()) &&
				(end == text.length || !text[end].isLetterOrDigit())
			sb.append(text, i, at).append(if (bounded) with else token)
			i = end
		}
		return sb.append(text, i, text.length).toString()
	}
}
