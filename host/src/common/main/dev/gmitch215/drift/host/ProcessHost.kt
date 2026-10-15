package dev.gmitch215.drift.host

import dev.gmitch215.drift.model.Attribute

/** A host that reads files and runs programs through the operating system's C interface. */
internal class ProcessHost(
    override val platform: String,
    override val os: String,
    override val arch: String,
    private val readFile: (String) -> String?,
    private val runCommand: (List<String>, Long?, (String) -> Unit) -> CommandResult?,
    private val extraFacts: () -> List<Attribute> = { emptyList() },
    private val nativeEnv: () -> Map<String, String>? = { null },
) : Host {
    override val capabilities = Capability.entries.toSet()

    override fun env(): Map<String, String> {
        nativeEnv()?.let { return it }
        val command = if (platform == "mingw") listOf("cmd", "/c", "set") else listOf("env")
        return run(command)?.let { parseEnvOutput(it.output) } ?: emptyMap()
    }

    override fun facts(): List<Attribute> = when (platform) {
        "linux" -> linuxFacts(this)
        "macos" -> macosFacts(this)
        "mingw" -> windowsFacts(this) + extraFacts()
        else -> emptyList()
    }

    override fun readText(path: String): String? = readFile(path)

    override fun run(argv: List<String>): CommandResult? = collect(argv)

    override fun run(
        argv: List<String>,
        timeoutMillis: Long?,
        onOutput: (String) -> Unit,
    ): CommandResult? = runCommand(argv, timeoutMillis, onOutput)
}
