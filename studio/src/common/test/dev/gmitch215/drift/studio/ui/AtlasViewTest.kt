package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.fixtures.AtlasFixtures
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.scan.atlas.Atlas
import dev.gmitch215.drift.scan.atlas.ColumnKey
import dev.gmitch215.drift.scan.atlas.Dataset
import dev.gmitch215.drift.scan.atlas.Transcripts
import dev.gmitch215.drift.scan.probe.ProbeRunner
import dev.gmitch215.drift.scan.probe.kotlin.KotlinCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val FULL_MACOS = "4b67160836ebbd089370d7ee1ea9c408cb33f70138fed1d7437a11663a6f88a0"

private const val DETAIL_BETA = "b0ddddade8b62da7d7c6cf1f283544f482a98678ab8061430938c7c7530aecda"

private val TARGETS_2_4_20 = listOf(
    "jvm", "jvm-17", "jvm-21", "jvm-25", "linux", "linux-arm64", "macos",
    "wasm", "wasm-chromium", "wasm-firefox", "wasm-webkit", "js-ir", "android", "ios",
    "cloudflare-jvm", "cloudflare-wasm",
)

class AtlasViewTest {
    private val dataset = AtlasData.dataset
    private val macos = Devices.of("this JVM", AtlasFixtures.text("macos.txt"), dataset)
    private val boundaries = "kotlin.double.tostring-boundaries"

    private fun view(
        version: String = "2.4.20",
        level: DetailLevel = DetailLevel.SUMMARY,
        device: Device? = null,
        divergentOnly: Boolean = false,
    ) = AtlasView.of(AtlasState(dataset, version, divergentOnly, level, device))

    private fun RowView.cell(target: String) = cells.first { it.target == target }

    @Test
    fun versionsNeverMerge() {
        assertEquals(listOf("2.4.20", "2.5.0-Beta1"), dataset.versions)
        val old = view("2.4.20")
        val new = view("2.5.0-Beta1")
        assertEquals(TARGETS_2_4_20, old.targets)
        assertEquals(listOf("jvm", "macos", "wasm", "android", "ios"), new.targets)
        assertEquals(38, old.total)
        assertEquals(32, old.divergent)
        assertEquals(18, new.divergent)
        val a = old.rows.first { it.id == boundaries }
        val b = new.rows.first { it.id == boundaries }
        assertNotEquals(a.cell("wasm").lines, b.cell("wasm").lines)
        assertEquals(a.cell("jvm").lines, b.cell("jvm").lines)
        assertTrue(a.divergent)
        assertTrue(!b.divergent)
    }

    @Test
    fun mobileTranscriptsAreRecordedOnTheStableVersion() {
        for (target in listOf("android", "ios")) {
            val text = AtlasFixtures.text("$target.txt")
            assertTrue(text.startsWith("# kotlin.version = 2.4.20\n# kotlin.target = $target\n"))
            val beta = AtlasFixtures.text("2.5.0-beta1/$target.txt")
            val header = "# kotlin.version = 2.5.0-Beta1\n# kotlin.target = $target\n"
            assertTrue(beta.startsWith(header))
        }
        val rows = view().rows
        assertEquals(9, rows.count { it.cell("android").lines != it.cell("jvm").lines })
        assertEquals(0, rows.count { it.cell("ios").lines != it.cell("macos").lines })
        val next = view("2.5.0-Beta1").rows
        assertEquals(9, next.count { it.cell("android").lines != it.cell("jvm").lines })
        assertEquals(0, next.count { it.cell("ios").lines != it.cell("macos").lines })
    }

    @Test
    fun jdkColumnsDifferWhereTheJdkChangedAndNowhereElse() {
        val rows = view().rows
        fun differing(a: String, b: String) =
            rows.filter { it.cell(a).lines != it.cell(b).lines }.map { it.name }.toSet()
        assertEquals(
            setOf(
                "bmp-case-mapping",
                "bmp-categories",
                "shortest-digits",
                "messages",
                "float-conversions",
                "unicode-matching",
            ),
            differing("jvm-17", "jvm-21"),
        )
        assertEquals(setOf("bmp-case-mapping", "bmp-categories"), differing("jvm-21", "jvm-25"))
        assertEquals(
            setOf("nan-bits", "pow-special-cases", "transcendental-bits"),
            differing("jvm", "jvm-21"),
        )
        assertEquals(setOf("nan-bits"), differing("linux", "linux-arm64"))
    }

    @Test
    fun columnsAreGroupedByTargetFamily() {
        val groups = view().groups
        assertEquals(
            listOf("JVM", "Native", "Wasm", "JS", "Mobile", "Cloudflare"),
            groups.map { it.name },
        )
        assertEquals(listOf("android", "ios"), groups.first { it.name == "Mobile" }.targets)
        assertEquals(
            listOf("cloudflare-jvm", "cloudflare-wasm"),
            groups.last().targets,
        )
        assertEquals(
            listOf("jvm", "jvm-17", "jvm-21", "jvm-25"),
            groups.first { it.name == "JVM" }.targets,
        )
        assertEquals(view().targets, groups.flatMap { it.targets })
        assertEquals(
            listOf("JVM", "Native", "Wasm", "Mobile", "Other"),
            listOf("jvm-25", "linux-arm64", "wasm-webkit", "ios", "plan9").map { family(it) },
        )
        for (key in dataset.columns) assertNotEquals("Other", family(key.target), key.label)
        assertEquals(
            listOf("Native", "Other"),
            groupTargets(listOf("plan9", "mingw", "macos")).map { it.name },
        )
    }

