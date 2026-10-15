@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.cli.install

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.Foundation.NSBundle
import platform.posix.free
import platform.posix.realpath

private object MacosInstall : PosixInstall() {
    override fun selfPath(): String? {
        val raw = NSBundle.mainBundle.executablePath ?: return null
        val resolved = realpath(raw, null) ?: return raw
        return try {
            resolved.toKString()
        } finally {
            free(resolved)
        }
    }
}

actual fun systemInstall(): InstallSystem = MacosInstall
