package dev.gmitch215.drift.tools.ladder

/** Compiler errors and program output turned into the transcript format of `atlas record`. */
object LadderRun {
	private val classic = Regex("""P_(\d+)\.kt:\d+:\d+:?\s+error:\s*(.*)""")
	private val gradle = Regex("""^e: \S*P_(\d+)\.kt:\d+:\d+:?\s*(.*)""")

	/** First error message per probe index, in order of appearance. */
	fun errors(log: String): Map<Int, String> {
		val found = linkedMapOf<Int, String>()
		for (line in log.lines()) {
			val m = classic.find(line) ?: gradle.find(line) ?: continue
			val text = m.groupValues[2].trim().replace(Regex("\\s+"), " ")
			found.getOrPut(m.groupValues[1].toInt()) { text }
		}
		return found
	}

	/** True when the log has an error line that [errors] cannot attribute to a probe file. */
	fun hasUnattributed(log: String): Boolean = log.lines().any {
		(it.contains(": error:") || it.startsWith("e: ") || it.startsWith("error:")) &&
			classic.find(it) == null && gradle.find(it) == null
	}

	fun parseExcluded(text: String): Map<String, String> = text.lines().filter { it.isNotBlank() }
		.associate { it.substringBefore('\t') to it.substringAfter('\t') }

	fun renderExcluded(excluded: Map<String, String>): String =
		excluded.entries.joinToString("") { "${it.key}\t${it.value}\n" }

	/** Blocks of a program run: probe id to header status and body. */
	fun parseRun(output: String): Map<String, Pair<String, String>> {
		val blocks = linkedMapOf<String, Pair<String, String>>()
		var id: String? = null
		var status = ""
		val body = StringBuilder()
		fun flush() {
			id?.let { blocks[it] = status to body.toString() }
			body.clear()
		}
		for (line in output.removeSuffix("\n").split('\n')) {
			if (line.startsWith("@@ ")) {
				flush()
				val rest = line.removePrefix("@@ ")
				val tagged = rest.endsWith("]") && rest.contains(" [")
				id = if (tagged) rest.substringBeforeLast(" [") else rest
				status = if (tagged) rest.substringAfterLast(" [").removeSuffix("]") else ""
			} else {
				body.append(line).append('\n')
			}
		}
		flush()
		return blocks
	}

	fun assemble(
		version: String,
		target: String,
		ids: List<String>,
		excluded: Map<String, String>,
		output: String,
		exits: Map<String, String> = emptyMap(),
	): String {
		val run = parseRun(output)
		return buildString {
			append("# kotlin.version = ").append(version).append('\n')
			append("# kotlin.target = ").append(target).append('\n')
			for (id in ids.sorted()) {
				val error = excluded[id]
				val ran = run[id]
				when {
					error != null -> {
						append("@@ ").append(id).append(" [UNAVAILABLE]\n")
						append("does not compile: ").append(error).append('\n')
					}

					ran == null -> {
						append("@@ ").append(id).append(" [UNAVAILABLE]\n")
						append("no output: the program ended before this probe finished")
						exits[id]?.let { append(" (exit ").append(it).append(')') }
						append('\n')
					}

					else -> {
						append("@@ ").append(id)
						if (ran.first.isNotEmpty()) append(" [").append(ran.first).append(']')
						append('\n').append(ran.second)
					}
				}
			}
		}
	}
}
