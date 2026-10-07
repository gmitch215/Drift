package dev.gmitch215.drift.build

import org.gradle.testfixtures.ProjectBuilder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbedFixturesTest {
	private fun embed(objectName: String?): String {
		val fixtures = Files.createTempDirectory("fixtures").toFile()
		fixtures.resolve("logs").mkdirs()
		fixtures.resolve("logs/a.log").writeText("one\n")
		val out = Files.createTempDirectory("out").toFile()
		val project = ProjectBuilder.builder().build()
		val task = project.tasks.register("embed", EmbedFixtures::class.java) {
			this.fixtures.set(fixtures)
			outputDir.set(out)
			if (objectName != null) this.objectName.set(objectName)
		}.get()
		task.embed()
		return out.resolve("dev/gmitch215/drift/fixtures/${task.objectName.get()}.kt").readText()
	}

	@Test
	fun objectNameDefaultsToFixtures() {
		val text = embed(null)
		assertTrue(text.contains("internal object Fixtures {"))
		assertTrue(text.contains("\"logs/a.log\" to"))
	}

	@Test
	fun objectNameNamesTheObjectAndTheFile() {
		val text = embed("RunFixtures")
		assertTrue(text.contains("internal object RunFixtures {"))
		assertEquals(1, Regex("internal object").findAll(text).count())
	}
}
