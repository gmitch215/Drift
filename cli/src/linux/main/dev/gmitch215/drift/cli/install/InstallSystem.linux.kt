@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.cli.install

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import platform.posix.readlink

private object LinuxInstall : PosixInstall() {
    override fun selfPath(): String? = memScoped {
        val buffer = allocArray<ByteVar>(BUFFER)
        val n = readlink("/proc/self/exe", buffer, BUFFER.convert()).toInt()
        if (n <= 0) null else buffer.readBytes(n).decodeToString()
    }

    private const val BUFFER = 4096
}

actual fun systemInstall(): InstallSystem = LinuxInstall