    @Test
    fun browserTranscriptsAgreeWithEachOtherAndDifferFromNodeInOneProbe() {
        val engines = listOf("wasm-chromium", "wasm-firefox", "wasm-webkit")
        for (target in engines) {
            val text = AtlasFixtures.text("$target.txt")
            assertTrue(text.startsWith("# kotlin.version = 2.4.20\n# kotlin.target = $target\n"))
        }
        val rows = view().rows
        assertEquals(0, rows.count { r -> engines.map { r.cell(it).lines }.distinct().size > 1 })
        val apart = rows.filter { it.cell("wasm-chromium").lines != it.cell("wasm").lines }
        assertEquals(listOf("kotlin.double.nan-bits"), apart.map { it.id })
    }

    @Test
    fun divergentOnlyHidesTheIdenticalRows() {
        val all = view()
        val only = view(divergentOnly = true)
        assertEquals(38, all.rows.size)
        assertEquals(0, all.hidden)
        assertEquals(32, only.rows.size)
        assertEquals(6, only.hidden)
        assertTrue(only.rows.all { it.divergent })
    }

    @Test
    fun levelChangesNotesNotRows() {
        val summary = view(level = DetailLevel.SUMMARY)
        val detail = view(level = DetailLevel.DETAIL)
        val full = view(level = DetailLevel.FULL)
        assertEquals(summary.rows.map { it.id to it.cells }, full.rows.map { it.id to it.cells })
        assertTrue(summary.rows.all { it.notes.isEmpty() })
        assertTrue(detail.rows.all { it.notes.size == 1 })
        val row = full.rows.first { it.id == "kotlin.regex.anchors-lines" }
        assertTrue(row.notes.any { it.startsWith("reference: https://") })
        assertTrue(
            row.notes.any { it == "linked issues: KT-82450 (Submitted)" },
        )
        assertTrue(row.notes.any { it.startsWith("reference: https://") && "\"" in it })
        assertTrue(row.notes.any { it == "basis: verified" })
        assertEquals("platform-defined", row.badge)
    }

    @Test
    fun unclassifiedRowsSayNoExplanationWasFound() {
        val row = view().rows.first { it.id == "kotlin.double.parse" }
        assertEquals("unclassified: no explanation found", row.badge)
    }

    @Test
    fun deviceMatchesWhereTheTranscriptsAgree() {
        val v = view(device = macos)
        assertEquals("this JVM", v.deviceLabel)
        assertTrue("matches every column: 6" in v.deviceSummary)
        assertTrue("matches some columns: 32" in v.deviceSummary)
        assertTrue("matches no column (new data, not an error): 0" in v.deviceSummary)
        assertTrue("macos: 38 of 38 probes match" in v.deviceSummary)
        assertTrue("ios: 38 of 38 probes match" in v.deviceSummary)
        for ((target, n) in mapOf(
            "android" to 20, "jvm" to 21, "jvm-17" to 22, "jvm-21" to 21, "jvm-25" to 21,
            "linux" to 36, "linux-arm64" to 37, "wasm" to 25, "wasm-webkit" to 25,
            "js-ir" to 11,
        )) {
            assertTrue("$target: $n of 38 probes match" in v.deviceSummary, target)
        }
        val row = v.rows.first { it.id == "kotlin.double.nan-bits" }
        assertEquals("matches ios, linux-arm64, macos", row.deviceVerdict)
        assertEquals(row.cell("macos").lines, row.device?.lines)
        assertTrue(view().deviceSummary.isEmpty())
    }

    @Test
    fun deviceOutsideEveryColumnIsNewData() {
        val text = AtlasFixtures.text("macos.txt")
            .replace("@@ kotlin.int.overflow\n", "@@ kotlin.int.overflow\nzzz = new\n")
        val v = view(device = Devices.of("x", text, dataset))
        assertEquals("new data", v.rows.first { it.id == "kotlin.int.overflow" }.deviceVerdict)
        assertTrue("matches no column (new data, not an error): 1" in v.deviceSummary)
        assertTrue(v.rows.first { it.id == "kotlin.int.overflow" }.device!!.short.startsWith("L1 "))
    }

    @Test
    fun deviceFromAnotherVersionIsLabelled() {
        val v = view("2.5.0-Beta1", device = macos)
        assertTrue(v.deviceSummary.any { it.startsWith("this device runs kotlin 2.4.20") })
    }

