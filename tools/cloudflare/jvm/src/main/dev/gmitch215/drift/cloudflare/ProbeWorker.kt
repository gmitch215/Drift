package dev.gmitch215.drift.cloudflare

import dev.gmitch215.bytebox.Bytebox
import dev.gmitch215.bytebox.Env
import dev.gmitch215.bytebox.ExecutionCtx
import dev.gmitch215.bytebox.Request
import dev.gmitch215.bytebox.Response
import dev.gmitch215.bytebox.Worker

private const val TRAP =
    "the compiled module traps on integer division and a trap aborts the request"

private val refused = mapOf(
    "kotlin.exceptions.messages" to "$TRAP (RuntimeError: divide by zero)",
    "kotlin.int.overflow" to "$TRAP (RuntimeError: divide result unrepresentable)",
)

object ProbeWorker : Worker {
    override fun fetch(request: Request, env: Env, ctx: ExecutionCtx): Response =
        Bytebox.response(probeTranscript("cloudflare-jvm", refused))
}
