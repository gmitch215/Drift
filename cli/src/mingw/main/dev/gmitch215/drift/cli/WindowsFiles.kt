@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.cli

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.wcstr
import platform.windows.GetFileAttributesW

internal const val PATH_BUFFER = 32768
internal const val INVALID_ATTRIBUTES = 0xFFFFFFFFu

private const val DIRECTORY = 0x10u

internal fun fileAttributes(path: String): UInt = memScoped {
    GetFileAttributesW(path.wcstr.ptr.reinterpret()).convert()
}

internal fun isWindowsDirectory(path: String): Boolean {
    val a = fileAttributes(path)
    return a != INVALID_ATTRIBUTES && a and DIRECTORY != 0u
}

internal fun wideString(buffer: CPointer<UShortVar>, length: Int): String =
    CharArray(length) { buffer[it].toInt().toChar() }.concatToString()
