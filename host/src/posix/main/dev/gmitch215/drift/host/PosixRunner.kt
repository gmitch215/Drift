@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.host

import dev.gmitch215.drift.host.spawn.drift_reap
import dev.gmitch215.drift.host.spawn.drift_self_peak_kb
import dev.gmitch215.drift.host.spawn.drift_spawn
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.EAGAIN
import platform.posix.ENOMEM
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.SIGKILL
import platform.posix.X_OK
import platform.posix.access
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.getenv
import platform.posix.pipe
import platform.posix.usleep
import platform.posix.close as posixClose
import platform.posix.kill as posixKill
import platform.posix.read as posixRead

// posix_spawn through C: a forked child of this runtime must not run Kotlin before exec
internal fun posixRunner(
    argv: List<String>,
    timeoutMillis: Long?,
    onOutput: (String) -> Unit,
): CommandResult? = memScoped {
    if (!resolves(argv.firstOrNull())) return@memScoped null
    val fds = allocArray<IntVar>(2)
    if (pipe(fds) != 0) return@memScoped null
    val cargv = allocArray<CPointerVar<ByteVar>>(argv.size + 1)
    argv.forEachIndexed { i, arg -> cargv[i] = arg.cstr.getPointer(this) }
    val pid = alloc<IntVar>()
    val floorKb = drift_self_peak_kb()
    val rc = drift_spawn(cargv, fds[0], fds[1], pid.ptr)
    posixClose(fds[1])
    if (rc != 0) {
        posixClose(fds[0])
        return@memScoped if (rc == EAGAIN || rc == ENOMEM) null else CommandResult(127, "")
    }
    fcntl(fds[0], F_SETFL, O_NONBLOCK)
    pumpUntilExit(PosixChild(pid.value, fds[0], floorKb), timeoutMillis, onOutput)
}

private fun resolves(program: String?): Boolean =
    findProgram(program, getenv("PATH")?.toKString() ?: "/usr/bin:/bin") {
        access(it, X_OK) == 0
    }

// the child starts with this process's peak resident size, so a peak at or under it is unknown
private class PosixChild(private val pid: Int, private val fd: Int, private val floorKb: Long) :
    ChildProcess {
    private var reaped: Int? = null
    private var used: ResourceUsage? = null

    override fun read(buf: ByteArray, millis: Int): Int {
        val n = posixRead(fd, buf.refTo(0), buf.size.convert()).toInt()
        if (n > 0) return n
        usleep((minOf(millis, 10) * 1000).convert())
        return if (n < 0 && errno == EAGAIN) 0 else -1
    }

    override fun exitCode(): Int? {
        reaped?.let { return it }
        memScoped {
            val status = alloc<IntVar>()
            val times = allocArray<LongVar>(3)
            val r = drift_reap(pid, status.ptr, times)
            if (r == 0) return null
            val s = status.value
            reaped = if (r < 0) -1 else waitStatusCode(s)
            if (r > 0) {
                used = ResourceUsage(times[0], times[1], times[2].takeIf { it > floorKb })
            }
        }
        return reaped
    }

    override fun usage(): ResourceUsage? = used

    override fun kill() {
        posixKill(pid, SIGKILL)
    }

    override fun close() {
        posixClose(fd)
    }
}
