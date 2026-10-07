package dev.gmitch215.drift.scan

import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.fixtures.AtlasFixtures
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.scan.atlas.AtlasException
import dev.gmitch215.drift.scan.atlas.AtlasProblem
import dev.gmitch215.drift.scan.atlas.BadDataset
import dev.gmitch215.drift.scan.atlas.BadHeader
import dev.gmitch215.drift.scan.atlas.Column
import dev.gmitch215.drift.scan.atlas.Comparison
import dev.gmitch215.drift.scan.atlas.Dataset
import dev.gmitch215.drift.scan.atlas.DuplicateColumn
import dev.gmitch215.drift.scan.atlas.DuplicateProbe
import dev.gmitch215.drift.scan.atlas.MissingProbes
import dev.gmitch215.drift.scan.atlas.NoSuchProbe
import dev.gmitch215.drift.scan.atlas.NoTranscripts
import dev.gmitch215.drift.scan.atlas.Show
import dev.gmitch215.drift.scan.atlas.Transcripts
import dev.gmitch215.drift.scan.atlas.TruncatedTranscript
import dev.gmitch215.drift.scan.atlas.UnknownProbe
import dev.gmitch215.drift.scan.atlas.Verdict
import dev.gmitch215.drift.scan.probe.kotlin.AtlasTranscript
import dev.gmitch215.drift.scan.probe.kotlin.Classification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AtlasTest {
	private val jvmText = AtlasFixtures.text("jvm.txt")
	private val columns = listOf("jvm.txt", "macos.txt", "wasm.txt").map {
		Transcripts.read(it, AtlasFixtures.text(it))
	}
	private val dataset = Dataset.build(columns)

	private fun problem(block: () -> Unit): AtlasProblem {
		try {
			block()
		} catch (e: AtlasException) {
			return e.problem
		}
		throw AssertionError("expected an AtlasException")
	}

	private fun sha(text: String) = Sha256.hex(text)

	@Test
	fun datasetMatchesTheMeasuredFindings() {
		assertEquals(38, dataset.probes.size)
		assertEquals(
			listOf("jvm@2.4.20", "macos@2.4.20", "wasm@2.4.20"),
			dataset.columns.map {
			it.label
		},
		)
		assertEquals(23, dataset.divergentCount)
		val by = dataset.byClassification.mapValues { it.value.probes to it.value.divergent }
		assertEquals(
			mapOf(
				Classification.DOCUMENTED to (18 to 6),
				Classification.PLATFORM_DEFINED to (4 to 4),
				Classification.UNCLASSIFIED to (16 to 13),
			),
			by,
		)
		assertEquals(IDENTICAL, dataset.probes.filter { !it.divergent }.map { it.id }.sorted())
	}

	@Test
	fun groupsNameTheTargetsThatDiffer() {
		assertEquals(
			listOf(listOf("jvm"), listOf("macos", "wasm")),
			dataset.probe("kotlin.char.bmp-categories")!!.groups("2.4.20"),
		)
		assertEquals(
			listOf(listOf("jvm", "macos"), listOf("wasm")),
			dataset.probe("kotlin.double.extremes")!!.groups("2.4.20"),
		)
		assertEquals(
			listOf(listOf("jvm", "macos", "wasm")),
			dataset.probe("kotlin.int.overflow")!!.groups("2.4.20"),
		)
	}

	@Test
	fun issuesAreTheTrackerIdsRecordedWithTheClassification() {
		fun ids(id: String) = dataset.probe(id)!!.issues.map { it.id }
		assertEquals(listOf("KT-88414", "KT-88035", "KT-88036"), ids("kotlin.double.extremes"))
		assertEquals(listOf("KT-89072", "KT-88414"), ids("kotlin.math.pow-special-cases"))
		assertEquals(
			listOf("KT-88775", "KT-89031"),
			ids("kotlin.collections.iterator-modification"),
		)
		assertEquals(emptyList(), ids("kotlin.int.overflow"))
		assertEquals("Fixed", dataset.probe("kotlin.double.extremes")!!.issues.first().state)
	}

	@Test
	fun datasetBytesAreTheSameOnEveryTarget() {
		val text = dataset.encode()
		assertEquals(DATASET_SHA, sha(text))
		assertEquals(text, Dataset.parse(text).encode())
		assertFalse(text.contains('\n'))
	}

	@Test
	fun datasetKeepsTheLinesAndTheSourceOfEveryCell() {
		val probe = dataset.probe("kotlin.double.tostring-boundaries")!!
		val wasm = probe.cells.single { it.key.target == "wasm" }
		assertTrue(wasm.lines.any { it.endsWith("10000000.0") }, wasm.lines.toString())
		assertTrue(probe.source.contains("line("), probe.source)
		assertEquals(wasm.hash, Sha256.hex(wasm.lines.joinToString("\n") + "\n"))
	}

	@Test
	fun versionsNeverMerge() {
		val wasmNext = jvmText.replace("kotlin.version = 2.4.20", "kotlin.version = 2.5.0-Beta1")
			.replace("kotlin.target = jvm", "kotlin.target = wasm")
		val next = Transcripts.read("wasm-next.txt", wasmNext)
		val both = Dataset.build(columns + next)
		assertEquals(listOf("2.4.20", "2.5.0-Beta1"), both.versions)
		val probe = both.probe("kotlin.double.extremes")!!
		assertEquals(listOf("2.4.20"), probe.divergentVersions)
		assertEquals(listOf("wasm"), probe.changedAcrossVersions)
		assertEquals(listOf(listOf("wasm")), probe.groups("2.5.0-Beta1"))
		assertEquals(4, probe.cells.size)
		assertEquals(23, both.divergentIn("2.4.20"))
		assertEquals(0, both.divergentIn("2.5.0-Beta1"))
		assertEquals(23, both.divergentCount)
		assertTrue(
			Show.matrix(
				both,
			).contains("divergent probes per kotlin version:\n  2.4.20: 23\n  2.5.0-Beta1: 0"),
		)
	}

	@Test
	fun theSameTargetAndVersionTwiceIsRefused() {
		val again = Transcripts.read("again.txt", jvmText)
		val found = problem { Dataset.build(columns + again) }
		assertEquals(DuplicateColumn("jvm@2.4.20", "jvm.txt", "again.txt"), found)
		assertEquals(NoTranscripts, problem { Dataset.build(emptyList()) })
	}

	@Test
	fun versionsSortByNumberAndPreReleaseNotByText() {
		val texts = listOf("2.10.0", "2.9.0", "2.5.0-Beta1", "2.5.0").map { v ->
			Transcripts.read(v, jvmText.replace("kotlin.version = 2.4.20", "kotlin.version = $v"))
		}
		val ordered = Dataset.build(texts).versions
		assertEquals(listOf("2.5.0-Beta1", "2.5.0", "2.9.0", "2.10.0"), ordered)
	}

	@Test
	fun truncatedTranscriptsAreRefused() {
		assertIs<TruncatedTranscript>(problem { Transcripts.read("t", jvmText.dropLast(3)) })
		assertIs<TruncatedTranscript>(problem { Transcripts.read("t", "") })
		assertIs<TruncatedTranscript>(
			problem { Transcripts.read("t", "$HEAD@@ a.b\n") },
		)
		assertIs<TruncatedTranscript>(
			problem { Transcripts.read("t", "# kotlin.version = 1\n# kotlin.target = x\n") },
		)
		val firstTen = jvmText.lines().take(30).joinToString("\n") + "\n"
		val cut = Transcripts.read("short.txt", firstTen)
		val missing = problem { Dataset.build(listOf(cut)) }
		assertIs<MissingProbes>(missing)
		assertTrue(missing.ids.size > 30, missing.ids.size.toString())
	}

	@Test
	fun wrongHeadersAreRefused() {
		val body = jvmText.substringAfter("# kotlin.target = jvm\n")
		assertIs<BadHeader>(problem { Transcripts.read("t", body) })
		assertIs<BadHeader>(problem { Transcripts.read("t", "# kotlin.version = 1\n$body") })
		assertIs<BadHeader>(problem { Transcripts.read("t", "# kotlin.target = x\n$body") })
		assertIs<BadHeader>(
			problem {
				Transcripts.read("t", "$HEAD# kotlin.version = 2\n$body")
			},
		)
		assertIs<BadHeader>(
			problem {
				Transcripts.read("t", "$HEAD@@ a.b [GONE]\nx = 1\n")
			},
		)
		assertIs<BadHeader>(problem { Transcripts.read("t", "hello\n" + jvmText) })
	}

	@Test
	fun duplicateAndUnknownProbesAreRefused() {
		val twice = jvmText + "@@ kotlin.int.overflow\nx = 1\n"
		val found = problem { Transcripts.read("t", twice) }
		assertEquals(DuplicateProbe("t", "kotlin.int.overflow"), found)
		val extra = Transcripts.read("extra.txt", jvmText + "@@ kotlin.nope.nothing\nx = 1\n")
		assertEquals(
			UnknownProbe("extra.txt", listOf("kotlin.nope.nothing")),
			problem {
			Dataset.build(listOf(extra))
		},
		)
	}

	@Test
	fun malformedDatasetsAreRefused() {
		val text = dataset.encode()
		assertIs<BadDataset>(problem { Dataset.parse("{not json") })
		assertIs<BadDataset>(problem { Dataset.parse("[]") })
		val newer = text.replace("\"schema\":2", "\"schema\":3")
		assertIs<BadDataset>(problem { Dataset.parse(newer) })
		val famous = text.replace("\"documented\"", "\"famous\"")
		assertIs<BadDataset>(problem { Dataset.parse(famous) })
		val hash = Regex("\"hash\":\"([0-9a-f]{64})\"").find(text)!!.groupValues[1]
		assertIs<BadDataset>(problem { Dataset.parse(text.replaceFirst(hash, "0".repeat(64))) })
		val last = text.lastIndexOf("\"target\":\"wasm\"")
		val short = text.substring(0, last) + "\"target\":\"wasm2\"" + text.substring(last + 15)
		assertIs<BadDataset>(problem { Dataset.parse(short) })
	}

	private fun beta() = listOf("jvm", "macos", "wasm").map {
		Transcripts.read("beta-$it", AtlasFixtures.text("2.5.0-beta1/$it.txt"))
	}

	@Test
	fun twoMeasuredVersionsShowTheWasmNumberFormattingRowsConverge() {
		val both = Dataset.build(columns + beta())
		assertEquals(listOf("2.4.20", "2.5.0-Beta1"), both.versions)
		assertEquals(6, both.columns.size)
		assertEquals(23, both.divergentIn("2.4.20"))
		assertEquals(17, both.divergentIn("2.5.0-Beta1"))
		assertEquals(BOTH_SHA, sha(both.encode()))
		assertEquals(BOTH_SHA, sha(Dataset.build(beta() + columns.reversed()).encode()))
		val converged = both.probes.filter { "2.4.20" in it.divergentVersions }
			.filter { "2.5.0-Beta1" !in it.divergentVersions }.map { it.id }
		assertEquals(
			listOf(
				"kotlin.double.extremes",
				"kotlin.double.parse",
				"kotlin.double.shortest-digits",
				"kotlin.double.tostring-boundaries",
				"kotlin.float.tostring",
				"kotlin.numbers.float-conversions",
			),
			converged,
		)
		assertEquals(
			listOf("macos", "wasm"),
			both.probe("kotlin.numbers.float-conversions")!!.changedAcrossVersions,
		)
	}

	private fun macos() = Transcripts.read("macos.txt", AtlasFixtures.text("macos.txt"))

	@Test
	fun compareCountsMatchAnIndependentReadOfTheFixtures() {
		val result = Comparison(dataset, macos())
		val mine = AtlasTranscript.parse(AtlasFixtures.text("macos.txt")).probes
		for (name in listOf("jvm", "macos", "wasm")) {
			val theirs = AtlasTranscript.parse(AtlasFixtures.text("$name.txt")).probes
			val expected = mine.count { (id, text) -> theirs[id] == text }
			val key = dataset.columns.single { it.target == name }
			assertEquals(expected, result.rows.count { key in it.matches }, name)
		}
		assertEquals(15, result.count(Verdict.SAME))
		assertEquals(23, result.count(Verdict.MIXED))
		assertEquals(0, result.count(Verdict.DIFFERENT))
	}

	@Test
	fun compareSummaryIsExact() {
		assertEquals(
			"""
			this device: macos, kotlin 2.4.20, 38 probes measured
			dataset: 38 probes, 3 columns: jvm@2.4.20, macos@2.4.20, wasm@2.4.20
			matches every column: 15
			matches some columns: 23
			matches no column: 0
			not measured here: 0
			per column:
			  jvm@2.4.20: 21 of 38 probes match
			  macos@2.4.20: 38 of 38 probes match
			  wasm@2.4.20: 25 of 38 probes match

			""".trimIndent(),
			Comparison(dataset, macos()).render(Detail.SUMMARY),
		)
	}

	@Test
	fun compareDetailAndFullAreGolden() {
		val result = Comparison(dataset, macos())
		assertEquals(COMPARE_DETAIL_SHA, sha(result.render(Detail.DETAIL)))
		assertEquals(COMPARE_FULL_SHA, sha(result.render(Detail.FULL)))
		assertTrue(result.render(Detail.FULL).startsWith(result.render(Detail.SUMMARY)))
		assertTrue(result.render(Detail.DETAIL).contains("  differs: jvm@2.4.20, wasm@2.4.20\n"))
	}

	@Test
	fun compareReportsDifferencesGapsAndNewerVersions() {
		val text = jvmText.replace("kotlin.version = 2.4.20", "kotlin.version = 2.5.0")
			.replace("@@ kotlin.int.overflow\n", "@@ kotlin.int.overflow\nextra = 1\n")
			.substringBefore("@@ kotlin.time.duration-format") + "@@ kotlin.nope.nothing\nx = 1\n"
		val result = Comparison(dataset, Transcripts.read("device.txt", text))
		val row = result.rows.single { it.probe.id == "kotlin.int.overflow" }
		assertEquals(Verdict.DIFFERENT, row.verdict)
		assertEquals(
			Verdict.NOT_MEASURED,
			result.rows.single { it.probe.id == "kotlin.time.duration-format" }.verdict,
		)
		assertEquals(listOf("kotlin.nope.nothing"), result.notInDataset)
		val full = result.render(Detail.FULL)
		assertTrue(full.contains("note: no dataset column was measured on kotlin 2.5.0\n"), full)
		assertTrue(full.contains("not measured here: 1\n"), full)
		assertTrue(full.contains("not in the dataset: kotlin.nope.nothing\n"), full)
		assertTrue(full.contains("kotlin.int.overflow [documented] matches no column\n"), full)
		assertTrue(full.contains("  line 1 (this device): extra = 1\n"), full)
	}

	@Test
	fun matrixMarksEveryRowAndCountsDivergence() {
		val text = Show.matrix(dataset)
		assertEquals(MATRIX_SHA, sha(text))
		val row = text.lines().single { it.startsWith("kotlin.double.extremes ") }
		val cells = row.split(Regex(" +"))
		assertEquals(listOf("kotlin.double.extremes", "unclassified", "A", "A", "B"), cells)
		assertTrue(text.endsWith("  platform-defined: 4 of 4\n  unclassified: 13 of 16\n"), text)
		assertTrue(text.contains("divergent probes: 23 of 38\n  documented: 6 of 18\n"), text)
	}

	@Test
	fun reproDraftsAreGolden() {
		val wasm = Show.repro(dataset, "kotlin.double.tostring-boundaries")
		val iterator = Show.repro(dataset, "kotlin.collections.iterator-modification")
		assertEquals(REPRO_FLOAT_SHA, sha(wasm))
		assertEquals(REPRO_ITERATOR_SHA, sha(iterator))
		for (text in listOf(wasm, iterator)) {
			assertTrue(text.startsWith("${Show.NOTICE}\n"), text)
			assertTrue(text.contains("Drift does not file"), text)
			assertTrue(text.contains("contact anyone."), text)
			assertTrue(text.contains("write the report yourself"), text)
			assertTrue(text.all { it.code in 0x20..0x7e || it == '\n' }, text)
		}
		assertTrue(wasm.contains("linked issues: KT-88414 "), wasm)
		assertTrue(wasm.contains("    wasm: 1e7 = 10000000.0\n"), wasm)
		assertTrue(wasm.contains("controls: 4 lines are the same on every target\n"), wasm)
		assertTrue(iterator.contains("kotlin 2.4.20: 2 different results\n"), iterator)
		assertTrue(
			iterator.contains("    jvm: list-remove-last = err:ConcurrentModificationException\n"),
			iterator,
		)
	}

	@Test
	fun reproOfAnIdenticalProbeSaysThereIsNothingToReport() {
		val text = Show.repro(dataset, "kotlin.int.overflow")
		assertTrue(text.contains("nothing to report"), text)
		assertTrue(text.contains("kotlin 2.4.20: identical on jvm, macos, wasm\n"), text)
		assertEquals(NoSuchProbe("kotlin.nope"), problem { Show.repro(dataset, "kotlin.nope") })
	}
}

