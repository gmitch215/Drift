@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.cli

import dev.gmitch215.drift.host.readAllBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.stdout

internal fun posixWriteText(path: String, text: String, mkdir: (String) -> Unit): Boolean =
    posixWriteBytes(path, text.encodeToByteArray(), mkdir)

internal fun posixWriteBytes(path: String, bytes: ByteArray, mkdir: (String) -> Unit): Boolean {
    for (dir in pathPrefixes(path, '/', 1, parentsOnly = true)) {
        if (dir.isNotEmpty()) mkdir(dir)
    }
    val file = fopen(path, "wb") ?: return false
    val written = if (bytes.isEmpty()) {
        0
    } else {
        bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), file).toInt() }
    }
    return (fclose(file) == 0) && written == bytes.size
}

actual fun readBinaryFile(path: String): ByteArray? {
    val file = fopen(path, "rb") ?: return null
    try {
        return readAllBytes(65536) { buffer ->
            buffer.usePinned {
                fread(it.addressOf(0), 1.convert(), buffer.size.convert(), file).toInt()
            }
        }
    } finally {
        fclose(file)
    }
}

actual fun writeStdout(text: String) {
    val bytes = text.encodeToByteArray()
    if (bytes.isNotEmpty()) {
        bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), stdout) }
    }
    fflush(stdout)
}
