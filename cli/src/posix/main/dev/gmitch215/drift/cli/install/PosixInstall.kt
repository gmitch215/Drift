@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.cli.install

import dev.gmitch215.drift.cli.isPosixDirectory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.posix.F_OK
import platform.posix.W_OK
import platform.posix.X_OK
import platform.posix.access
import platform.posix.chmod
import platform.posix.fopen
import platform.posix.geteuid
import platform.posix.mkdir
import platform.posix.rmdir

internal abstract class PosixInstall : NativeInstall({ path, mode -> fopen(path, mode) }) {
    override fun isPrivileged() = geteuid() == 0u

    override fun exists(path: String) = access(path, F_OK) == 0

    override fun isDirectory(path: String) = isPosixDirectory(path)

    override fun canWrite(directory: String) = access(directory, W_OK or X_OK) == 0

    override fun markExecutable(path: String) = chmod(path, MODE.convert()) == 0

    override fun rename(from: String, to: String) = platform.posix.rename(from, to) == 0

    override fun makeDirectory(path: String) = mkdir(path, MODE.convert()) == 0

    override fun removeDirectory(path: String) = rmdir(path) == 0

    override fun readPath(machine: Boolean): RegistryValue? = null

    override fun writePath(machine: Boolean, value: RegistryValue?) = false

    override fun broadcastEnvironment() = Unit

    override fun removeAfterExit(paths: List<String>, directories: List<String>) = false

    private companion object {
        const val MODE = 493
    }
}
