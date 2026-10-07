@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.cli.install

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import platform.posix.F_OK
import platform.posix.W_OK
import platform.posix.X_OK
import platform.posix.access
import platform.posix.chmod
import platform.posix.closedir
import platform.posix.fopen
import platform.posix.geteuid
import platform.posix.mkdir
import platform.posix.opendir
import platform.posix.readlink
import platform.posix.rmdir

private object LinuxInstall : NativeInstall({ path, mode -> fopen(path, mode) }) {
	override fun selfPath(): String? = memScoped {
		val buffer = allocArray<ByteVar>(BUFFER)
		val n = readlink("/proc/self/exe", buffer, BUFFER.convert()).toInt()
		if (n <= 0) null else buffer.readBytes(n).decodeToString()
	}

	override fun isPrivileged() = geteuid() == 0u

	override fun exists(path: String) = access(path, F_OK) == 0

	override fun isDirectory(path: String): Boolean {
		val dir = opendir(path) ?: return false
		closedir(dir)
		return true
	}

	override fun canWrite(directory: String) = access(directory, W_OK or X_OK) == 0

	override fun markExecutable(path: String) = chmod(path, MODE.convert()) == 0

	override fun rename(from: String, to: String) = platform.posix.rename(from, to) == 0

	override fun makeDirectory(path: String) = mkdir(path, MODE.convert()) == 0

	override fun removeDirectory(path: String) = rmdir(path) == 0

	override fun readPath(machine: Boolean): RegistryValue? = null

	override fun writePath(machine: Boolean, value: RegistryValue?) = false

	override fun broadcastEnvironment() = Unit

	override fun removeAfterExit(paths: List<String>, directories: List<String>) = false

	private const val BUFFER = 4096
	private const val MODE = 493
}

actual fun systemInstall(): InstallSystem = LinuxInstall
