@file:OptIn(ExperimentalForeignApi::class, ObsoleteWorkersApi::class, NativeRuntimeApi::class)

package dev.gmitch215.drift.host

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.setenv
import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class PosixRunnerTest {
    @Test
    fun theChildInheritsTheEnvironment() {
        setenv("DRIFT_SPAWN_PROBE", "inherited", 1)
        val seen = StringBuilder()
        val result = posixRunner(listOf("sh", "-c", "printf %s \"\$DRIFT_SPAWN_PROBE\""), null) {
            seen.append(it)
        }
        assertEquals(0, result?.exitCode)
        assertEquals("inherited", seen.toString())
    }

    @Test
    fun aMissingProgramIsNotRun() {
        assertNull(posixRunner(listOf("drift-no-such-program"), null) {})
    }

    @Test
    fun spawnsNeverHangWhileOtherThreadsCollectGarbage() {
        val calls = getenv("DRIFT_FORK_STRESS_CALLS")?.toKString()?.toIntOrNull() ?: return
        val stop = AtomicInt(0)
        val workers = List(3) { Worker.start() }
        val running = workers.mapIndexed { i, worker ->
            worker.execute(TransferMode.SAFE, { Pair(stop, i) }) { (flag, id) ->
                var keep = ArrayList<ByteArray>()
                var n = 0
                while (flag.value == 0) {
                    keep.add(ByteArray(4096))
                    if (keep.size > 20000) keep = ArrayList()
                    if (id == 0 && ++n % 200 == 0) GC.collect()
                }
                n
            }
        }
        val mark = TimeSource.Monotonic.markNow()
        var hangs = 0
        var failures = 0
        repeat(calls) {
            val result = posixRunner(listOf("true"), DEADLINE_MILLIS) {}
            when (result?.exitCode) {
                0 -> Unit
                TIMED_OUT_EXIT_CODE -> hangs++
                else -> failures++
            }
        }
        stop.value = 1
        running.forEach { it.result }
        workers.forEach { it.requestTermination().result }
        val took = mark.elapsedNow()
        println("spawn stress: $calls calls, $hangs hangs, $failures failures, $took")
        assertEquals(0, hangs, "spawns that never reached exec")
        assertEquals(0, failures, "spawns that failed")
    }

    @Test
    fun aFinishedChildReportsItsCpuTime() {
        val burn = "i=0; while [ \$i -lt 300000 ]; do i=\$((i+1)); done"
        val usage = posixRunner(listOf("sh", "-c", burn), null) {}?.usage
        assertNotNull(usage)
        assertTrue(usage.userMs!! + usage.systemMs!! > 0, "$usage")
    }

    @Test
    fun aPeakAtOrUnderThisProcessesPeakIsUnknownNotInflated() {
        val held = ByteArray(64 * 1024 * 1024) { 1 }
        val small = posixRunner(listOf("sh", "-c", "exit 0"), null) {}?.usage
        assertNotNull(small)
        assertNull(small.peakKb, "held ${held.size} bytes, usage $small")
    }

    @Test
    fun aPeakAboveThisProcessesPeakIsReported() {
        val grow = "awk 'BEGIN { s = \"a\"; for (i = 0; i < 28; i++) s = s s }'"
        val usage = posixRunner(listOf("sh", "-c", grow), null) {}?.usage
        assertNotNull(usage)
        assertTrue((usage.peakKb ?: 0) > 200 * 1024, "$usage")
    }

    private companion object {
        const val DEADLINE_MILLIS = 5000L
    }
}
