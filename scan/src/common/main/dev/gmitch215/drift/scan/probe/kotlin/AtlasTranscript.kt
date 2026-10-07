package dev.gmitch215.drift.scan.probe.kotlin

import dev.gmitch215.drift.model.ProbeResult
import dev.gmitch215.drift.model.ProbeStatus
import dev.gmitch215.drift.scan.KotlinBuild

/** Canonical text form of one target's Kotlin probe results, sorted by probe id. */
object AtlasTranscript {
	private const val HEADER = "@@ "
	private const val META = "# "

	class Parsed(val meta: Map<String, String>, val probes: Map<String, String>)

	fun render(target: String, results: List<ProbeResult>): String = buildString {
		append(META).append("kotlin.version = ").append(KotlinBuild.VERSION).append('\n')
		append(META).append("kotlin.target = ").append(target).append('\n')
		for (result in results.sortedBy { it.id }) {
			append(HEADER).append(result.id)
			if (result.status != ProbeStatus.OK) append(" [").append(result.status).append(']')
			append('\n').append(result.transcript)
		}
	}

	fun parse(text: String): Parsed {
		val meta = linkedMapOf<String, String>()
		val probes = linkedMapOf<String, String>()
		var id: String? = null
		val body = StringBuilder()
		fun flush() {
			id?.let { probes[it] = body.toString() }
			body.clear()
		}
		for (line in text.lines().dropLast(1)) {
			if (line.startsWith(HEADER)) {
				flush()
				id = line.removePrefix(HEADER).substringBefore(" [")
			} else if (id == null && line.startsWith(META)) {
				meta[line.removePrefix(META).substringBefore(" = ")] = line.substringAfter(" = ")
			} else {
				body.append(line).append('\n')
			}
		}
		flush()
		return Parsed(meta, probes)
	}
}
