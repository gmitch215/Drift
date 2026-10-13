package dev.gmitch215.drift.scan

import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.fixtures.AtlasFixtures
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.model.ProbeStatus
import dev.gmitch215.drift.scan.atlas.AtlasException
import dev.gmitch215.drift.scan.atlas.AtlasProblem
import dev.gmitch215.drift.scan.atlas.BadDataset
import dev.gmitch215.drift.scan.atlas.BadDiff
import dev.gmitch215.drift.scan.atlas.BadHeader
import dev.gmitch215.drift.scan.atlas.Cell
import dev.gmitch215.drift.scan.atlas.ChangeKind
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
import dev.gmitch215.drift.scan.atlas.VersionDiff
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
                Classification.DOCUMENTED to (10 to 3),
                Classification.PLATFORM_DEFINED to (3 to 3),
                Classification.UNCLASSIFIED to (25 to 17),
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

    @Test
    fun versionDiffMatchesAnIndependentReadOfTheFixtures() {
        val both = Dataset.build(columns + beta())
        val diff = VersionDiff(both, "2.4.20", "2.5.0-beta1")
        assertEquals("2.5.0-Beta1", diff.to)
        assertEquals(listOf("jvm", "macos", "wasm"), diff.targets)
        for (target in diff.targets) {
            val old = AtlasTranscript.parse(AtlasFixtures.text("$target.txt")).probes
            val new = AtlasTranscript.parse(AtlasFixtures.text("2.5.0-beta1/$target.txt")).probes
            val expected = old.filter { (id, text) -> text != new.getValue(id) }.keys.sorted()
            assertEquals(
                expected,
                diff.changes.filter { it.target == target }.map { it.probe.id }.sorted(),
                target,
            )
        }
        assertEquals(10, diff.changes.count { it.target == "wasm" })
        assertEquals(1, diff.changes.count { it.target == "macos" })
        assertEquals(0, diff.changes.count { it.target == "jvm" })
        assertEquals(10, diff.changedProbes.size)
        assertTrue(diff.changes.all { it.kind == ChangeKind.CHANGED })
        assertEquals(VERSION_DIFF_FULL_SHA, sha(diff.render(Detail.FULL)))
        val summary = diff.render(Detail.SUMMARY)
        assertTrue(summary.startsWith("kotlin 2.4.20 to 2.5.0-Beta1: 10 of 38 probes change\n"))
        assertTrue(
            "  wasm: 10 of 38 change (0 became available, 0 became unavailable)\n" in summary,
        )
        assertFalse("kotlin.double.extremes" in summary)
        val detail = diff.render(Detail.DETAIL)
        assertTrue("kotlin.double.extremes [" in detail)
        assertTrue(
            "    line 13 (2.4.20): ulong-max-double = 1.844674407370955E19\n" +
                "    line 13 (2.5.0-Beta1): ulong-max-double = 1.8446744073709552E19\n" in detail,
            detail,
        )
    }

    @Test
    fun versionDiffNarrowsToOneTargetAndNamesTargetsMeasuredOnOneVersion() {
        val partial = Dataset.build(columns + beta().filter { it.target != "jvm" })
        val wasm = VersionDiff(partial, "2.4.20", "2.5.0-Beta1", "wasm")
        assertEquals(listOf("wasm"), wasm.targets)
        assertEquals(listOf("jvm"), wasm.onlyFrom)
        assertEquals(emptyList(), wasm.onlyTo)
        assertTrue("measured on 2.4.20 only: jvm\n" in wasm.render(Detail.SUMMARY))
        val back = VersionDiff(partial, "2.5.0-Beta1", "2.4.20")
        assertEquals(listOf("jvm"), back.onlyTo)
        assertEquals(10, back.changes.count { it.target == "wasm" })
    }

    @Test
    fun versionDiffNamesProbesThatBecomeUnavailableOrAvailable() {
        val jvm = columns[0]
        val id = jvm.cells.keys.first()
        val broken = Column(
            "next.txt",
            jvm.target,
            "9.9",
            jvm.cells + (id to Cell(ProbeStatus.UNAVAILABLE, "does not compile: x\n")),
        )
        val data = Dataset.build(listOf(jvm, broken))
        val lost = VersionDiff(data, "2.4.20", "9.9")
        assertEquals(listOf(ChangeKind.REMOVED), lost.changes.map { it.kind })
        val text = lost.render(Detail.DETAIL)
        assertTrue("$id [" in text, text)
        assertTrue("  jvm: became unavailable\n    9.9: does not compile: x\n" in text, text)
        val gained = VersionDiff(data, "9.9", "2.4.20")
        assertEquals(listOf(ChangeKind.APPEARED), gained.changes.map { it.kind })
        val back = gained.render(Detail.FULL)
        assertTrue("  jvm: became available\n    9.9: does not compile: x\n" in back, back)
    }

    @Test
    fun versionDiffRefusesWhatItCannotCompare() {
        val partial = Dataset.build(columns + beta().filter { it.target != "jvm" })
        assertIs<BadDiff>(problem { VersionDiff(partial, "2.4.20", "2.4.20") })
        assertIs<BadDiff>(problem { VersionDiff(partial, "2.4.20", "9.9.9") })
        val missing = problem { VersionDiff(partial, "2.4.20", "2.5.0-Beta1", "jvm") }
        assertEquals(
            "cannot diff versions: no column jvm on both 2.4.20 and 2.5.0-Beta1; " +
                "both have: macos, wasm",
            missing.message(),
        )
        val oneEach = Dataset.build(
            listOf(columns[0], beta()[1]),
        )
        assertEquals(
            "cannot diff versions: no target is measured on both 2.4.20 and 2.5.0-Beta1",
            problem { VersionDiff(oneEach, "2.4.20", "2.5.0-Beta1") }.message(),
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
        assertTrue(full.contains("kotlin.int.overflow [unclassified] matches no column\n"), full)
        assertTrue(full.contains("  line 1 (this device): extra = 1\n"), full)
    }

    @Test
    fun matrixMarksEveryRowAndCountsDivergence() {
        val text = Show.matrix(dataset)
        assertEquals(MATRIX_SHA, sha(text))
        val row = text.lines().single { it.startsWith("kotlin.double.extremes ") }
        val cells = row.split(Regex(" +"))
        assertEquals(listOf("kotlin.double.extremes", "unclassified", "A", "A", "B"), cells)
        assertTrue(text.endsWith("  platform-defined: 3 of 3\n  unclassified: 17 of 25\n"), text)
        assertTrue(text.contains("divergent probes: 23 of 38\n  documented: 3 of 10\n"), text)
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
    "1e5349013ab35e40b93d2f57a48141e29f10d9191d07e412a19d76c3760e7717"

private const val HEAD_TARGET = "# kotlin.target = x\n"

private const val HEAD = "# kotlin.version = 1\n$HEAD_TARGET"

private val IDENTICAL = listOf(
    "kotlin.char.case-special", "kotlin.char.whitespace-set", "kotlin.collections.equality-hashing",
    "kotlin.double.equality-order", "kotlin.int.division-modulo", "kotlin.int.overflow",
    "kotlin.int.radix", "kotlin.long.bit-ops", "kotlin.numbers.type-checks", "kotlin.random.seeded",
    "kotlin.regex.anchors-lines", "kotlin.regex.groups-replace", "kotlin.string.hashcode",
    "kotlin.string.padding", "kotlin.time.duration-format",
)

private const val VERSION_DIFF_FULL_SHA =
    "f4686e67cfa432962ee7623e446c06d37ab8ecdfb7008540a0796c571c7f93ad"

private const val DATASET_SHA =
    "18e154bf26aba9464760e3e5ed1b8e02307e33f1c07fe3565bc20906c0c3d3e7"

private const val COMPARE_DETAIL_SHA =
    "d69a90a70b10284578613e343d6332115df8a62a574ce55668fe99e62ebce134"

private const val COMPARE_FULL_SHA =
    "2bde5e9bba9381085075c70dbc6f7d8d2ff1c9e9f0af0a40c4c2d3f451647827"

private const val MATRIX_SHA =
    "e2bd6866ff8dbc0737c7e9eea91a702b14536c9653c0686dbf585c10c3c84182"

private const val REPRO_FLOAT_SHA =
    "b714f4685788219346f795ad2a459577efb8cfbad808554c324fe8cc5a42be46"

private const val REPRO_ITERATOR_SHA =
    "1b6918271c0d551dbcae3f42bc298749b07c0c08494c09317c6fd1e6fa74f38b"
