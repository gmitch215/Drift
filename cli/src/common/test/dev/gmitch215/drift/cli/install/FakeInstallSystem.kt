package dev.gmitch215.drift.cli.install

/** An in-memory machine: files, directories, a registry Path value and failure switches. */
internal class FakeInstallSystem(
	private val self: String? = "/src/drift",
	selfBytes: ByteArray = "DRIFT-BINARY".encodeToByteArray(),
	var privileged: Boolean = false,
	private val windows: Boolean = false,
	roots: List<String> =
		listOf("/", "/src", "/home", "/home/u", "/usr", "/usr/local", "/usr/local/bin"),
) : InstallSystem {
	val files = linkedMapOf<String, ByteArray>()
	val directories = roots.toMutableSet()
	val executable = mutableSetOf<String>()
	val readOnly = mutableSetOf<String>()
	val locked = mutableSetOf<String>()
	val registry = mutableMapOf<Boolean, RegistryValue>()
	val mutations = mutableListOf<String>()
	var broadcasts = 0
	private val sep = if (windows) '\\' else '/'

	init {
		if (self != null) files[self] = selfBytes
	}

	fun put(path: String, text: String) {
		files[path] = text.encodeToByteArray()
	}

	fun text(path: String): String? = files[path]?.decodeToString()

	fun snapshot(): Map<String, String> =
		files.mapValues { it.value.decodeToString() } + directories.associateWith { "<dir>" }

	private fun parent(path: String): String? {
		val at = path.trimEnd(sep).lastIndexOf(sep)
		return when {
			at < 0 -> null
			at == 0 -> sep.toString()
			windows && at == 2 && path[1] == ':' -> path.substring(0, 3)
			else -> path.substring(0, at)
		}
	}

	override fun selfPath() = self

	override fun isPrivileged() = privileged

	override fun exists(path: String) = path in files || path in directories

	override fun isDirectory(path: String) = path in directories

	override fun canWrite(directory: String) = directory in directories && directory !in readOnly

	override fun read(path: String) = files[path]

	override fun write(path: String, bytes: ByteArray, executable: Boolean): Boolean {
		if (parent(path) !in directories || path in locked) return false
		mutations += "write $path"
		files[path] = bytes
		if (executable) this.executable += path
		return true
	}

	override fun rename(from: String, to: String): Boolean {
		val bytes = files[from] ?: return false
		if (to in locked || parent(to) !in directories) return false
		mutations += "rename $from $to"
		files.remove(from)
		files[to] = bytes
		if (from in locked) {
			locked -= from
			locked += to
		}
		if (from in executable) executable += to
		executable -= from
		return true
	}

	override fun remove(path: String): Boolean {
		if (path in locked) return false
		mutations += "remove $path"
		return files.remove(path) != null
	}

	override fun makeDirectory(path: String): Boolean {
		if (parent(path) !in directories || path in directories) return false
		mutations += "mkdir $path"
		directories += path
		return true
	}

	override fun removeDirectory(path: String): Boolean {
		val prefix = path.trimEnd(sep) + sep
		val busy = (files.keys + directories).any { it.startsWith(prefix) }
		if (path !in directories || busy) return false
		mutations += "rmdir $path"
		directories -= path
		return true
	}

	override fun readPath(machine: Boolean) = registry[machine]

	override fun writePath(machine: Boolean, value: RegistryValue?): Boolean {
		mutations += "registry ${if (machine) "machine" else "user"}"
		if (value == null) registry.remove(machine) else registry[machine] = value
		return true
	}

	override fun broadcastEnvironment() {
		broadcasts++
	}

	var scheduled: Pair<List<String>, List<String>>? = null
	var canSchedule = true

	override fun removeAfterExit(paths: List<String>, directories: List<String>): Boolean {
		if (canSchedule) scheduled = paths to directories
		return canSchedule
	}
}
