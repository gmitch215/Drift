package dev.gmitch215.drift.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

object PackagingRender {
	private val token = Regex("@(version|license|sha256:[A-Za-z0-9._-]+)@")
	private val sumLine = Regex("([0-9a-f]{64}) [ *](.+)")

	fun sums(text: String): Map<String, String> {
		val out = linkedMapOf<String, String>()
		for ((i, raw) in text.lines().withIndex()) {
			val line = raw.trimEnd('\r')
			if (line.isBlank()) continue
			val match = requireNotNull(sumLine.matchEntire(line)) {
				"SHA256SUMS line ${i + 1} is not '<sha256>  <name>'"
			}
			val name = match.groupValues[2]
			require(out.put(name, match.groupValues[1]) == null) { "SHA256SUMS lists $name twice" }
		}
		return out
	}

	fun render(text: String, version: String, sums: Map<String, String>, license: String): String =
		token.replace(text) { match ->
			when (val key = match.groupValues[1]) {
				"version" -> version

				"license" -> license

				else -> {
					val asset = "drift-$version-${key.removePrefix("sha256:")}"
					requireNotNull(sums[asset]) { "SHA256SUMS has no entry for $asset" }
				}
			}
		}
}

abstract class RenderPackaging : DefaultTask() {
	@get:Input
	abstract val version: Property<String>

	@get:InputFile
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val sums: RegularFileProperty

	@get:InputFile
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val license: RegularFileProperty

	@get:InputDirectory
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val templates: DirectoryProperty

	@get:OutputDirectory
	abstract val outputDir: DirectoryProperty

	@TaskAction
	fun render() {
		val out = outputDir.get().asFile
		out.deleteRecursively()
		out.mkdirs()
		val table = PackagingRender.sums(sums.get().asFile.readText())
		val licenseText = license.get().asFile.readText().trimEnd()
		val root = templates.get().asFile
		root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
			val target = out.resolve(file.relativeTo(root).path)
			target.parentFile.mkdirs()
			target.writeText(PackagingRender.render(file.readText(), version.get(), table, licenseText))
		}
	}
}
