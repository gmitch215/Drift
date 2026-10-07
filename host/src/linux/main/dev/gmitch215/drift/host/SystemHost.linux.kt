@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.host

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.pclose
import platform.posix.popen

actual fun systemHost(): Host = NativeHost("linux", { popen(it, "r") }, { pclose(it) })
