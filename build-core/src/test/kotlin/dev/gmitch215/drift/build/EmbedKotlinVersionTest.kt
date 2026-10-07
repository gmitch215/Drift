package dev.gmitch215.drift.build

import org.gradle.testfixtures.ProjectBuilder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class EmbedKotlinVersionTest {
	@Test
	fun theFullVersionIncludingAQualifierIsEmbedded() {
		for (version in listOf("2.4.20", "2.5.0-Beta1")) {
			val out = Files.createTempDirectory("out").toFile()
			val project = ProjectBuilder.builder().build()
			val task = project.tasks.register("embed", EmbedKotlinVersion::class.java) {
				this.version.set(version)
				outputDir.set(out)
			}.get()
			task.embed()
			val text = out.resolve("dev/gmitch215/drift/scan/KotlinBuild.kt").readText()
			assertEquals(
				"package dev.gmitch215.drift.scan\n\nobject KotlinBuild {\n" +
					"    const val VERSION = \"$version\"\n}\n",
				text,
			)
		}
	}
}