    @Test
    fun drillShowsSourceResultsAndCitations() {
        val state = AtlasState(dataset, device = macos)
        val lines = AtlasView.drill(state, "kotlin.regex.anchors-lines")
        assertTrue("platform-defined" in lines)
        assertTrue("source:" in lines)
        assertTrue("jvm on kotlin 2.4.20:" in lines)
        assertTrue("this JVM:" in lines)
        assertTrue("linked issues: KT-82450 (Submitted)" in lines)
        assertTrue(AtlasView.drill(state, "no.such.probe").isEmpty())
    }

    @Test
    fun deviceIdNamesPlatformRuntimeAndOsFamilyOnly() {
        assertEquals("jvm-21-linux", Devices.id(FakeHost("jvm", "linux"), "21"))
        assertEquals("jvm-macos", Devices.id(FakeHost("jvm", "macos")))
        assertEquals("jvm-21-windows", Devices.id(FakeHost("jvm", "windows"), "21"))
        assertEquals("wasm-chromium-153", Devices.id(FakeHost("wasm", "unknown"), "chromium-153"))
        assertEquals("wasm", Devices.id(FakeHost("wasm", "unknown"), ""))
        assertEquals("android", Devices.id(FakeHost("android", "android")))
        assertEquals("linux-arm64", Devices.id(FakeHost("linux-arm64", "linux")))
        assertEquals("macos", Devices.id(FakeHost("macos", "macos")))
    }

    @Test
    fun browserIdReadsEngineMajorVersionAndOsFromTheUserAgent() {
        val chrome = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"
        val firefox = "Mozilla/5.0 (X11; Linux x86_64; rv:155.0) Gecko/20100101 Firefox/155.0"
        val safari = "Mozilla/5.0 (iPhone; CPU iPhone OS 26_5 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.5 Mobile/15E148 Safari/604.1"
        val android = "Mozilla/5.0 (Linux; Android 16; Pixel 9) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/153.0.0.0 Mobile Safari/537.36"
        val windows = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0"
        assertEquals("chromium-153-macos", Devices.browser(chrome))
        assertEquals("firefox-155-linux", Devices.browser(firefox))
        assertEquals("webkit-26-ios", Devices.browser(safari))
        assertEquals("chromium-153-android", Devices.browser(android))
        assertEquals("chromium-153-windows", Devices.browser(windows))
        assertEquals("", Devices.browser("curl/8.0"))
    }

    @Test
    fun aDeviceColumnIsAnAtlasTranscriptThatReadsBackAndJoinsTheDataset() {
        val host = FakeHost("jvm", "linux")
        val id = Devices.id(host, "21")
        val text = Atlas.record(host, id)
        val column = Transcripts.read(id, text)
        assertEquals(id, column.target)
        assertEquals(KotlinCatalog.all.size, column.cells.size)
        for (result in ProbeRunner.run(host, KotlinCatalog.all.map { it.probe })) {
            assertEquals(result.transcript, column.cells.getValue(result.id).transcript)
        }
        assertEquals(
            listOf("# kotlin.version = ${column.version}", "# kotlin.target = $id"),
            text.lines().takeWhile { !it.startsWith("@@ ") },
        )
        assertTrue(host.ran.isEmpty())
        val device = Devices.of(id, text, dataset)
        assertEquals(KotlinCatalog.all.size, device.measured)
        val names = AtlasFixtures.names.filter { it.endsWith(".txt") }.sorted()
        val recorded = names.map { Transcripts.read(it, AtlasFixtures.text(it)) }
        val joined = Dataset.build(recorded + column)
        assertTrue(ColumnKey(id, column.version) in joined.columns)
    }

    @Test
    fun kotlinProbesNeedNoProcessFilesOrEnvironment() {
        assertEquals(38, KotlinCatalog.all.size)
        assertTrue(KotlinCatalog.all.all { it.probe.needs.isEmpty() })
    }

    @Test
    fun rowsSayWhichColumnsAgreeAndDifferAndTheDrillRepeatsIt() {
        val v = view(device = macos)
        val row = v.rows.first { it.id == "kotlin.double.nan-bits" }
        assertEquals(listOf("linux-arm64", "macos", "ios"), row.agrees)
        assertEquals(v.targets - row.agrees.toSet(), row.differs)
        assertTrue(view().rows.all { it.agrees.isEmpty() && it.differs.isEmpty() })
        val lines = AtlasView.drill(AtlasState(dataset, device = macos), row.id)
        assertTrue("same as this device: ios, linux-arm64, macos" in lines)
        val differs = lines.first { it.startsWith("differs from this device: ") }
        assertEquals(
            row.differs.toSet(),
            differs.removePrefix("differs from this device: ").split(", ").toSet(),
        )
    }

    @Test
    fun viewModelGoldens() {
        val a = view("2.4.20", DetailLevel.FULL, macos).encode()
        val b = view("2.5.0-Beta1", DetailLevel.DETAIL).encode()
        assertEquals(FULL_MACOS, Sha256.hex(a), "full 2.4.20 with macos device")
        assertEquals(DETAIL_BETA, Sha256.hex(b), "detail 2.5.0-Beta1")
    }
}
