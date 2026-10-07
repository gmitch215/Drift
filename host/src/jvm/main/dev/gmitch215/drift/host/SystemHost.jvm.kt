package dev.gmitch215.drift.host

import dev.gmitch215.drift.model.Attribute
import java.io.File

private object JvmHost : Host {
	override val platform = "jvm"
	override val capabilities = Capability.entries.toSet()
	override val os = System.getProperty("os.name").lowercase().substringBefore(' ')
	override val arch = System.getProperty("os.arch")

	override fun env(): Map<String, String> = System.getenv()

	override fun readText(path: String): String? = runCatching { File(path).readText() }.getOrNull()

	override fun facts(): List<Attribute> = oshiFacts()

	override fun run(argv: List<String>): CommandResult? = runCatching {
		val process = ProcessBuilder(argv).redirectErrorStream(true).start()
		process.outputStream.close()
		val output = process.inputStream.readBytes().decodeToString()
		CommandResult(process.waitFor(), output)
	}.getOrNull()
}

actual fun systemHost(): Host = JvmHost
