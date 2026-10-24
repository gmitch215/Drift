package dev.gmitch215.drift.host

import dev.gmitch215.drift.model.Attribute

/**
 * What the child cost, in whole milliseconds and KiB. A field is null when the platform cannot
 * measure it, never zero: absent means unknown.
 */
data class ResourceUsage(val userMs: Long?, val systemMs: Long?, val peakKb: Long?)

data class CommandResult(val exitCode: Int, val output: String, val usage: ResourceUsage? = null)

enum class Capability { PROCESS, FILES, ENV }

interface Host {
    val platform: String
    val capabilities: Set<Capability>
    val os: String
    val arch: String

    fun env(): Map<String, String>

    fun readText(path: String): String?

    fun run(argv: List<String>): CommandResult?

    /**
     * Like [run], but hands the merged output to [onOutput] while the process runs. The result
     * carries the exit code and an empty `output`; the callback has seen all of it. Past
     * [timeoutMillis] the child is killed and the result carries [TIMED_OUT_EXIT_CODE]. A host
     * that can measure the child fills [CommandResult.usage].
     */
    fun run(
        argv: List<String>,
        timeoutMillis: Long? = null,
        onOutput: (String) -> Unit,
    ): CommandResult? = run(argv)?.let {
        if (it.output.isNotEmpty()) onOutput(it.output)
        it.copy(output = "")
    }

    fun facts(): List<Attribute> = emptyList()
}

expect fun systemHost(): Host

internal fun Host.collect(argv: List<String>): CommandResult? {
    val all = StringBuilder()
    return run(argv) { all.append(it) }?.copy(output = "$all")
}

/** Hands whole lines to [sink]; a line can arrive in several byte pieces. */
internal class LineSplitter(private val sink: (String) -> Unit) {
    private var pending = ByteArray(0)

    fun feed(bytes: ByteArray) {
        pending += bytes
        if (bytes.lastOrNull() == NEWLINE) flush()
    }

    fun flush() {
        if (pending.isNotEmpty()) sink(pending.decodeToString())
        pending = ByteArray(0)
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
    }
}

internal fun normalizeOs(raw: String): String {
    val name = raw.trim().lowercase()
    return when {
        name.startsWith("mac") || name == "darwin" -> "macos"
        name.startsWith("linux") -> "linux"
        name.startsWith("windows") || name.startsWith("mingw") -> "windows"
        name.startsWith("freebsd") -> "freebsd"
        name == "android" -> "android"
        name == "ios" -> "ios"
        else -> "unknown"
    }
}
