package dev.gmitch215.drift.build

import org.gradle.testfixtures.ProjectBuilder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class EmbedDriftVersionTest {
	@Test
	fun theVersionIsEmbeddedAsAConstant() {
		val out = Files.createTempDirectory("out").toFile()
		val project = ProjectBuilder.builder().build()
		val task = project.tasks.register("embed", EmbedDriftVersion::class.java) {
			version.set("1.0.0")
			outputDir.set(out)
		}.get()
		task.embed()
		assertEquals(
			"package dev.gmitch215.drift\n\nconst val DRIFT_VERSION = \"1.0.0\"\n",
			out.resolve("dev/gmitch215/drift/Version.kt").readText(),
		)
	}
}
