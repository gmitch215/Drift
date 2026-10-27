@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.host

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_WRONLY
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close
import platform.posix.fsync
import platform.posix.open
import platform.posix.unlink
import platform.posix.write
import kotlin.random.Random
import kotlin.time.TimeSource

private const val BLOCK = 4096

internal fun writeSyncNanos(dir: String, rounds: Int): List<Long>? {
    val path = "$dir/drift-fsync-${Random.nextLong().toString(16)}"
    val fd = open(path, O_WRONLY or O_CREAT or O_EXCL, S_IRUSR or S_IWUSR)
    if (fd < 0) return null
    try {
        return memScoped {
            val block = allocArray<ByteVar>(BLOCK)
            List(rounds) {
                val mark = TimeSource.Monotonic.markNow()
                write(fd, block, BLOCK.convert())
                fsync(fd)
                mark.elapsedNow().inWholeNanoseconds
            }
        }
    } finally {
        close(fd)
        unlink(path)
    }
}
