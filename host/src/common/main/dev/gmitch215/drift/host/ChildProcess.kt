package dev.gmitch215.drift.host

import kotlin.time.TimeSource

/** A started child whose merged output is read while it runs; platforms supply the primitives. */
internal interface ChildProcess {
    /** Reads the bytes arriving within [millis]; 0 when none did, -1 once the output ended. */
    fun read(buf: ByteArray, millis: Int): Int

    /** The exit code of the direct child, or null while it runs. Never blocks. */
    fun exitCode(): Int?

    fun kill()

    fun close()
}

/** Exit code of a child killed at its deadline (the `timeout(1)` convention). */
const val TIMED_OUT_EXIT_CODE = 124

private const val POLL_MILLIS = 20
private const val CHUNK = 8192

// bounds the drain when a grandchild keeps writing after the child exits
private const val DRAIN_CHUNKS = 64

/**
 * Streams [child] until the direct child exits. A grandchild that inherited stdout and keeps it
 * open cannot hold the result back: once the child has exited, only the bytes already readable
 * are drained.
 */
internal fun pumpUntilExit(
    child: ChildProcess,
    timeoutMillis: Long?,
    onOutput: (String) -> Unit,
): CommandResult {
    val lines = LineSplitter(onOutput)
    val buf = ByteArray(CHUNK)
    val mark = TimeSource.Monotonic.markNow()
    var killed = false
    while (true) {
        val n = child.read(buf, POLL_MILLIS)
        if (n > 0) lines.feed(buf.copyOf(n))
        val exit = child.exitCode()
        if (exit != null) {
            var drains = 0
            while (drains++ < DRAIN_CHUNKS) {
                val got = child.read(buf, 0)
                if (got <= 0) break
                lines.feed(buf.copyOf(got))
            }
            lines.flush()
            child.close()
            return CommandResult(if (killed) TIMED_OUT_EXIT_CODE else exit, "")
        }
        val expired =
            timeoutMillis != null && mark.elapsedNow().inWholeMilliseconds >= timeoutMillis
        if (expired && !killed) {
            child.kill()
            killed = true
        }
    }
}
