package dev.gmitch215.drift.scan

import dev.gmitch215.drift.fixtures.AtlasFixtures
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.scan.atlas.AtlasException
import dev.gmitch215.drift.scan.atlas.BadClassification
import dev.gmitch215.drift.scan.atlas.ClassificationData
import dev.gmitch215.drift.scan.atlas.Classifications
import dev.gmitch215.drift.scan.atlas.Dataset
import dev.gmitch215.drift.scan.atlas.Transcripts
import dev.gmitch215.drift.scan.probe.kotlin.Classification
import dev.gmitch215.drift.scan.probe.kotlin.KotlinCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClassificationTest {
	private val data = Classifications.embedded

	private val full = Dataset.build(
		AtlasFixtures.names.filter { it.endsWith(".txt") }.sorted().map {
			Transcripts.read(it, AtlasFixtures.text(it))
		},
	)

	private val issueId = Regex("KT-\\d{1,9}")

	private fun problem(block: () -> Unit): Any {
		try {
			block()
		} catch (e: AtlasException) {
			return e.problem
		}
		throw AssertionError("expected an AtlasException")
	}

	private fun count(c: Classification, only: (String) -> Boolean = { true }) =
		data.entries.count { it.classification == c && only(it.id) }

	@Test
	fun everyProbeHasOneEntryAndNoEntryIsLeftOver() {
		val probes = KotlinCatalog.all.map { it.id }
		assertEquals(probes.sorted(), data.entries.map { it.id }.sorted())
		assertEquals(38, data.entries.size)
		assertEquals(data.entries.size, data.entries.map { it.id }.toSet().size)
	}

	@Test
	fun explainedEntriesQuoteTheirSources() {
		for (e in data.entries.filter { it.classification != Classification.UNCLASSIFIED }) {
			assertTrue(e.references.isNotEmpty(), "${e.id} has no reference")
			for (r in e.references) {
				assertTrue(r.url.startsWith("https://"), "${e.id} ${r.url}")
				assertTrue(r.quote.isNotBlank(), "${e.id} ${r.url} has no quote")
				assertTrue(r.quote.split(' ').size <= 25, "${e.id} quote over 25 words")
			}
			assertTrue(e.basis in setOf("verified", "inferred"), "${e.id} basis ${e.basis}")
			assertTrue(e.causes.isNotEmpty(), "${e.id} names no cause")
		}
	}

	@Test
	fun unclassifiedEntriesNameWhatWasSearchedAndLinkTheirDraft() {
		val unclassified = data.entries.filter { it.classification == Classification.UNCLASSIFIED }
		assertEquals(16, unclassified.size)
		for (e in unclassified) {
			assertTrue(!e.searched.isNullOrBlank(), "${e.id} does not say what was searched")
			val divergent = full.probe(e.id)!!.divergent
			assertEquals(divergent, e.repro != null, "${e.id} repro link and divergence disagree")
			if (divergent) assertEquals("atlas/repros/${e.id}.md", e.repro)
		}
		assertEquals(13, unclassified.count { it.repro != null })
	}

	@Test
	fun noEntryNamesAnIssueTheResearchDidNotRead() {
		val read = data.issuesRead.toSet()
		assertEquals(data.issuesRead.size, read.size)
		for (e in data.entries) {
			val text = buildList {
				add(e.note)
				e.searched?.let { add(it) }
				e.references.forEach { add(it.url + " " + it.quote) }
				e.lines.forEach { add(it.columns + " " + it.issue.orEmpty()) }
				e.issues.forEach { add(it.id + " " + it.summary) }
			}.joinToString(" ")
			val mentioned = issueId.findAll(text).map { it.value }.toSet()
			assertTrue(read.containsAll(mentioned), "${e.id}: ${mentioned - read}")
			assertTrue(read.containsAll(e.issues.map { it.id }), "${e.id} lists an unread issue")
			for (i in e.issues) assertTrue(i.state.isNotBlank(), "${e.id} ${i.id} has no state")
		}
	}

	@Test
	fun theFourDowngradedProbesAreUnclassifiedWithTheirReasons() {
		val down = data.entries.filter { it.downgradedFrom != null }
		assertEquals(
			listOf(
				"kotlin.math.pow-special-cases",
				"kotlin.math.rounding",
				"kotlin.numbers.float-conversions",
				"kotlin.string.split-replace-lines",
			),
			down.map { it.id },
		)
		for (e in down) {
			assertEquals(Classification.DOCUMENTED, e.downgradedFrom)
			assertEquals(Classification.UNCLASSIFIED, e.classification)
			assertTrue(e.note.startsWith("Downgraded from documented: "), e.id)
		}
	}

	@Test
	fun countsMatchTheResearch() {
		assertEquals(
			Triple(18, 4, 16),
			Triple(
				count(Classification.DOCUMENTED),
				count(Classification.PLATFORM_DEFINED),
				count(Classification.UNCLASSIFIED),
			),
		)
		val divergent = full.probes.filter { it.divergent }.map { it.id }.toSet()
		assertEquals(23, divergent.size)
		assertEquals(
			Triple(6, 4, 13),
			Triple(
				count(Classification.DOCUMENTED) { it in divergent },
				count(Classification.PLATFORM_DEFINED) { it in divergent },
				count(Classification.UNCLASSIFIED) { it in divergent },
			),
		)
	}

	@Test
	fun recordedColumnGroupsAgreeWithTheMeasuredFixtures() {
		for (p in full.probes) {
			assertEquals(
				p.divergentVersions.toSet(),
				p.info.columns.keys,
				"${p.id} lists groups for the wrong versions",
			)
			for ((version, groups) in p.info.columns) {
				assertEquals(
					p.groups(version).map { it.toSet() }.toSet(),
					groups.map { it.toSet() }.toSet(),
					"${p.id} at $version",
				)
			}
		}
	}

	@Test
	fun theExtraTranscriptsAreNotEmbedded() {
		val names = AtlasFixtures.names.map { it.substringAfterLast('/') }
		for (extra in listOf("mingw-wine.txt", "linux-ubuntu.txt", "jvm-21-arm64.txt")) {
			assertTrue(extra !in names, extra)
		}
		assertEquals(
			listOf(
				"android", "ios", "jvm", "jvm-17", "jvm-21", "jvm-25", "linux", "linux-arm64",
				"macos", "wasm", "wasm-chromium", "wasm-firefox", "wasm-webkit",
			),
			full.columns.filter { it.version == "2.4.20" }.map { it.target },
		)
		assertEquals(
			listOf("android", "ios", "jvm", "macos", "wasm"),
			full.columns.filter { it.version == "2.5.0-Beta1" }.map { it.target },
		)
	}

	@Test
	fun theDatasetCarriesTheSameEntries() {
		val back = Dataset.parse(full.encode())
		for (p in full.probes) {
			val other = back.probe(p.id)!!
			assertEquals(p.classification, other.classification)
			assertEquals(p.references.map { it.quote }, other.references.map { it.quote })
			assertEquals(p.issues.map { it.id + it.state }, other.issues.map { it.id + it.state })
			assertEquals(p.info.searched, other.info.searched)
			assertEquals(p.info.repro, other.info.repro)
			assertEquals(p.note, other.note)
		}
	}

	@Test
	fun datasetRefusesAProbeWithoutAnEntry() {
		val partial = ClassificationData(data.issuesRead, data.entries.drop(1))
		val columns = listOf(Transcripts.read("jvm.txt", AtlasFixtures.text("jvm.txt")))
		val found = problem { Dataset.build(columns, classifications = partial) }
		assertIs<BadClassification>(found)
		assertTrue(found.message().contains(data.entries.first().id), found.message())
	}

	@Test
	fun malformedClassificationDataIsRefused() {
		val ok = """{"schema":1,"measured":{"issuesRead":[]},"probes":[]}"""
		assertEquals(0, Classifications.parse(ok).entries.size)
		for (bad in listOf(
			"not json",
			ok.replace("\"schema\":1", "\"schema\":2"),
			ok.replace("\"probes\":[]", "\"probes\":[{\"id\":\"a\"}]"),
			ok.replace(
				"\"probes\":[]",
				"\"probes\":[{\"id\":\"a\",\"classification\":\"sure\",\"basis\":\"x\"," +
					"\"causes\":[],\"note\":\"n\"}]",
			),
		)) {
			assertIs<BadClassification>(problem { Classifications.parse(bad) }, bad)
		}
	}

	@Test
	fun anEntryRoundTripsThroughJson() {
		for (e in data.entries) {
			val text = CanonicalJson.encode(Classifications.toJson(e))
			val back = Classifications.fromJson(CanonicalJson.parse(text) as JsonObject)
			assertEquals(text, CanonicalJson.encode(Classifications.toJson(back)))
		}
		assertNotNull(data["kotlin.double.nan-bits"])
		assertEquals(null, data["kotlin.nope"])
		assertFailsWith<AtlasException> {
			Classifications.fromJson(JsonObject(emptyMap()))
		}
	}
}
