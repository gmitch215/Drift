package dev.gmitch215.drift.host

import dev.gmitch215.drift.model.Attribute

class FakeHost(
    override val platform: String = "fake",
    override val os: String = "linux",
    override val arch: String = "x86_64",
    private val env: Map<String, String> = emptyMap(),
    private val files: Map<String, String> = emptyMap(),
    private val commands: Map<List<String>, CommandResult> = emptyMap(),
    private val canRun: Boolean = true,
    private val facts: List<Attribute> = emptyList(),
    override val capabilities: Set<Capability> = Capability.entries.toSet(),
    private val sync: List<Long>? = null,
) : Host {
    val ran = mutableListOf<List<String>>()

    override fun syncNanos(dir: String, rounds: Int): List<Long>? = sync

    override fun env(): Map<String, String> = env

    override fun readText(path: String): String? = files[path]

    override fun facts(): List<Attribute> = facts

    override fun run(argv: List<String>): CommandResult? {
        if (!canRun) return null
        ran += argv
        return commands[argv] ?: CommandResult(127, "")
    }
}
