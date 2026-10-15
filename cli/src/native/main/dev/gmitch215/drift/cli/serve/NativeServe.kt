@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.cli.serve

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.signal
import kotlin.concurrent.AtomicInt

private val interrupt = AtomicInt(0)

internal fun trapInterruptSignals() {
    interrupt.value = 0
    val handler = staticCFunction<Int, Unit> { interrupt.value = 1 }
    signal(SIGINT, handler)
    signal(SIGTERM, handler)
}

internal fun interruptSignalled() = interrupt.value != 0

/** Runs the block behind a [kotlinx.cinterop.StableRef] a new thread was started with. */
internal fun runBlock(arg: COpaquePointer?) {
    val holder = arg!!.asStableRef<() -> Unit>()
    try {
        holder.get().invoke()
    } finally {
        holder.dispose()
    }
}
