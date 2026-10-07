package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.fixtures.AtlasFixtures
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.scan.probe.kotlin.KotlinCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val FULL_MACOS = "a0196c4418d58330cdf2cb08cc3ba02d5c57ae5128e90976d2ac47bc54081f61"

private const val DETAIL_BETA = "19c6d795e935ccfa77233c59fa6c2445c3dbe56326882b251cb8b6a0efcbe7c9"

private val TARGETS_2_4_20 = listOf(
	"jvm", "jvm-17", "jvm-21", "jvm-25", "linux", "linux-arm64", "macos",
	"wasm", "wasm-chromium", "wasm-firefox", "wasm-webkit", "android", "ios",
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
		assertEquals(23, old.divergent)
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
		assertEquals(listOf("JVM", "Native", "Wasm", "Mobile"), groups.map { it.name })
		assertEquals(listOf("android", "ios"), groups.last().targets)
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
		assertEquals(23, only.rows.size)
		assertEquals(15, only.hidden)
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
		val row = full.rows.first { it.id == "kotlin.collections.iterator-modification" }
		assertTrue(row.notes.any { it.startsWith("reference: https://") })
		assertTrue(
			row.notes.any { it == "linked issues: KT-88775 (Open), KT-89031 (To be discussed)" },
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
		assertTrue("matches every column: 15" in v.deviceSummary)
		assertTrue("matches some columns: 23" in v.deviceSummary)
		assertTrue("matches no column (new data, not an error): 0" in v.deviceSummary)
		assertTrue("macos: 38 of 38 probes match" in v.deviceSummary)
		assertTrue("ios: 38 of 38 probes match" in v.deviceSummary)
		for ((target, n) in mapOf(
			"android" to 20, "jvm" to 21, "jvm-17" to 22, "jvm-21" to 21, "jvm-25" to 21,
			"linux" to 36, "linux-arm64" to 37, "wasm" to 25, "wasm-webkit" to 25,
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
		val lines = AtlasView.drill(state, "kotlin.collections.iterator-modification")
		assertTrue("platform-defined" in lines)
		assertTrue("source:" in lines)
		assertTrue("jvm on kotlin 2.4.20:" in lines)
		assertTrue("this JVM:" in lines)
		assertTrue("linked issues: KT-88775 (Open), KT-89031 (To be discussed)" in lines)
		assertTrue(AtlasView.drill(state, "no.such.probe").isEmpty())
	}

	@Test
	fun thisDeviceIsMeasuredHere() {
		val device = Devices.measure(dataset)
		assertTrue(device.label.startsWith("this "), device.label)
		assertEquals(KotlinCatalog.all.size, device.measured)
		assertTrue(device.transcript.startsWith("# kotlin.version = "))
		assertEquals("this JVM", Devices.label("jvm"))
		assertEquals("this browser (wasm)", Devices.label("wasm"))
		assertEquals("this device (linuxX64)", Devices.label("linuxX64"))
	}

	@Test
	fun viewModelGoldens() {
		val a = view("2.4.20", DetailLevel.FULL, macos).encode()
		val b = view("2.5.0-Beta1", DetailLevel.DETAIL).encode()
		assertEquals(FULL_MACOS, Sha256.hex(a), "full 2.4.20 with macos device")
		assertEquals(DETAIL_BETA, Sha256.hex(b), "detail 2.5.0-Beta1")
	}
}
