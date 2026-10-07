package dev.gmitch215.drift.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class EmbedKotlinVersion : DefaultTask() {
	@get:Input
	abstract val version: Property<String>

	@get:OutputDirectory
	abstract val outputDir: DirectoryProperty

	@TaskAction
	fun embed() {
		val out = outputDir.get().asFile.resolve("dev/gmitch215/drift/scan/KotlinBuild.kt")
		out.parentFile.mkdirs()
		out.writeText(
			"""
			|package dev.gmitch215.drift.scan
			|
			|object KotlinBuild {
			|    const val VERSION = "${version.get()}"
			|}
			|
			""".trimMargin(),
		)
	}
}
