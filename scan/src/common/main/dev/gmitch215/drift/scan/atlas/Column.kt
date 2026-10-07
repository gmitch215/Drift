package dev.gmitch215.drift.scan.atlas

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.model.ProbeStatus

class Cell(val status: ProbeStatus, val transcript: String) {
	val hash: String get() = Sha256.hex(transcript)
}

/** One transcript file: every probe result of one target on one Kotlin version. */
class Column(
	val source: String,
	val target: String,
	val version: String,
	val cells: Map<String, Cell>,
) {
	val label: String get() = "$target@$version"
}

object Transcripts {
	private const val HEADER = "@@ "
	private const val META = "# "

	/** Reads the text written by `AtlasTranscript.render`, refusing anything malformed. */
	fun read(source: String, text: String): Column {
		if (!text.endsWith("\n")) {
			throw AtlasException(
				TruncatedTranscript(source, "the text is empty or its last line has no newline"),
			)
		}
		val meta = linkedMapOf<String, String>()
		val cells = linkedMapOf<String, Cell>()
		var id: String? = null
		var status = ProbeStatus.OK
		val body = StringBuilder()

		fun flush() {
			val current = id ?: return
			if (body.isEmpty()) {
				throw AtlasException(TruncatedTranscript(source, "probe $current has no lines"))
			}
			cells[current] = Cell(status, body.toString())
			body.clear()
		}

		for (line in text.removeSuffix("\n").split('\n')) {
			if (line.startsWith(HEADER)) {
				flush()
				val rest = line.removePrefix(HEADER)
				val open = rest.lastIndexOf(" [")
				val tagged = rest.endsWith("]") && open > 0
				val name = if (tagged) rest.substring(0, open) else rest
				status = if (tagged) {
					val label = rest.substring(open + 2, rest.length - 1)
					ProbeStatus.entries.firstOrNull { it.name == label }
						?: throw AtlasException(BadHeader(source, "unknown status [$label]"))
				} else {
					ProbeStatus.OK
				}
				if (name.isBlank()) throw AtlasException(BadHeader(source, "probe has no id"))
				if (name in cells) throw AtlasException(DuplicateProbe(source, name))
				id = name
			} else if (id == null) {
				if (!line.startsWith(META) || " = " !in line) {
					throw AtlasException(
						BadHeader(source, "expected '# key = value' before the first probe: $line"),
					)
				}
				val key = line.removePrefix(META).substringBefore(" = ")
				if (key in meta) throw AtlasException(BadHeader(source, "$key is set twice"))
				meta[key] = line.substringAfter(" = ")
			} else {
				body.append(line).append('\n')
			}
		}
		flush()
		fun required(key: String) = meta[key]?.takeIf { it.isNotBlank() }
			?: throw AtlasException(BadHeader(source, "missing $key"))
		val version = required("kotlin.version")
		val target = required("kotlin.target")
		if (cells.isEmpty()) throw AtlasException(TruncatedTranscript(source, "no probes"))
		return Column(source, target, version, cells)
	}
}
