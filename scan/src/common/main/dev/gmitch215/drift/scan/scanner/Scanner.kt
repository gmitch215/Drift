package dev.gmitch215.drift.scan.scanner

import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.redact.Redactor

class Scanner(
	val tool: String,
	val command: List<String>,
	val parse: (String) -> Map<String, String>,
	val group: String = tool,
)

object Scanners {
	const val ABSENT = 127

	val all: List<Scanner> = listOf(
		Scanner("java", listOf("java", "-version"), ::parseJava),
		Scanner("cc", listOf("gcc", "--version"), ::parseCc),
		Scanner("go", listOf("go", "version"), ::parseGo),
		Scanner("php", listOf("php", "-v"), ::parsePhp),
		Scanner("node", listOf("node", "--version"), ::parseNode),
		Scanner("python", listOf("python3", "--version"), ::parsePython),
		Scanner("rust", listOf("rustc", "--version"), ::parseRust),
		Scanner("git", listOf("git", "--version"), ::parseGit),
		Scanner("coreutils", listOf("ls", "--version"), ::parseCoreutils),
		Scanner("libc", listOf("ldd", "--version"), ::parseLibc),
		Scanner(
			"visualstudio",
			listOf("vswhere", "-latest", "-property", "installationVersion"),
			::parseVisualStudio,
		),
		Scanner("python-managed", listOf("python3", "-c", PEP_668_CHECK), ::parseManaged, "python"),
	)

	fun attributes(scanner: Scanner, text: String): List<Attribute> {
		val clean = text.replace("\r", "").trimEnd()
		val source = "scanner:" + scanner.command.joinToString(" ")
		val parsed = scanner.parse(clean)
		if (parsed.isEmpty()) {
			if (scanner.group != scanner.tool) return emptyList()
			val line = clean.lineSequence().firstOrNull().orEmpty()
			val raw = Redactor.redactText(line.take(MAX_RAW))
			return listOf(
				Attribute("tool.${scanner.tool}.version", "unparsed", source),
				Attribute("tool.${scanner.tool}.raw", raw, source),
			).filter { it.value.isNotEmpty() }
		}
		return parsed.map { (key, value) -> Attribute("tool.${scanner.group}.$key", value, source) }
	}

	fun scan(host: Host): List<Attribute> = all.flatMap { scanner ->
		val result = host.run(scanner.command)
		if (result == null || result.exitCode == ABSENT) {
			emptyList()
		} else {
			attributes(scanner, result.output)
		}
	}

	fun fromTranscripts(entries: Map<String, KitEntry>): List<Attribute> {
		val result = mutableListOf<Attribute>()
		for (scanner in all) {
			val entry = entries[scanner.tool] ?: continue
			if (entry.exitCode != ABSENT) result += attributes(scanner, entry.output)
		}
		return result
	}

	private const val MAX_RAW = 120

	private const val PEP_668_CHECK =
		"import os,sysconfig;print(os.path.exists(os.path.join(" +
			"sysconfig.get_path(\"stdlib\"),\"EXTERNALLY-MANAGED\")))"
}

class KitEntry(val exitCode: Int, val output: String)

private fun first(text: String, pattern: String): MatchResult? =
	text.lineSequence().firstNotNullOfOrNull { Regex(pattern).find(it) }

internal fun parseJava(text: String): Map<String, String> {
	val m = first(text, "^(\\w+) version \"([^\"]+)\"") ?: return emptyMap()
	return mapOf("implementation" to m.groupValues[1], "version" to m.groupValues[2])
}

internal fun parseCc(text: String): Map<String, String> {
	first(text, "clang version (\\d+(?:\\.\\d+)+)")?.let {
		return mapOf("family" to "clang", "version" to it.groupValues[1])
	}
	val m = first(text, "^g?cc \\(.*\\)\\s+(\\d+(?:\\.\\d+)+)") ?: return emptyMap()
	return mapOf("family" to "gcc", "version" to m.groupValues[1])
}

internal fun parseGo(text: String): Map<String, String> {
	val m = first(text, "^go version go(\\S+) (\\w+)/(\\w+)") ?: return emptyMap()
	val (version, os, arch) = m.destructured
	return mapOf("version" to version, "os" to os, "arch" to arch)
}

internal fun parsePhp(text: String): Map<String, String> {
	val m = first(text, "^PHP (\\d+\\.\\d+\\.\\d+\\S*) \\((\\w+)\\)") ?: return emptyMap()
	val result = mutableMapOf("version" to m.groupValues[1], "sapi" to m.groupValues[2])
	first(text, "\\(\\s*(NTS|ZTS)\\s*\\)")?.let { result["thread-safety"] = it.groupValues[1] }
	return result
}

internal fun parseNode(text: String): Map<String, String> {
	val m = first(text, "^v(\\d+\\.\\d+\\.\\d+\\S*)$") ?: return emptyMap()
	return mapOf("version" to m.groupValues[1])
}

internal fun parsePython(text: String): Map<String, String> {
	val m = first(text, "^Python (\\d+\\.\\d+\\.\\d+\\S*)") ?: return emptyMap()
	return mapOf("version" to m.groupValues[1])
}

internal fun parseRust(text: String): Map<String, String> {
	val m = first(text, "^rustc (\\d+\\.\\d+\\.\\d+\\S*)") ?: return emptyMap()
	return mapOf("version" to m.groupValues[1])
}

internal fun parseGit(text: String): Map<String, String> {
	val m = first(text, "^git version (\\d+(?:\\.\\d+)+)") ?: return emptyMap()
	return mapOf("version" to m.groupValues[1])
}

internal fun parseCoreutils(text: String): Map<String, String> {
	first(text, "\\(GNU coreutils\\) (\\S+)")?.let {
		return mapOf("flavor" to "gnu", "version" to it.groupValues[1])
	}
	if ("BusyBox" in text) return mapOf("flavor" to "busybox")
	return if (text.isBlank()) emptyMap() else mapOf("flavor" to "other")
}

internal fun parseLibc(text: String): Map<String, String> {
	first(text, "^ldd \\(.*\\) (\\d+\\.\\d+(?:\\.\\d+)?)")?.let {
		return mapOf("family" to "glibc", "version" to it.groupValues[1])
	}
	if (first(text, "^musl libc") == null) return emptyMap()
	val version = first(text, "^Version (\\d+\\.\\d+\\.\\d+)") ?: return mapOf("family" to "musl")
	return mapOf("family" to "musl", "version" to version.groupValues[1])
}

internal fun parseVisualStudio(text: String): Map<String, String> {
	val m = first(text, "^(\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?)$") ?: return emptyMap()
	return mapOf("version" to m.groupValues[1])
}

internal fun parseManaged(text: String): Map<String, String> {
	val m = first(text, "^(True|False)$") ?: return emptyMap()
	return mapOf("externally-managed" to m.groupValues[1].lowercase())
}
