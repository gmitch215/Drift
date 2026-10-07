package dev.gmitch215.drift.host

import dev.gmitch215.drift.model.Attribute

data class CommandResult(val exitCode: Int, val output: String)

enum class Capability { PROCESS, FILES, ENV }

interface Host {
	val platform: String
	val capabilities: Set<Capability>
	val os: String
	val arch: String

	fun env(): Map<String, String>

	fun readText(path: String): String?

	fun run(argv: List<String>): CommandResult?

	fun facts(): List<Attribute> = emptyList()
}

expect fun systemHost(): Host
