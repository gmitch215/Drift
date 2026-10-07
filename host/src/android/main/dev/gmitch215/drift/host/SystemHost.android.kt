package dev.gmitch215.drift.host

import android.os.Build
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Stability
import java.io.File

private object AndroidHost : Host {
	override val platform = "android"
	override val capabilities = Capability.entries.toSet()
	override val os = "android"
	override val arch = System.getProperty("os.arch").orEmpty()

	override fun env(): Map<String, String> = System.getenv()

	override fun readText(path: String): String? = runCatching { File(path).readText() }.getOrNull()

	override fun facts(): List<Attribute> {
		val build = mutableMapOf(
			"os.version" to Build.VERSION.RELEASE,
			"os.api-level" to Build.VERSION.SDK_INT.toString(),
			"os.build" to Build.ID,
			"hw.model" to Build.MODEL,
			"hw.manufacturer" to Build.MANUFACTURER,
			"hw.board" to Build.HARDWARE,
		)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) build["hw.soc"] = Build.SOC_MODEL
		return linuxFacts(this) +
			build.filterValues { it.isNotBlank() }.map { (k, v) ->
				Attribute(k, v, "build", Stability.STATIC)
			}
	}

	override fun run(argv: List<String>): CommandResult? = runCatching {
		val process = ProcessBuilder(argv).redirectErrorStream(true).start()
		process.outputStream.close()
		val output = process.inputStream.readBytes().decodeToString()
		CommandResult(process.waitFor(), output)
	}.getOrNull()
}

actual fun systemHost(): Host = AndroidHost
