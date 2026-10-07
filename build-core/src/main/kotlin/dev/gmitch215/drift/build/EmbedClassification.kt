package dev.gmitch215.drift.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.Base64

abstract class EmbedClassification : DefaultTask() {
	@get:InputFile
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val classification: RegularFileProperty

	@get:OutputDirectory
	abstract val outputDir: DirectoryProperty

	@TaskAction
	fun embed() {
		val file = classification.get().asFile
		val json = try {
			RuleYaml.toJson(file.readText(), file.name, requireId = false)
		} catch (e: RuleYamlException) {
			throw GradleException(e.message ?: "unreadable classification data", e)
		}
		val out = outputDir.get().asFile.resolve(TARGET)
		out.parentFile.mkdirs()
		out.writeText(render(json.toByteArray()))
	}

	companion object {
		private const val CHUNK = 16000
		const val TARGET = "dev/gmitch215/drift/scan/atlas/ClassificationJson.kt"

		/** Kotlin source for the `ClassificationJson` text; [json] is the converted file. */
		fun render(json: ByteArray): String {
			val chunks = Base64.getEncoder().encodeToString(json).chunked(CHUNK)
				.joinToString(",\n") { "                \"$it\"" }
			return """
				|package dev.gmitch215.drift.scan.atlas
				|
				|import kotlin.io.encoding.Base64
				|
				|internal object ClassificationJson {
				|    private val chunks = listOf(
				|$chunks,
				|    )
				|
				|    val text: String
				|        get() = Base64.decode(chunks.joinToString("")).decodeToString(
				|            throwOnInvalidSequence = true,
				|        )
				|}
				|
			""".trimMargin()
		}
	}
}
