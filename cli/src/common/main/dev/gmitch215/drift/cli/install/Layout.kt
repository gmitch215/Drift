package dev.gmitch215.drift.cli.install

enum class Scope { USER, GLOBAL }

/** Where things live on one operating system, derived from the environment alone. */
internal class Layout(private val vars: Map<String, String>, val windows: Boolean) {
	val sep = if (windows) '\\' else '/'
	val binaryName = if (windows) "drift.exe" else "drift"

	fun env(name: String): String? {
		val exact = vars[name]
		val value = if (exact == null && windows) {
			vars.entries.firstOrNull { it.key.equals(name, true) }?.value
		} else {
			exact
		}
		return value?.takeIf { it.isNotEmpty() }
	}

	fun home(): String? = env("HOME")

	private fun localAppData(): String? = env("LOCALAPPDATA")
		?: env("USERPROFILE")?.let { join(it, "AppData\\Local") }

	private fun xdg(name: String): String? = env(name)?.takeIf { isAbsolute(it) }

	fun userDir(): String? = if (windows) {
		localAppData()?.let { join(join(it, "Programs"), "Drift") }
	} else {
		xdg("XDG_BIN_HOME") ?: home()?.let { join(join(it, ".local"), "bin") }
	}

	fun globalDir(): String = if (windows) {
		join(env("ProgramFiles") ?: "C:\\Program Files", "Drift")
	} else {
		"/usr/local/bin"
	}

	fun configDir(): String? = if (windows) {
		localAppData()?.let { join(it, "Drift") }
	} else {
		(xdg("XDG_CONFIG_HOME") ?: home()?.let { join(it, ".config") })?.let { join(it, "drift") }
	}

	fun isAbsolute(path: String): Boolean = if (windows) {
		path.startsWith("\\\\") ||
			(path.length >= 3 && path[1] == ':' && (path[2] == '\\' || path[2] == '/'))
	} else {
		path.startsWith("/")
	}

	fun clean(path: String): String {
		val slashed = if (windows) path.replace('/', '\\') else path
		val drive = slashed.length == 3 && slashed[1] == ':'
		return if (drive || slashed.trimEnd(sep).isEmpty()) slashed else slashed.trimEnd(sep)
	}

	fun join(dir: String, name: String): String = dir.trimEnd(sep) + sep + name

	fun parent(path: String): String? {
		val trimmed = path.trimEnd(sep)
		val at = trimmed.lastIndexOf(sep)
		return when {
			at < 0 -> null
			at == 0 -> sep.toString()
			windows && at == 2 && trimmed[1] == ':' -> trimmed.substring(0, 3)
			else -> trimmed.substring(0, at)
		}
	}

	fun sameDirectory(a: String, b: String): Boolean = key(a) == key(b)

	private fun key(path: String): String {
		val clean = clean(path).trim('"').trimEnd(sep)
		return if (windows) clean.lowercase() else clean
	}

	/** True when [dir] is one of the entries of a PATH-style [list]. */
	fun onPath(list: String, dir: String): Boolean {
		val want = key(dir)
		return list.split(if (windows) ';' else ':').any { it.isNotEmpty() && key(it) == want }
	}

	fun oldName(binary: String): String = join(parent(binary) ?: "", "drift.old")
}