private const val BOTH_SHA =
	"b5fd38d52f61c39646117598edfc6b21034d11c437014f7e9496e8b7045fee28"

private const val HEAD_TARGET = "# kotlin.target = x\n"

private const val HEAD = "# kotlin.version = 1\n$HEAD_TARGET"

private val IDENTICAL = listOf(
	"kotlin.char.case-special", "kotlin.char.whitespace-set", "kotlin.collections.equality-hashing",
	"kotlin.double.equality-order", "kotlin.int.division-modulo", "kotlin.int.overflow",
	"kotlin.int.radix", "kotlin.long.bit-ops", "kotlin.numbers.type-checks", "kotlin.random.seeded",
	"kotlin.regex.anchors-lines", "kotlin.regex.groups-replace", "kotlin.string.hashcode",
	"kotlin.string.padding", "kotlin.time.duration-format",
)

private const val DATASET_SHA =
	"3cf771d67a004c85570eeb323050261a6a2a1d8f74fac71f04551760fb8f0470"

private const val COMPARE_DETAIL_SHA =
	"76582ef23235a1b42ac78b33fb69551f6122f9a9c57ca3b3a41024b599e42d25"

private const val COMPARE_FULL_SHA =
	"49c0992b73650aede50b1a0475d8d08ac80c4f925c2cbe58a5c34fa56b5c904b"

private const val MATRIX_SHA =
	"fb8daad72d22edf1c95ad4e3b721f6afc49c9f3421f69cf0580a984adba87327"

private const val REPRO_FLOAT_SHA =
	"55ab28b9888d81cfd7ae7d75d5be0727d31b07f02fca1ceb6217fbe2691f049f"

private const val REPRO_ITERATOR_SHA =
	"80e61044d0fe28c23356060594cac2f6091ac1b713a6d51e88f34db4ff16f581"
