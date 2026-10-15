@file:OptIn(ExperimentalNativeApi::class)

package dev.gmitch215.drift.host

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform

actual fun systemHost(): Host =
    nativeHost(if (Platform.osFamily == OsFamily.MACOSX) "macos" else "linux", ::posixRunner)
