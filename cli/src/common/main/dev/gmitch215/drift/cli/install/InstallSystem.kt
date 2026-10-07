package dev.gmitch215.drift.cli.install

const val REG_SZ = 1
const val REG_EXPAND_SZ = 2

/** A registry string as stored: the raw text, with `%NAME%` references left unexpanded. */
data class RegistryValue(val type: Int, val data: String)

/** Everything `drift install` and `drift uninstall` need from the operating system. */
interface InstallSystem {
	/** The path of the running executable, or null when it cannot be installed. */
	fun selfPath(): String?

	fun isPrivileged(): Boolean

	fun exists(path: String): Boolean

	fun isDirectory(path: String): Boolean

	fun canWrite(directory: String): Boolean

	fun read(path: String): ByteArray?

	/** Creates or truncates [path] in place, so a symlinked profile keeps its target. */
	fun write(path: String, bytes: ByteArray, executable: Boolean = false): Boolean

	/** Moves [from] over [to] in one step. */
	fun rename(from: String, to: String): Boolean

	fun remove(path: String): Boolean

	fun makeDirectory(path: String): Boolean

	/** Removes a directory only when it is empty. */
	fun removeDirectory(path: String): Boolean

	fun readPath(machine: Boolean): RegistryValue?

	/** Writes the `Path` value; null deletes it. */
	fun writePath(machine: Boolean, value: RegistryValue?): Boolean

	fun broadcastEnvironment()

	/** Deletes [paths] and then any empty [directories] once this process has exited. */
	fun removeAfterExit(paths: List<String>, directories: List<String>): Boolean
}

/** The target has no way to install itself: the JVM and wasm builds. */
object UnsupportedInstall : InstallSystem {
	override fun selfPath(): String? = null

	override fun isPrivileged() = false

	override fun exists(path: String) = false

	override fun isDirectory(path: String) = false

	override fun canWrite(directory: String) = false

	override fun read(path: String): ByteArray? = null

	override fun write(path: String, bytes: ByteArray, executable: Boolean) = false

	override fun rename(from: String, to: String) = false

	override fun remove(path: String) = false

	override fun makeDirectory(path: String) = false

	override fun removeDirectory(path: String) = false

	override fun readPath(machine: Boolean): RegistryValue? = null

	override fun writePath(machine: Boolean, value: RegistryValue?) = false

	override fun broadcastEnvironment() = Unit

	override fun removeAfterExit(paths: List<String>, directories: List<String>) = false
}

expect fun systemInstall(): InstallSystem
