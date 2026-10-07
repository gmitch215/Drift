package dev.gmitch215.drift.build

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AtlasSiteTest {
	private class Inputs(val root: File) {
		val studio = root.resolve("studio").also {
			it.mkdirs()
			it.resolve("index.html").writeText("<html><head></head><body></body></html>\n")
			it.resolve("studio.js").writeBytes(byteArrayOf(0, 1, 2, -1))
		}
		val dataset = root.resolve("dataset.json").also { it.writeText(sampleJson()) }
		val classification = root.resolve("classification.yml").also {
			it.writeText("schema: 1\nmeasured:\n  date: '2026-01-02'\nprobes:\n  - id: a\n    note: n\n")
		}
		val columns = root.resolve("columns.yml").also {
			it.writeText("schema: 1\ncolumns:\n  - target: jvm\n    how: JVM\n")
		}
		val repros = root.resolve("repros").also {
			it.mkdirs()
			it.resolve("kotlin.b.split.md").writeText("Draft for human review. Not filed.\n\nx\n")
		}
		val transcripts = root.resolve("transcripts").also {
			it.resolve("next").mkdirs()
			it.resolve("jvm.txt").writeText("# kotlin.version = 1.0\n# kotlin.target = jvm\n@@ p\nx\n")
			it.resolve("next/wasm.txt").writeText("# kotlin.version = 2.0\n# kotlin.target = wasm\n")
		}
		val out = root.resolve("out")

		fun assemble() = AtlasSiteBuilder.assemble(
			out,
			studio,
			dataset,
			classification,
			columns,
			repros,
			transcripts,
		)
	}

	private fun inputs() = Inputs(Files.createTempDirectory("site").toFile())

	private fun tree(dir: File): Map<String, String> = dir.walkTopDown().filter { it.isFile }
		.associate {
			it.relativeTo(dir).invariantSeparatorsPath to
				MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b ->
					"%02x".format(b)
				}
		}

	@Test
	fun assemblesTheStudioDataPagesAndTranscripts() {
		val i = inputs()
		i.assemble()
		val names = tree(i.out).keys
		assertEquals(
			setOf(
				"index.html", "studio.js", "404.html", "probe/index.html", "probe/kotlin.a.same.html",
				"probe/kotlin.b.split.html", "data/dataset.json", "data/classification.json",
				"data/transcripts/jvm.txt", "data/transcripts/next/wasm.txt",
			),
			names,
		)
		assertEquals(i.dataset.readText(), i.out.resolve("data/dataset.json").readText())
		assertTrue(i.out.resolve("data/classification.json").readText().contains("\"date\""))
		assertEquals(
			i.transcripts.resolve("jvm.txt").readText(),
			i.out.resolve("data/transcripts/jvm.txt").readText(),
		)
		assertEquals(listOf<Byte>(0, 1, 2, -1), i.out.resolve("studio.js").readBytes().toList())
	}

	@Test
	fun theStudioIndexKeepsItsHeadAndGainsALinkForReadersWithoutScripts() {
		val i = inputs()
		i.assemble()
		val html = i.out.resolve("index.html").readText()
		assertTrue(html.startsWith("<html><head></head><body><noscript>"), html)
		assertTrue("<a href=\"probe/\">all probes</a>" in html)
		assertEquals(1, Regex("<body>").findAll(html).count())
	}

	@Test
	fun theSameInputsGiveTheSameBytesAndStaleFilesAreRemoved() {
		val i = inputs()
		i.out.resolve("old").mkdirs()
		i.out.resolve("old/stale.html").writeText("stale")
		i.assemble()
		val first = tree(i.out)
		assertTrue("old/stale.html" !in first)
		i.assemble()
		assertEquals(first, tree(i.out))
		val other = inputs()
		other.assemble()
		assertEquals(first, tree(other.out))
	}

	@Test
	fun aMissingReproOrBodyOrHeaderIsRefusedWithTheCause() {
		val i = inputs()
		i.repros.resolve("kotlin.b.split.md").delete()
		val missing = assertFailsWith<SiteDataException> { i.assemble() }
		assertTrue("kotlin.b.split" in missing.message!! && "missing" in missing.message!!)
		val j = inputs()
		j.studio.resolve("index.html").writeText("<body>x</body>")
		assertTrue("empty body" in assertFailsWith<SiteDataException> { j.assemble() }.message!!)
		val k = inputs()
		k.transcripts.resolve("jvm.txt").writeText("@@ p\nx\n")
		assertTrue("kotlin.version" in assertFailsWith<SiteDataException> { k.assemble() }.message!!)
		val m = inputs()
		m.classification.writeText("schema: 1\nprobes: []\n")
		assertTrue("measured.date" in assertFailsWith<SiteDataException> { m.assemble() }.message!!)
	}

	@Test
	fun theTaskWrapsDataProblemsInAGradleException() {
		val i = inputs()
		i.studio.resolve("index.html").writeText("nothing")
		val project = ProjectBuilder.builder().build()
		val task = project.tasks.register("atlasSite", AtlasSite::class.java) {
			dataset.set(i.dataset)
			classification.set(i.classification)
			columns.set(i.columns)
			repros.set(i.repros)
			transcripts.set(i.transcripts)
			studio.set(i.studio)
			outputDir.set(i.out)
		}.get()
		assertFailsWith<GradleException> { task.assemble() }
		i.studio.resolve("index.html").writeText("<body></body>")
		task.assemble()
		assertTrue(i.out.resolve("probe/index.html").isFile)
		i.classification.writeText("[")
		assertFailsWith<GradleException> { task.assemble() }
	}
}
