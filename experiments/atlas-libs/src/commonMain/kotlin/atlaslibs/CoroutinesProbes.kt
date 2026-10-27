package atlaslibs

import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

class Boom(message: String) : Exception(message)

val coroutinesProbes = listOf(
    Probe("coroutines.timeout-messages") {
        attempt("withTimeout[1ms]") { withTimeout(1) { awaitCancellation() } }
        attempt("withTimeout[0]") { withTimeout(0) { 1 } }
        attempt("withTimeout[-1]") { withTimeout(-1) { 1 } }
        attempt("withTimeout-duration[1ms]") { withTimeout(1.milliseconds) { awaitCancellation() } }
        attempt("withTimeout-duration[1500us]") {
            withTimeout(1500.microseconds) { awaitCancellation() }
        }
        attempt("withTimeout-duration[1ns]") { withTimeout(1.nanoseconds) { awaitCancellation() } }
        attempt("withTimeout-duration[zero]") { withTimeout(Duration.ZERO) { 1 } }
        attempt("withTimeout-duration[-1s]") { withTimeout((-1).seconds) { 1 } }
        attempt("withTimeout-duration[infinite]") { withTimeout(Duration.INFINITE) { "done" } }
        attempt("withTimeoutOrNull[1ms]") { withTimeoutOrNull(1) { awaitCancellation() } }
        attempt("withTimeoutOrNull[0]") { withTimeoutOrNull(0) { 1 } }
        attempt("withTimeoutOrNull-duration[infinite]") {
            withTimeoutOrNull(Duration.INFINITE) { "done" }
        }
        attempt("delay[0]") { delay(0) }
        attempt("delay[-1]") { delay(-1) }
        attempt("delay-duration[zero]") { delay(Duration.ZERO) }
        attempt("delay-duration[-1s]") { delay((-1).seconds) }
        attempt("timeout-is-cancellation") {
            try {
                withTimeout(1) { awaitCancellation() }
            } catch (e: TimeoutCancellationException) {
                "${e is CancellationException} ${e.cause == null} ${e.cause}"
            }
        }
    },
    Probe("coroutines.cancellation") {
        attempt("cancel-message") {
            coroutineScope {
                val job = launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
                job.cancel(CancellationException("stop"))
                job.join()
                "${job.isCancelled} ${job.isCompleted}"
            }
        }
        attempt("cancel-cause-message") {
            coroutineScope {
                val d = async(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
                d.cancel(CancellationException("because", Boom("root")))
                try {
                    d.await()
                } catch (e: CancellationException) {
                    "${e.message} cause=${e.cause?.message}"
                }
            }
        }
        attempt("cancel-no-message") {
            coroutineScope {
                val d = async(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
                d.cancel()
                try {
                    d.await()
                } catch (e: CancellationException) {
                    "${e.message}"
                }
            }
        }
        attempt("ensureActive-after-cancel") {
            coroutineScope {
                val job = coroutineContext.job
                val c = async(start = CoroutineStart.UNDISPATCHED) {
                    coroutineContext.job.cancel()
                    try {
                        coroutineContext.ensureActive()
                        "active"
                    } catch (e: CancellationException) {
                        "${e.message}"
                    }
                }
                c.await()
            }
        }
        attempt("noncancellable-after-cancel") {
            coroutineScope {
                val d = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { yield() }
                    }
                }
                d.cancelAndJoin()
                d.isCancelled
            }
        }
        attempt("completable-cancel") {
            val c = CompletableDeferred<Int>()
            c.cancel(CancellationException("x"))
            "${c.isCancelled} ${c.complete(1)}"
        }
        attempt("completable-complete-twice") {
            val c = CompletableDeferred<Int>()
            "${c.complete(1)} ${c.complete(2)} ${c.getCompleted()}"
        }
        attempt("completable-exception") {
            val c = CompletableDeferred<Int>()
            c.completeExceptionally(Boom("b"))
            try {
                c.await()
            } catch (e: Boom) {
                e.message
            }
        }
        attempt("getCompleted-active") { CompletableDeferred<Int>().getCompleted() }
    },
    Probe("coroutines.ordering") {
        val events = mutableListOf<String>()
        coroutineScope {
            launch(Dispatchers.Unconfined) {
                events += "u1"
                yield()
                events += "u2"
            }
            events += "after-unconfined"
        }
        line("unconfined-yield", events.joinToString(","))
        val e2 = mutableListOf<String>()
        coroutineScope {
            val j = launch(start = CoroutineStart.UNDISPATCHED) {
                e2 += "undispatched-start"
                yield()
                e2 += "undispatched-after-yield"
            }
            e2 += "after-launch"
            j.join()
        }
        line("undispatched", e2.joinToString(","))
        val serial = Dispatchers.Default.limitedParallelism(1)
        val e4 = mutableListOf<String>()
        coroutineScope {
            val x = async(Dispatchers.Unconfined) {
                e4 += "x-start"
                withContext(serial) { e4 += "x-inner" }
                e4 += "x-end"
            }
            e4 += "main-after-async"
            x.await()
        }
        line("unconfined-switch", e4.joinToString(","))
        val e5 = mutableListOf<String>()
        coroutineScope {
            repeat(5) { i -> launch(serial) { e5 += "l$i" } }
        }
        line("serial-launch-order", e5.joinToString(","))
        val e6 = mutableListOf<String>()
        coroutineScope {
            launch(Dispatchers.Unconfined) {
                e6 += "outer-start"
                launch(Dispatchers.Unconfined) { e6 += "inner" }
                e6 += "outer-end"
            }
        }
        line("unconfined-nested", e6.joinToString(","))
    },
    Probe("coroutines.exceptions") {
        attempt("coroutineScope-child-failure") {
            try {
                coroutineScope {
                    launch { throw Boom("child") }
                    launch { awaitCancellation() }
                }
            } catch (e: Boom) {
                "Boom:${e.message}"
            }
        }
        attempt("async-await-failure") {
            try {
                coroutineScope {
                    val d = async { throw Boom("deferred") }
                    d.await()
                }
            } catch (e: Boom) {
                "Boom:${e.message}"
            }
        }
        attempt("supervisor-child-failure") {
            supervisorScope {
                val d = async { throw Boom("sup") }
                try {
                    d.await()
                } catch (e: Boom) {
                    "Boom:${e.message}"
                }
            }
        }
        attempt("cancellation-in-launch-not-propagated") {
            coroutineScope {
                launch { throw CancellationException("quiet") }
                "scope-survived"
            }
        }
        attempt("finally-exception-wins") {
            try {
                coroutineScope {
                    launch {
                        try {
                            awaitCancellation()
                        } finally {
                            throw Boom("from-finally")
                        }
                    }
                    launch { throw Boom("trigger") }
                }
            } catch (e: Boom) {
                "${e.message} suppressed=${e.suppressedExceptions.map { it.message }}"
            }
        }
        attempt("withContext-exception-type") {
            try {
                withContext(Dispatchers.Default) { throw Boom("ctx") }
            } catch (e: Boom) {
                "Boom:${e.message}"
            }
        }
    },
    Probe("coroutines.channel-flow") {
        attempt("conflated") {
            val c = Channel<Int>(Channel.CONFLATED)
            c.trySend(1)
            c.trySend(2)
            c.trySend(3)
            "${c.receive()} ${c.tryReceive().isSuccess}"
        }
        attempt("unlimited-order") {
            val c = Channel<Int>(Channel.UNLIMITED)
            repeat(5) { c.trySend(it) }
            c.close()
            val out = mutableListOf<Int>()
            for (v in c) out += v
            out
        }
        attempt("rendezvous-trySend") { Channel<Int>(Channel.RENDEZVOUS).trySend(1).isSuccess }
        attempt("buffered-trySend-full") {
            val c = Channel<Int>(1)
            "${c.trySend(1).isSuccess} ${c.trySend(2).isSuccess} ${c.trySend(3).isFailure}"
        }
        attempt("closed-trySend") {
            val c = Channel<Int>(1)
            c.close(Boom("closed"))
            val r = c.trySend(1)
            "${r.isClosed} ${r.exceptionOrNull()?.message}"
        }
        attempt("closed-receiveCatching") {
            val c = Channel<Int>(1)
            c.close(Boom("c2"))
            val r = c.receiveCatching()
            "${r.isClosed} ${r.exceptionOrNull()?.message}"
        }
        attempt("closed-no-cause-receive") {
            val c = Channel<Int>(1)
            c.close()
            c.receive()
        }
        attempt("cancel-channel-receive") {
            val c = Channel<Int>(1)
            c.cancel()
            c.receive()
        }
        attempt("channel-capacity-0-neg") { Channel<Int>(-5) }
        attempt("channel-capacity-conflated-buffer") {
            Channel<Int>(Channel.CONFLATED, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
        }
        attempt("channel-drop-oldest") {
            val c = Channel<Int>(2, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
            repeat(5) { c.trySend(it) }
            c.close()
            val out = mutableListOf<Int>()
            for (v in c) out += v
            out
        }
        attempt("flow-buffer-order") {
            flow { repeat(3) { emit(it) } }.buffer().toList()
        }
        attempt("flow-exception") {
            try {
                flow { emit(1); throw Boom("f") }.toList()
            } catch (e: Boom) {
                e.message
            }
        }
    },
)
