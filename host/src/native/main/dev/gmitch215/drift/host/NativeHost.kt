@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package dev.gmitch215.drift.host

import dev.gmitch215.drift.model.Attribute
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

private const val CHUNK = 8192

private fun readFile(path: String): String? {
    val file = fopen(path, "rb") ?: return null
    try {
        return readAllBytes(CHUNK) { buf ->
            buf.usePinned { fread(it.addressOf(0), 1.convert(), buf.size.convert(), file).toInt() }
        }.decodeToString()
    } finally {
        fclose(file)
    }
}

internal fun nativeHost(
    platform: String,
    runCommand: (List<String>, Long?, (String) -> Unit) -> CommandResult?,
    extraFacts: () -> List<Attribute> = { emptyList() },
    nativeEnv: () -> Map<String, String>? = { null },
): Host = ProcessHost(
    platform,
    normalizeOs(Platform.osFamily.name),
    Platform.cpuArchitecture.name.lowercase(),
    ::readFile,
    runCommand,
    extraFacts,
    nativeEnv,
)
