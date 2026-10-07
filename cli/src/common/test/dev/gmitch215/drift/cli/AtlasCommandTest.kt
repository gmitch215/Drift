package dev.gmitch215.drift.cli

import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.fixtures.AtlasFixtures
import dev.gmitch215.drift.fixtures.AtlasVariants
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.host.systemHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class AtlasCommandTest {
	private val names = listOf("jvm.txt", "macos.txt", "wasm.txt")
	private val disk = MemoryCaseFiles(
		names.associateWith { AtlasFixtures.text(it) }.toMutableMap(),
	)

	private fun drift(args: String, files: CaseFiles = disk) =
		DriftCommand(FakeHost(canRun = false), files).test(args)

	private val target = systemHost().let {
		if (it.platform == "linux" && it.arch in ARM) "linux-arm64" else it.platform
	}

	// the jvm fixture is arm64 and x86-64 sets the sign bit of a generated NaN; linux is exact
	private fun portable(text: String) = when {
		target.startsWith("linux") -> text

		systemHost().platform == "jvm" && systemHost().arch !in ARM ->
			text.lines().joinToString("\n") { it.substringBefore(" = ") }

		else -> text.replace("fff8000000000000", "7ff8000000000000")
	}

	// debug test binaries do not fold libm constants, so linux reproduces a linux-built transcript
	private fun committed(): String? {
		if (!target.startsWith("linux")) {
			val column = "$target.txt"
			return if (column in AtlasFixtures.names) AtlasFixtures.text(column) else null
		}
		val name = "$target@linux.txt"
		if (name !in AtlasVariants.names) {
			fail(
				"no recorded transcript $name; run `drift atlas record --target $target` " +
					"on a linux host and save it as fixtures/atlas-variants/$name",
			)
		}
		return AtlasVariants.text(name)
	}

	private fun built(): MemoryCaseFiles {
		val result = drift("atlas build jvm.txt macos.txt wasm.txt --out dataset.json")
		assertEquals(0, result.statusCode)
		return disk
	}

	@Test
	fun recordWritesTheCommittedBytesForThisTarget() {
		val files = MemoryCaseFiles()
		val command = DriftCommand(systemHost(), files)
		val result = command.test("atlas record --out out/ --target $target")
		assertEquals(0, result.statusCode, result.stderr)
		val written = files.files.getValue("out/$target.txt")
		assertTrue("probes for $target on kotlin" in result.stderr, result.stderr)
		val recorded = committed()
		if (recorded != null) {
			assertEquals(portable(recorded), portable(written))
		} else {
			assertTrue(written.startsWith("# kotlin.version = "), written.take(80))
		}
	}

	@Test
	fun recordWithoutOutPrintsTheExactTranscriptToStdout() {
		val host = systemHost()
		val files = MemoryCaseFiles()
		val printed = DriftCommand(host, files).test("atlas record")
		assertEquals(0, printed.statusCode, printed.stderr)
		DriftCommand(host, files).test("atlas record --out out")
		assertEquals(files.files.getValue("out/${host.platform}.txt"), printed.stdout)
		assertTrue(files.files.size == 1, "stdout mode writes no file")
	}

	@Test
	fun recordLabelsAnotherTargetWithoutChangingTheResults() {
		val host = systemHost()
		val files = MemoryCaseFiles()
		val result = DriftCommand(host, files).test("atlas record --out out --target linux-arm64")
		assertEquals(0, result.statusCode, result.stderr)
		val written = files.files.getValue("out/linux-arm64.txt")
		assertTrue("# kotlin.target = linux-arm64\n" in written)
		DriftCommand(host, files).test("atlas record --out out")
		val own = files.files.getValue("out/${host.platform}.txt")
		assertEquals(
			own.substringAfter("# kotlin.target = ${host.platform}\n"),
			written.substringAfter("# kotlin.target = linux-arm64\n"),
		)
	}

	@Test
	fun recordRefusesABadTargetAndAFailingWriter() {
		val bad = DriftCommand(
			systemHost(),
			MemoryCaseFiles(),
		).test("atlas record --out out --target 'a b'")
		assertEquals(1, bad.statusCode)
		assertTrue("--target may only use" in bad.stderr, bad.stderr)
		val failing = object : CaseFiles {
			override fun read(path: String): String? = null

			override fun write(path: String, text: String) = false
		}
		val result = DriftCommand(systemHost(), failing).test("atlas record --out out")
		assertEquals(1, result.statusCode)
		assertTrue("cannot write transcript: out/" in result.stderr, result.stderr)
	}

	@Test
	fun buildWritesTheGoldenDataset() {
		val result = drift("atlas build jvm.txt macos.txt wasm.txt --out dataset.json")
		assertEquals(0, result.statusCode, result.stderr)
		assertEquals("", result.stdout)
		val text = disk.files.getValue("dataset.json")
		assertTrue(text.endsWith("}\n"))
		assertEquals(DATASET_SHA, Sha256.hex(text.removeSuffix("\n")))
		assertEquals(
			"dataset: 38 probes, 3 columns, 23 divergent, sha256 $DATASET_SHA\n",
			result.stderr,
		)
	}

	@Test
	fun buildNamesTheFileItCannotUse() {
		val missing = drift("atlas build jvm.txt nope.txt --out d.json")
		assertEquals(1, missing.statusCode)
		assertTrue("cannot read transcript: nope.txt" in missing.stderr, missing.stderr)
		disk.files["cut.txt"] = AtlasFixtures.text("jvm.txt").dropLast(4)
		val cut = drift("atlas build cut.txt --out d.json")
		assertEquals(1, cut.statusCode)
		assertTrue("truncated transcript cut.txt" in cut.stderr, cut.stderr)
		val twice = drift("atlas build jvm.txt jvm.txt --out d.json")
		assertEquals(1, twice.statusCode)
		assertTrue("jvm.txt and jvm.txt both record jvm@2.4.20" in twice.stderr, twice.stderr)
		assertEquals(1, drift("atlas build --out d.json").statusCode)
		assertEquals(1, drift("atlas build jvm.txt").statusCode)
		val failing = object : CaseFiles {
			override fun read(path: String) = disk.read(path)

			override fun write(path: String, text: String) = false
		}
		val unwritable = drift("atlas build jvm.txt --out dataset.json", failing)
		assertEquals(1, unwritable.statusCode)
		assertTrue("cannot write dataset: dataset.json" in unwritable.stderr, unwritable.stderr)
	}

	@Test
	fun compareHasThreeLevelsOfTheSameData() {
		built()
		val summary = drift("atlas compare dataset.json macos.txt")
		assertEquals(0, summary.statusCode, summary.stderr)
		val head = "this device: macos, kotlin 2.4.20, 38 probes measured\n"
		assertTrue(summary.stdout.startsWith(head))
		assertTrue("  jvm@2.4.20: 21 of 38 probes match\n" in summary.stdout, summary.stdout)
		val detail = drift("atlas compare dataset.json macos.txt --detail detail")
		assertEquals(COMPARE_DETAIL_SHA, Sha256.hex(detail.stdout))
		val full = drift("atlas compare dataset.json macos.txt --detail full")
		assertEquals(COMPARE_FULL_SHA, Sha256.hex(full.stdout))
		assertTrue(detail.stdout.startsWith(summary.stdout))
		assertTrue(full.stdout.startsWith(detail.stdout.substringBefore("\nkotlin.char")))
	}

	@Test
	fun compareRefusesBadInputs() {
		built()
		disk.files["bad.json"] = "{not json"
		val bad = drift("atlas compare bad.json macos.txt")
		assertEquals(1, bad.statusCode)
		assertTrue("invalid atlas dataset" in bad.stderr, bad.stderr)
		val missing = drift("atlas compare dataset.json nope.txt")
		assertEquals(1, missing.statusCode)
		assertTrue("cannot read transcript: nope.txt" in missing.stderr, missing.stderr)
		assertEquals(1, drift("atlas compare dataset.json macos.txt --detail loud").statusCode)
		assertEquals(1, drift("atlas compare nope.json macos.txt").statusCode)
	}

	@Test
	fun showPrintsTheMatrixAndOneReproDraft() {
		built()
		val matrix = drift("atlas show dataset.json")
		assertEquals(0, matrix.statusCode, matrix.stderr)
		assertEquals(MATRIX_SHA, Sha256.hex(matrix.stdout))
		val repro = drift("atlas show dataset.json --probe kotlin.double.tostring-boundaries")
		assertEquals(0, repro.statusCode, repro.stderr)
		assertEquals(REPRO_FLOAT_SHA, Sha256.hex(repro.stdout))
		assertTrue(repro.stdout.startsWith("GENERATED DRAFT, NOT REVIEWED\n"))
		val unknown = drift("atlas show dataset.json --probe kotlin.nope")
		assertEquals(1, unknown.statusCode)
		assertTrue("no probe kotlin.nope in the dataset" in unknown.stderr, unknown.stderr)
	}

	private companion object {
		val ARM = setOf("aarch64", "arm64")
		const val DATASET_SHA =
			"3cf771d67a004c85570eeb323050261a6a2a1d8f74fac71f04551760fb8f0470"
		const val COMPARE_DETAIL_SHA =
			"76582ef23235a1b42ac78b33fb69551f6122f9a9c57ca3b3a41024b599e42d25"
		const val COMPARE_FULL_SHA =
			"49c0992b73650aede50b1a0475d8d08ac80c4f925c2cbe58a5c34fa56b5c904b"
		const val MATRIX_SHA =
			"fb8daad72d22edf1c95ad4e3b721f6afc49c9f3421f69cf0580a984adba87327"
		const val REPRO_FLOAT_SHA =
			"55ab28b9888d81cfd7ae7d75d5be0727d31b07f02fca1ceb6217fbe2691f049f"
	}
}
