@file:OptIn(ExperimentalNativeApi::class)

package dev.gmitch215.drift.host

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform

actual fun systemHost(): Host = object : Host by nativeHost(
    if (Platform.osFamily == OsFamily.MACOSX) "macos" else "linux",
    ::posixRunner,
) {
    override fun syncNanos(dir: String, rounds: Int): List<Long>? = writeSyncNanos(dir, rounds)
}
