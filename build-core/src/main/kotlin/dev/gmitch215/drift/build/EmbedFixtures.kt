package dev.gmitch215.drift.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.Base64

abstract class EmbedFixtures : DefaultTask() {
	@get:InputDirectory
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val fixtures: DirectoryProperty

	@get:Input
	abstract val objectName: Property<String>

	@get:OutputDirectory
	abstract val outputDir: DirectoryProperty

	init {
		objectName.convention("Fixtures")
	}

	@TaskAction
	fun embed() {
		val root = fixtures.get().asFile
		val files = root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).path }
		val entries = files.joinToString("\n") { f ->
			val b64 = Base64.getEncoder().encodeToString(f.readBytes())
			val chunks = b64.chunked(CHUNK).joinToString(",\n") { "                \"$it\"" }
			val path = f.relativeTo(root).invariantSeparatorsPath
			"        \"$path\" to listOf(\n$chunks,\n        ),"
		}
		val name = objectName.get()
		val out = outputDir.get().asFile.resolve("dev/gmitch215/drift/fixtures/$name.kt")
		out.parentFile.mkdirs()
		out.writeText(
			"""
			|package dev.gmitch215.drift.fixtures
			|
			|import kotlin.io.encoding.Base64
			|
			|internal object $name {
			|    private val files = mapOf(
			|$entries
			|    )
			|
			|    val names: Set<String> get() = files.keys
			|
			|    fun bytes(name: String): ByteArray =
			|        Base64.decode(files.getValue(name).joinToString(""))
			|
			|    fun text(name: String): String = bytes(name).decodeToString(throwOnInvalidSequence = true)
			|}
			|
			""".trimMargin(),
		)
	}

	private companion object {
		const val CHUNK = 16000
	}
}
