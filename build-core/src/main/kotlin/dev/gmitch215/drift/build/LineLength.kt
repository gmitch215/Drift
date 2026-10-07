package dev.gmitch215.drift.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

object EditorConfig {
	fun global(text: String): Map<String, String> {
		val values = linkedMapOf<String, String>()
		var section = ""
		for (raw in text.lines()) {
			val line = raw.trim()
			if (line.startsWith("[") && line.endsWith("]")) {
				section = line.substring(1, line.length - 1)
			} else if (section == "*" && '=' in line && !line.startsWith("#")) {
				values[line.substringBefore('=').trim()] = line.substringAfter('=').trim()
			}
		}
		return values
	}
}

object LineLength {
	fun width(line: String, tab: Int): Int {
		var column = 0
		var i = 0
		while (i < line.length) {
			val cp = line.codePointAt(i)
			column = if (cp == '\t'.code) (column / tab + 1) * tab else column + 1
			i += Character.charCount(cp)
		}
		return column
	}

	fun violations(path: String, text: String, limit: Int, tab: Int): List<String> =
		text.lines().withIndex()
			.filter { width(it.value, tab) > limit }
			.map { "$path:${it.index + 1} exceeds $limit columns" }
}

abstract class LineLengthTask : DefaultTask() {
	@get:Internal
	abstract val root: DirectoryProperty

	@get:InputFile
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val editorConfig: RegularFileProperty

	@get:InputFiles
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val sources: ConfigurableFileCollection

	@TaskAction
	fun check() {
		val config = EditorConfig.global(editorConfig.get().asFile.readText())
		val limit = config["max_line_length"]?.toIntOrNull() ?: throw GradleException(
			"max_line_length is missing from the [*] section of .editorconfig",
		)
		val tab = config["tab_width"]?.toIntOrNull() ?: config["indent_size"]?.toIntOrNull() ?: 4
		val base = root.get().asFile
		val problems = sources.files.sortedBy { it.path }.flatMap {
			LineLength.violations(it.relativeTo(base).invariantSeparatorsPath, it.readText(), limit, tab)
		}
		if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n"))
	}
}
