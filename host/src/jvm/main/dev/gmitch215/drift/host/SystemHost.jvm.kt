package dev.gmitch215.drift.host

import dev.gmitch215.drift.model.Attribute
import java.io.File
import java.io.IOException

private object JvmHost : Host {
    override val platform = "jvm"
    override val capabilities = Capability.entries.toSet()
    override val os = normalizeOs(System.getProperty("os.name"))
    override val arch = System.getProperty("os.arch")

    override fun env(): Map<String, String> = System.getenv()

    override fun readText(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    override fun facts(): List<Attribute> = oshiFacts()

    override fun run(argv: List<String>): CommandResult? = collect(argv)

    override fun run(
        argv: List<String>,
        timeoutMillis: Long?,
        onOutput: (String) -> Unit,
    ): CommandResult? {
        val process = runCatching { ProcessBuilder(argv).redirectErrorStream(true).start() }
            .getOrNull() ?: return null
        process.outputStream.close()
        return pumpUntilExit(JvmChild(process), timeoutMillis, onOutput)
    }
}

// never blocks: a grandchild can keep the pipe open after the child has exited
private class JvmChild(private val process: Process) : ChildProcess {
    private val input = process.inputStream

    override fun read(buf: ByteArray, millis: Int): Int {
        // destroy closes the stream; whatever the reaper drained after an exit stays readable
        val n = try {
            minOf(input.available(), buf.size)
        } catch (_: IOException) {
            0
        }
        if (n > 0) {
            return try {
                input.read(buf, 0, n)
            } catch (_: IOException) {
                0
            }
        }
        if (millis > 0) Thread.sleep(millis.toLong())
        return 0
    }

    override fun exitCode(): Int? = if (process.isAlive) null else process.exitValue()

    override fun kill() {
        process.destroyForcibly()
    }

    override fun close() {
        input.close()
    }
}

actual fun systemHost(): Host = JvmHost
