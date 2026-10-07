package dev.gmitch215.drift.build

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmbedRulesTest {
	@Test
	fun rendersEntriesSortedByIdWithBase64Bodies() {
		val src = EmbedRules.render(
			mapOf("zeta" to "{\"z\":1}".toByteArray(), "alpha" to "{\"a\":\"\$x\"}".toByteArray()),
		)
		assertTrue(src.startsWith("package dev.gmitch215.drift.know\n"))
		assertTrue(src.indexOf("\"alpha\" to") < src.indexOf("\"zeta\" to"))
		val body = "{\"a\":\"\$x\"}".toByteArray()
		assertTrue(src.contains(Base64.getEncoder().encodeToString(body)))
		assertFalse(src.contains("\$x"))
	}

	@Test
	fun longBodiesSplitIntoChunksUnderTheConstantLimit() {
		val src = EmbedRules.render(mapOf("big" to ByteArray(30000) { 'a'.code.toByte() }))
		val longest = src.lines().maxOf { it.length }
		assertTrue(longest < 16100, "longest line $longest")
	}

	@Test
	fun emptyDirectoryGivesAnEmptyRegistry() {
		val src = EmbedRules.render(emptyMap())
		assertTrue(src.contains("mapOf<String, List<String>>(\n\n    )"))
	}

	@Test
	fun taskEmbedsYamlRulesAsJsonInTheKnowPackage() {
		val rules = Files.createTempDirectory("rules").toFile()
		rules.resolve("one.yml").writeText("schema: 1\nid: one\nnote: '1.20'\n")
		rules.resolve("notes.txt").writeText("ignored")
		val out = Files.createTempDirectory("out").toFile()
		val text = embed(rules, out)
		assertTrue(text.contains("\"one\" to"))
		assertFalse(text.contains("notes"))
		assertEquals(1, Regex("\" to listOf\\(").findAll(text).count())
		val b64 = Regex("\"([A-Za-z0-9+/=]{20,})\"").find(text)!!.groupValues[1]
		val json = String(Base64.getDecoder().decode(b64))
		assertEquals("{\n\t\"schema\": 1,\n\t\"id\": \"one\",\n\t\"note\": \"1.20\"\n}\n", json)
	}

	@Test
	fun strayJsonRuleFilesAreRejectedByName() {
		val rules = Files.createTempDirectory("rules").toFile()
		rules.resolve("one.yml").writeText("schema: 1\nid: one\n")
		rules.resolve("old.json").writeText("{}")
		val e =
			assertFailsWith<GradleException> { embed(rules, Files.createTempDirectory("out").toFile()) }
		assertTrue(e.message!!.contains("old.json"), e.message)
	}

	private fun embed(rules: java.io.File, out: java.io.File): String {
		val project = ProjectBuilder.builder().build()
		val task = project.tasks.register("embedRules", EmbedRules::class.java) {
			this.rules.set(rules)
			outputDir.set(out)
		}.get()
		task.embed()
		return out.resolve("dev/gmitch215/drift/know/Rules.kt").readText()
	}
}
