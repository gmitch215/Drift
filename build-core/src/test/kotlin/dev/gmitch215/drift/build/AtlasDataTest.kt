package dev.gmitch215.drift.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AtlasDataTest {
	private val atlas = File("../atlas")
	private val fixtures = File("../fixtures")

	private val probes: List<Map<*, *>> = run {
		val root = RuleYaml.load(atlas.resolve("classification.yml").readText(), "classification.yml")
		(((root as Map<*, *>)["probes"]) as List<*>).map { it as Map<*, *> }
	}

	@Test
	fun everyCommittedTranscriptHasAColumnDescription() {
		val described = SiteData.columns(atlas.resolve("columns.yml").readText(), "columns.yml")
			.map { it.target }
		assertEquals(described.size, described.toSet().size)
		val recorded = fixtures.resolve("atlas").walkTopDown()
			.filter { it.isFile && it.name.endsWith(".txt") }
			.map { Regex("^# kotlin.target = (.+)$", RegexOption.MULTILINE).find(it.readText())!! }
			.map { it.groupValues[1] }.toSet()
		assertTrue(described.containsAll(recorded), "no description for ${recorded - described.toSet()}")
		assertEquals(setOf("mingw", "jvm-windows"), described.toSet() - recorded)
	}

	@Test
	fun everyReproDraftIsLabelledAndLinkedFromItsEntry() {
		val linked = probes.filter { it["repro"] != null }
		val files = atlas.resolve("repros").listFiles { f -> f.name.endsWith(".md") }!!
		assertEquals(linked.size, files.size)
		for (p in linked) {
			assertEquals("unclassified", p["classification"])
			val file = atlas.resolve("repros/${p["id"]}.md")
			assertEquals("atlas/repros/${p["id"]}.md", p["repro"])
			assertTrue(
				file.readText().startsWith("Draft for human review. Not filed.\n"),
				"$file lacks the draft label",
			)
		}
	}

	@Test
	fun theExtraTranscriptsStayOutOfTheEmbeddedDirectory() {
		val extra = fixtures.resolve("atlas-extra")
		val names = extra.walkTopDown().filter { it.name.endsWith(".txt") }.map { it.name }.toSet()
		assertEquals(setOf("mingw-wine.txt", "linux-ubuntu.txt", "jvm-21-arm64.txt"), names)
		assertTrue(extra.resolve("README.md").isFile)
		val embedded = fixtures.resolve("atlas").walkTopDown().map { it.name }.toSet()
		assertTrue(names.none { it in embedded })
	}
}
