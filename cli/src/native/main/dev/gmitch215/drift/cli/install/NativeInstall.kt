@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.cli.install

import dev.gmitch215.drift.host.readAllBytes
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.ferror
import platform.posix.fread
import platform.posix.fwrite

/** File reads and writes shared by every native target; [open] picks the path encoding. */
internal abstract class NativeInstall(private val open: (String, String) -> CPointer<FILE>?) :
    InstallSystem {
    protected abstract fun markExecutable(path: String): Boolean

    override fun read(path: String): ByteArray? {
        val file = open(path, "rb") ?: return null
        try {
            val bytes = readAllBytes(CHUNK) { buffer ->
                buffer.usePinned {
                    fread(it.addressOf(0), 1.convert(), buffer.size.convert(), file).toInt()
                }
            }
            return if (ferror(file) != 0) null else bytes
        } finally {
            fclose(file)
        }
    }

    override fun write(path: String, bytes: ByteArray, executable: Boolean): Boolean {
        val file = open(path, "wb") ?: return false
        val written = if (bytes.isEmpty()) {
            0
        } else {
            bytes.usePinned {
                fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), file).toInt()
            }
        }
        if (fclose(file) != 0 || written != bytes.size) return false
        return !executable || markExecutable(path)
    }

    override fun remove(path: String): Boolean = platform.posix.remove(path) == 0

    private companion object {
        const val CHUNK = 65536
    }
}
