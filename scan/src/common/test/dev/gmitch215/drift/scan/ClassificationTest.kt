package dev.gmitch215.drift.scan

import dev.gmitch215.drift.fixtures.AtlasFixtures
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.model.ProbeStatus
import dev.gmitch215.drift.scan.atlas.AtlasException
import dev.gmitch215.drift.scan.atlas.BadClassification
import dev.gmitch215.drift.scan.atlas.ClassificationData
import dev.gmitch215.drift.scan.atlas.ClassificationEntry
import dev.gmitch215.drift.scan.atlas.Classifications
import dev.gmitch215.drift.scan.atlas.ColumnKey
import dev.gmitch215.drift.scan.atlas.Dataset
import dev.gmitch215.drift.scan.atlas.DatasetCell
import dev.gmitch215.drift.scan.atlas.LineNote
import dev.gmitch215.drift.scan.atlas.Oracle
import dev.gmitch215.drift.scan.atlas.OracleKind
import dev.gmitch215.drift.scan.atlas.ProbeEntry
import dev.gmitch215.drift.scan.atlas.Reference
import dev.gmitch215.drift.scan.atlas.Show
import dev.gmitch215.drift.scan.atlas.Transcripts
import dev.gmitch215.drift.scan.atlas.label
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
        assertEquals(24, unclassified.size)
        for (e in unclassified) {
            assertTrue(!e.searched.isNullOrBlank(), "${e.id} does not say what was searched")
            val divergent = full.probe(e.id)!!.divergent
            assertEquals(divergent, e.repro != null, "${e.id} repro link and divergence disagree")
            if (divergent) assertEquals("atlas/repros/${e.id}.md", e.repro)
        }
        assertEquals(24, unclassified.count { it.repro != null })
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
            assertTrue(
                read.containsAll(e.issues.filter { it.url == null }.map { it.id }),
                "${e.id} lists an unread issue",
            )
            for (i in e.issues) assertTrue(i.state.isNotBlank(), "${e.id} ${i.id} has no state")
        }
    }

    @Test
    fun issuesOutsideTheKotlinTrackerNameTheirPage() {
        val outside = data.entries.flatMap { e -> e.issues.map { e.id to it } }
            .filter { it.second.url != null }
        assertEquals(listOf("teavm#1249", "teavm#1249", "teavm#1263"), outside.map { it.second.id })
        for ((id, issue) in outside) {
            assertTrue(issue.url!!.startsWith("https://github.com/konsoletyper/teavm/issues/"), id)
            assertTrue(!issueId.matches(issue.id), id)
        }
        val inKotlin = data.entries.flatMap { it.issues }.filter { it.url == null }
        assertTrue(inKotlin.all { issueId.matches(it.id) })
    }

    @Test
    fun theDowngradedProbesAreUnclassifiedWithTheirReasons() {
        val down = data.entries.filter { it.downgradedFrom != null }
        val expected = mapOf(
            "kotlin.char.case-special" to Classification.DOCUMENTED,
            "kotlin.collections.iterator-modification" to Classification.PLATFORM_DEFINED,
            "kotlin.exceptions.tostring-cause" to Classification.DOCUMENTED,
            "kotlin.int.division-modulo" to Classification.DOCUMENTED,
            "kotlin.int.overflow" to Classification.DOCUMENTED,
            "kotlin.math.pow-special-cases" to Classification.DOCUMENTED,
            "kotlin.math.rounding" to Classification.DOCUMENTED,
            "kotlin.math.transcendental-bits" to Classification.PLATFORM_DEFINED,
            "kotlin.numbers.float-conversions" to Classification.DOCUMENTED,
            "kotlin.regex.groups-replace" to Classification.DOCUMENTED,
            "kotlin.string.compare-order" to Classification.DOCUMENTED,
            "kotlin.string.split-replace-lines" to Classification.DOCUMENTED,
            "kotlin.string.supplementary" to Classification.DOCUMENTED,
        )
        assertEquals(expected.keys.toList(), down.map { it.id })
        for (e in down) {
            assertEquals(expected.getValue(e.id), e.downgradedFrom)
            assertEquals(Classification.UNCLASSIFIED, e.classification)
            assertTrue(e.note.startsWith("Downgraded from ${e.downgradedFrom!!.label}: "), e.id)
        }
    }

    @Test
    fun countsMatchTheResearch() {
        assertEquals(
            Triple(10, 4, 24),
            Triple(
                count(Classification.DOCUMENTED),
                count(Classification.PLATFORM_DEFINED),
                count(Classification.UNCLASSIFIED),
            ),
        )
        val divergent = full.probes.filter { it.divergent }.map { it.id }.toSet()
        assertEquals(32, divergent.size)
        assertEquals(
            Triple(4, 4, 24),
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
                "android", "cloudflare-jvm", "cloudflare-wasm", "ios", "js-ir", "jvm", "jvm-17",
                "jvm-21", "jvm-25", "linux", "linux-arm64", "macos", "wasm", "wasm-chromium",
                "wasm-firefox", "wasm-webkit",
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
            assertEquals(p.references.map { it.decides }, other.references.map { it.decides })
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

    @Test
    fun decidesNamesRealLinesOfUnclassifiedProbesOnly() {
        val named = data.entries.flatMap { e -> e.references.map { e to it } }
            .filter { it.second.decides.isNotEmpty() }
        assertEquals(7, named.size)
        for ((e, reference) in named) {
            assertEquals(Classification.UNCLASSIFIED, e.classification, e.id)
            val keys = full.probe(e.id)!!.cells
                .flatMap { c -> c.lines.map { it.substringBefore(" = ") } }
            for (line in reference.decides) assertTrue(line in keys, "${e.id} has no line $line")
        }
    }

    @Test
    fun everyDivergentLineHasOneKindAndTheCountsAreMeasured() {
        val counts = full.versions.associateWith { v -> Oracle.counts(Oracle.lines(full, v)) }
        assertEquals(
            mapOf(
                OracleKind.DOCUMENTED_CONTRACT to 15,
                OracleKind.CROSS_VERSION_CHANGE to 31,
                OracleKind.CRASH to 4,
                OracleKind.NO_CORRECTNESS_CLAIM to 208,
            ),
            counts.getValue("2.4.20"),
        )
        assertEquals(
            mapOf(
                OracleKind.DOCUMENTED_CONTRACT to 4,
                OracleKind.CROSS_VERSION_CHANGE to 4,
                OracleKind.CRASH to 0,
                OracleKind.NO_CORRECTNESS_CLAIM to 86,
            ),
            counts.getValue("2.5.0-Beta1"),
        )
        for (v in full.versions) {
            val lines = Oracle.lines(full, v)
            val own = lines.filter { it.line != Oracle.WHOLE_PROBE }
            assertEquals(own.size, own.map { it.probe.id to it.line }.toSet().size, v)
            val divergent = full.probes.count { v in it.divergentVersions }
            assertEquals(divergent, lines.map { it.probe }.toSet().size)
        }
    }

    @Test
    fun theReportableLinesAreContractsWithQuotesAndFailedColumns() {
        val reportable = full.versions.associateWith { v ->
            Oracle.lines(full, v).filter { it.reportable }
        }
        for (line in reportable.values.flatten()) {
            val quote = line.contract
            if (line.kind == OracleKind.DOCUMENTED_CONTRACT) {
                assertTrue(quote!!.quote.isNotBlank() && quote.url.startsWith("https://"))
                assertTrue(line.line in quote.decides, line.line)
            } else {
                assertEquals(OracleKind.CRASH, line.kind)
                assertEquals(Oracle.WHOLE_PROBE, line.line)
                assertEquals(listOf("cloudflare-jvm"), line.results.flatMap { it.first })
            }
        }
        assertEquals(
            listOf(
                "kotlin.char.case-special:upper-greek-iota-sub",
                "kotlin.char.case-special:upper-iota-tonos",
                "kotlin.char.case-special:upper-j-caron",
                "kotlin.char.case-special:upper-ligature-fi",
                "kotlin.char.case-special:upper-n-apostrophe",
                "kotlin.char.case-special:upper-sharp-s",
                "kotlin.exceptions.messages:(whole probe)",
                "kotlin.exceptions.tostring-cause:(whole probe)",
                "kotlin.int.overflow:(whole probe)",
                "kotlin.math.pow-special-cases:1^nan",
                "kotlin.math.rounding:0.49999999999999994",
                "kotlin.math.rounding:4503599627370497",
                "kotlin.math.rounding:float-8388609",
                "kotlin.numbers.float-conversions:ulong-max-double",
                "kotlin.regex.anchors-lines:(whole probe)",
                "kotlin.string.compare-order:compare-ignore-case-sigma",
                "kotlin.string.compare-order:dotless-i-vs-I",
                "kotlin.string.compare-order:final-sigma",
                "kotlin.string.supplementary:deseret-lower",
            ),
            reportable.getValue("2.4.20").map { "${it.probe.id}:${it.line}" }.sorted(),
        )
        assertEquals(
            listOf(
                "kotlin.math.pow-special-cases:1^nan",
                "kotlin.math.rounding:0.49999999999999994",
                "kotlin.math.rounding:4503599627370497",
                "kotlin.math.rounding:float-8388609",
            ),
            reportable.getValue("2.5.0-Beta1").map { "${it.probe.id}:${it.line}" }.sorted(),
        )
    }

    @Test
    fun theNegatedNanLineIsNeverARegression() {
        for (v in full.versions) {
            val line = Oracle.lines(full.probe("kotlin.double.nan-bits")!!, v)
                .single { it.line == "neg-nan-raw" }
            assertEquals(OracleKind.NO_CORRECTNESS_CLAIM, line.kind, v)
            assertEquals(null, line.contract)
            assertTrue(!line.reportable)
            assertEquals(emptyList(), line.changed)
        }
    }

    @Test
    fun aDocumentedLimitThatFailsAColumnIsNotACrash() {
        val line = Oracle.lines(full.probe("kotlin.collections.tostring-forms")!!, "2.4.20")
            .single { it.line == Oracle.WHOLE_PROBE }
        assertEquals(OracleKind.NO_CORRECTNESS_CLAIM, line.kind)
        assertEquals(listOf(listOf("js-ir")), line.results.map { it.first })
        assertTrue(!line.reportable)
    }

    @Test
    fun noKindOrListingSaysRegression() {
        val text = Show.oracle(full) + OracleKind.entries.joinToString { it.label + it.meaning }
        assertTrue(!text.lowercase().contains("regress"), text)
        assertTrue(text.all { it.code in 0x20..0x7e || it == '\n' })
        assertEquals(ORACLE_SHA, Sha256.hex(Show.oracle(full)))
    }

    @Test
    fun oneProbeOfEachKind() {
        fun cell(target: String, version: String, vararg lines: String) =
            DatasetCell(ColumnKey(target, version), ProbeStatus.OK, lines.toList())

        fun probe(
            decides: List<String>,
            cells: List<DatasetCell>,
            lines: List<LineNote> = emptyList(),
        ) = ProbeEntry(
            "kotlin.synthetic.line",
            "question",
            "source",
            ClassificationEntry(
                "kotlin.synthetic.line", Classification.UNCLASSIFIED, "verified", listOf("g"),
                null,
                listOf(Reference("https://example.org/page", "the value is two", decides)),
                emptyList(), null, null, emptyMap(), lines, "note",
            ),
            cells,
        )
        val cells = listOf(
            cell("a", "2.0.0", "decided = 1", "moved = 1", "plain = 1"),
            cell("b", "2.0.0", "decided = 2", "moved = 1", "plain = 2"),
            cell("c", "2.0.0", "decided = 2", "moved = 3", "plain = 2"),
            cell("a", "2.1.0", "decided = 1", "moved = 2", "plain = 1"),
        )
        val kinds = Oracle.lines(probe(listOf("decided"), cells), "2.0.0")
            .associate { it.line to it.kind }
        assertEquals(
            mapOf(
                "decided" to OracleKind.DOCUMENTED_CONTRACT,
                "moved" to OracleKind.CROSS_VERSION_CHANGE,
                "plain" to OracleKind.NO_CORRECTNESS_CLAIM,
            ),
            kinds,
        )
        val without = Oracle.lines(probe(emptyList(), cells), "2.0.0")
            .associate { it.line to it.kind }
        assertEquals(OracleKind.NO_CORRECTNESS_CLAIM, without["decided"])
        val failed = cells + DatasetCell(
            ColumnKey("d", "2.0.0"),
            ProbeStatus.UNAVAILABLE,
            listOf("failed Boom: nope"),
        )
        val crash = Oracle.lines(probe(emptyList(), failed), "2.0.0").first()
        assertEquals(OracleKind.CRASH, crash.kind)
        assertEquals(Oracle.WHOLE_PROBE, crash.line)
        assertEquals(listOf(listOf("d") to "failed Boom: nope"), crash.results)
        val rest = Oracle.lines(probe(emptyList(), failed), "2.0.0").drop(1)
        assertEquals(setOf("decided", "moved", "plain"), rest.map { it.line }.toSet())
        val limit = listOf(LineNote("whole probe", "b", "d", null))
        val limited = Oracle.lines(probe(emptyList(), failed, limit), "2.0.0").first()
        assertEquals(OracleKind.NO_CORRECTNESS_CLAIM, limited.kind)
        assertEquals(emptyList(), Oracle.lines(probe(emptyList(), cells), "2.1.0"))
    }
}

private const val ORACLE_SHA = "422aa874eaf8a956196bfb95d1becbb7566e08ebc4094314be17690ca0e0f366"
