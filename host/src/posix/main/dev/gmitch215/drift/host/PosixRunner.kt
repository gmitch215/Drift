@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.host

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
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
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.SIGKILL
import platform.posix.STDERR_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.WNOHANG
import platform.posix.X_OK
import platform.posix._exit
import platform.posix.access
import platform.posix.dup2
import platform.posix.errno
import platform.posix.execvp
import platform.posix.fcntl
import platform.posix.fork
import platform.posix.getenv
import platform.posix.pipe
import platform.posix.usleep
import platform.posix.waitpid
import platform.posix.close as posixClose
import platform.posix.kill as posixKill
import platform.posix.read as posixRead

// fork and exec rather than posix_spawn (absent from platform.posix); popen hides the pid
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
    val pid = fork()
    if (pid == 0) {
        // only C calls from here on: the child of a multithreaded runtime must not allocate
        dup2(fds[1], STDOUT_FILENO)
        dup2(fds[1], STDERR_FILENO)
        posixClose(fds[0])
        posixClose(fds[1])
        execvp(cargv[0], cargv)
        _exit(127)
    }
    posixClose(fds[1])
    if (pid < 0) {
        posixClose(fds[0])
        return@memScoped null
    }
    fcntl(fds[0], F_SETFL, O_NONBLOCK)
    pumpUntilExit(PosixChild(pid, fds[0]), timeoutMillis, onOutput)
}

private fun resolves(program: String?): Boolean {
    if (program.isNullOrEmpty()) return false
    if ('/' in program) return access(program, X_OK) == 0
    val path = getenv("PATH")?.toKString() ?: "/usr/bin:/bin"
    return path.split(':').any { access("${it.ifEmpty { "." }}/$program", X_OK) == 0 }
}

private class PosixChild(private val pid: Int, private val fd: Int) : ChildProcess {
    private var reaped: Int? = null

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
            val r = waitpid(pid, status.ptr, WNOHANG)
            if (r == 0) return null
            val s = status.value
            reaped = when {
                r < 0 -> -1
                s and 0x7f == 0 -> (s shr 8) and 0xff
                else -> 128 + (s and 0x7f)
            }
        }
        return reaped
    }

    override fun kill() {
        posixKill(pid, SIGKILL)
    }

    override fun close() {
        posixClose(fd)
    }
}
