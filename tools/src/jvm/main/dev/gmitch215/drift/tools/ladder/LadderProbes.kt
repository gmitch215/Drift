package dev.gmitch215.drift.tools.ladder

/** One probe read from the displayed source text of the Kotlin catalog. */
class LadderProbe(val id: String, val source: String, val imports: List<String>)

/** Reads the probe sources and writes one self-contained Kotlin file per probe. */
object LadderProbes {
	private val idPattern = Regex("""\bid = "([^"]+)"""")
	private val importPattern = Regex("""(?m)^import ([\w.]+)$""")
	private const val FENCE = "\"\"\""

	fun extract(text: String): List<LadderProbe> {
		val imports = importPattern.findAll(text).map { it.groupValues[1] }
			.filter { !it.startsWith("dev.gmitch215.") }.toList()
		val probes = mutableListOf<LadderProbe>()
		var at = 0
		while (true) {
			val id = idPattern.find(text, at) ?: break
			val open = text.indexOf("source = $FENCE", id.range.last)
			if (open < 0) break
			val start = open + "source = $FENCE".length
			val end = text.indexOf(FENCE, start)
			require(end > start) { "unterminated source for ${id.groupValues[1]}" }
			val source = text.substring(start, end).replace("\${'$'}", "$").trimIndent()
			probes += LadderProbe(id.groupValues[1], source, imports)
			at = end + FENCE.length
		}
		return probes
	}

	/** Probes from every catalog file in [texts], sorted by id so file names are stable. */
	fun all(texts: List<String>): List<LadderProbe> =
		texts.flatMap(::extract).sortedBy { it.id }.also { probes ->
			require(probes.map { it.id }.toSet().size == probes.size) { "duplicate probe id" }
		}

	fun fileName(index: Int): String = "P_" + index.toString().padStart(2, '0') + ".kt"

	fun indexOf(name: String): Int? = Regex("""P_(\d+)\.kt""").matchEntire(name)
		?.groupValues?.get(1)?.toInt()

	const val CORE_NAME = "Core.kt"

	val core: String = """
		package ladder

		class Ctx {
			private val lines = StringBuilder()

			fun line(text: String) {
				lines.append(text).append('\n')
			}

			fun line(label: String, value: Any?) {
				lines.append("${'$'}label = ${'$'}value").append('\n')
			}

			fun text(): String = lines.toString()
		}

		fun Ctx.outcome(label: String, block: () -> Any?) {
			line(label, try {
				"ok:" + block()
			} catch (e: Throwable) {
				"err:" + e::class.simpleName
			})
		}

		fun Ctx.failure(label: String, block: () -> Any?) {
			line(label, try {
				"ok:" + block()
			} catch (e: Throwable) {
				"err:" + e::class.simpleName + ":" + e.message
			})
		}

		fun hex(code: Int): String = code.toString(16)

		fun codes(text: String): List<String> = text.map { hex(it - '\u0000') }
	""".trimIndent() + "\n"

	private val helpers = listOf(
		"bits" to """
			private fun bits(d: Double): String = d.toRawBits().toULong().toString(16)

			private fun bits(f: Float): String = f.toRawBits().toUInt().toString(16)
		""",
		"units" to """
			private fun units(vararg codes: Int): String = buildString {
				for (c in codes) append(c.toChar())
			}
		""",
		"find" to """
			private fun Ctx.find(label: String, pattern: String, input: String) {
				outcome(label) { Regex(pattern).containsMatchIn(input) }
			}
		""",
		"option" to """
			private fun Ctx.option(
				label: String,
				pattern: String,
				option: RegexOption,
				input: String,
			) {
				outcome(label) { Regex(pattern, option).containsMatchIn(input) }
			}
		""",
	)

	private val types = listOf(
		"Classification" to """
			private enum class Classification { DOCUMENTED, PLATFORM_DEFINED, UNCLASSIFIED }
		""",
		"Holder" to """
			private class Holder {
				lateinit var name: String
			}
		""",
		"ProbeException" to """
			private class ProbeException(message: String) : Exception(message)
		""",
	)

	private fun uses(source: String, name: String): Boolean =
		Regex("""\b$name\(""").containsMatchIn(source) && !source.contains("fun $name(")

	private fun usesType(source: String, name: String): Boolean =
		Regex("""\b$name\b""").containsMatchIn(source) && !source.contains("class $name")

	fun file(index: Int, probe: LadderProbe): String = buildString {
		append("package ladder\n\n")
		val imports = probe.imports.filter {
			Regex("""\b${it.substringAfterLast('.')}\b""").containsMatchIn(probe.source)
		}
		for (import in imports) append("import ").append(import).append('\n')
		if (imports.isNotEmpty()) append('\n')
		for ((name, text) in types) {
			if (usesType(probe.source, name)) append(text.trimIndent()).append("\n\n")
		}
		for ((name, text) in helpers) {
			if (uses(probe.source, name)) append(text.trimIndent()).append("\n\n")
		}
		append("fun probe").append(index).append("(): String {\n")
		append("\tval ctx = Ctx()\n\twith(ctx) {\n")
		for (line in probe.source.lines()) {
			if (line.isEmpty()) append('\n') else append("\t\t").append(line).append('\n')
		}
		append("\t}\n\treturn ctx.text()\n}\n")
	}

	fun main(probes: List<LadderProbe>, excluded: Set<String>): String = buildString {
		append("package ladder\n\nfun main(args: Array<String>) {\n")
		append("\tval all = listOf<Pair<String, () -> String>>(\n")
		val entries = probes.withIndex().filter { it.value.id !in excluded }.map {
			"\t\tPair(\"" + it.value.id + "\", { probe" + it.index + "() })"
		}
		append(entries.joinToString(",\n")).append('\n')
		append("\t)\n")
		append("\tval only = args.toSet()\n")
		append("\tfor ((id, run) in all) {\n")
		append("\t\tif (only.isNotEmpty() && id !in only) continue\n")
		append("\t\tvar tag = \"\"\n")
		append("\t\tval body = try {\n\t\t\trun()\n\t\t} catch (e: Throwable) {\n")
		append("\t\t\ttag = \" [UNAVAILABLE]\"\n")
		append("\t\t\t\"failed \" + e::class.simpleName + \": \" + e.message + \"\\n\"\n\t\t}\n")
		append("\t\tprint(\"@@ \" + id + tag + \"\\n\" + body)\n")
		append("\t}\n}\n")
	}

	/** Every file of the program for the probes not in [excluded], keyed by file name. */
	fun program(probes: List<LadderProbe>, excluded: Set<String>): Map<String, String> {
		val files = linkedMapOf(CORE_NAME to core)
		probes.forEachIndexed { i, p -> if (p.id !in excluded) files[fileName(i)] = file(i, p) }
		files["Main.kt"] = main(probes, excluded)
		return files
	}
}
