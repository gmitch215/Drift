package dev.gmitch215.drift.build

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmbedClassificationTest {
	private fun embed(yaml: String): String {
		val dir = Files.createTempDirectory("atlas").toFile()
		val file = dir.resolve("classification.yml").also { it.writeText(yaml) }
		val out = Files.createTempDirectory("out").toFile()
		val project = ProjectBuilder.builder().build()
		project.tasks.register("embedClassification", EmbedClassification::class.java) {
			classification.set(file)
			outputDir.set(out)
		}.get().embed()
		return out.resolve(EmbedClassification.TARGET).readText()
	}

	private fun decoded(source: String): String {
		val b64 = Regex("\"([A-Za-z0-9+/=]{8,})\"").findAll(source).joinToString("") {
			it.groupValues[1]
		}
		return String(Base64.getDecoder().decode(b64))
	}

	@Test
	fun embedsTheYamlAsJsonWithoutAnId() {
		val source = embed("schema: 1\nprobes:\n  - id: a\n    note: 'KT-1 \$x'\n")
		assertTrue(source.startsWith("package dev.gmitch215.drift.scan.atlas\n"))
		assertTrue(source.contains("internal object ClassificationJson {"))
		assertFalse(source.contains("\$x"))
		assertEquals(
			"{\n\t\"schema\": 1,\n\t\"probes\": [\n\t\t{\n\t\t\t\"id\": \"a\",\n" +
				"\t\t\t\"note\": \"KT-1 \$x\"\n\t\t}\n\t]\n}\n",
			decoded(source),
		)
	}

	@Test
	fun longDataSplitsIntoChunksUnderTheConstantLimit() {
		val json = ByteArray(40000) { 'a'.code.toByte() }
		val longest = EmbedClassification.render(json).lines().maxOf { it.length }
		assertTrue(longest < 16100, "longest line $longest")
	}

	@Test
	fun missingSchemaIsRejectedByFileName() {
		val e = assertFailsWith<GradleException> { embed("probes: []\n") }
		assertTrue(e.message!!.contains("classification.yml"), e.message)
		assertTrue(e.message!!.contains("schema"), e.message)
	}

	@Test
	fun theCommittedFileEmbeds() {
		val file = File("../atlas/classification.yml")
		assertTrue(file.isFile, "run from build-core")
		val text = decoded(embed(file.readText()))
		assertTrue(text.contains("\"id\": \"kotlin.double.nan-bits\""))
	}
}
