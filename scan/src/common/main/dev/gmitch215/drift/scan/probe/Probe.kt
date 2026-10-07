package dev.gmitch215.drift.scan.probe

import dev.gmitch215.drift.host.Capability
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.model.ProbeResult
import dev.gmitch215.drift.model.ProbeStatus

enum class Family { NUMERIC, TEXT, COLLECTIONS, TIME, PROCESS, RESOURCES, KOTLIN }

class ProbeContext(val host: Host) {
	private val lines = mutableListOf<String>()

	fun line(text: String) {
		lines += text
	}

	fun line(label: String, value: Any?) {
		lines += "$label = $value"
	}

	fun attempt(label: String, block: () -> Any?) {
		val outcome = try {
			"ok:" + block()
		} catch (e: Exception) {
			"err:" + e::class.simpleName + ":" + e.message
		}
		lines += "$label = $outcome"
	}

	fun transcript(): String = lines.joinToString("\n", postfix = "\n")
}

class Probe(
	val id: String,
	val family: Family,
	val needs: Set<Capability> = emptySet(),
	val body: ProbeContext.() -> Unit,
)

object ProbeRunner {
	fun run(host: Host, probes: List<Probe>): List<ProbeResult> = probes.map { run(host, it) }

	fun run(host: Host, probe: Probe): ProbeResult {
		val missing = probe.needs - host.capabilities
		if (missing.isNotEmpty()) {
			val reason = missing.joinToString(",") { it.name.lowercase() }
			return ProbeResult(probe.id, ProbeStatus.UNAVAILABLE, "requires $reason\n")
		}
		val context = ProbeContext(host)
		return try {
			probe.body(context)
			ProbeResult(probe.id, ProbeStatus.OK, context.transcript())
		} catch (e: Throwable) {
			ProbeResult(
				probe.id,
				ProbeStatus.UNAVAILABLE,
				"failed ${e::class.simpleName}: ${e.message}\n",
			)
		}
	}
}
