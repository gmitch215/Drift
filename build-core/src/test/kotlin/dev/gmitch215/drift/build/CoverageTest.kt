package dev.gmitch215.drift.build

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverageTest {
	private val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
		|<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
		|<report name="drift">
		|<package name="dev/x">
		|<sourcefile name="A.kt"><line nr="1" mi="0" ci="1"/><counter type="LINE" missed="1" covered="3"/></sourcefile>
		|<sourcefile name="B.kt"><counter type="LINE" missed="0" covered="4"/></sourcefile>
		|<counter type="LINE" missed="1" covered="7"/>
		|</package>
		|<counter type="LINE" missed="1" covered="7"/>
		|</report>
	""".trimMargin()

	private fun tree(vararg files: String): File {
		val root = Files.createTempDirectory("coverage").toFile()
		for (path in files) File(root, path).apply { parentFile.mkdirs() }.writeText("val x = 1\n")
		return root
	}

	@Test
	fun parsesFilesAndTotalsWithADoctype() {
		val report = Coverage.parse(xml)
		assertEquals(listOf("dev/x/A.kt", "dev/x/B.kt"), report.files.map { it.path })
		assertEquals(7, report.covered)
		assertEquals(1, report.missed)
		assertEquals(87.5, report.percent)
	}

	@Test
	fun acceptsAMappedReportAboveTheFloor() {
		val root = tree("core/src/common/main/dev/x/A.kt", "core/src/common/main/dev/x/B.kt")
		val index = Coverage.index(root, listOf("core/src/common/main"))
		assertEquals(emptyList(), Coverage.check(Coverage.parse(xml), index, 80.0).problems)
	}

	@Test
	fun rejectsAReportBelowTheFloor() {
		val root = tree("core/src/common/main/dev/x/A.kt", "core/src/common/main/dev/x/B.kt")
		val index = Coverage.index(root, listOf("core/src/common/main"))
		assertEquals(1, Coverage.check(Coverage.parse(xml), index, 90.0).problems.size)
	}

	@Test
	fun rejectsAReportWithNoFiles() {
		val empty = "<report name=\"x\"><counter type=\"LINE\" missed=\"0\" covered=\"0\"/></report>"
		assertTrue(
			"no source files" in Coverage.check(Coverage.parse(empty), emptyMap(), 0.0).problems.single(),
		)
	}

	@Test
	fun rejectsFilesThatMapToTwoPlaces() {
		val root = tree("core/src/common/main/dev/x/A.kt", "host/src/jvm/main/dev/x/A.kt")
		val index = Coverage.index(root, listOf("core/src/common/main", "host/src/jvm/main"))
		val checked = Coverage.check(Coverage.parse(xml), index, 0.0)
		assertTrue(checked.problems.any { "dev/x/A.kt maps to 2 source files" in it })
	}

	@Test
	fun ignoresInlinedFilesButMeasuresOnlyMappedOnes() {
		val root = tree("core/src/common/main/dev/x/A.kt")
		val index = Coverage.index(root, listOf("core/src/common/main"))
		val checked = Coverage.check(Coverage.parse(xml), index, 70.0)
		assertEquals(emptyList(), checked.problems)
		assertEquals(listOf("dev/x/B.kt"), checked.unmapped)
		assertEquals(75.0, checked.mapped.percent)
	}

	@Test
	fun rejectsAReportWhereNothingMaps() {
		val checked = Coverage.check(Coverage.parse(xml), emptyMap(), 0.0)
		assertTrue("no file in the report maps" in checked.problems.single())
	}

	@Test
	fun groupsLinesByModule() {
		val root = tree("core/src/common/main/dev/x/A.kt", "scan/src/common/main/dev/x/B.kt")
		val index = Coverage.index(root, listOf("core/src/common/main", "scan/src/common/main"))
		assertEquals(
			mapOf("core" to (3 to 1), "scan" to (4 to 0)),
			Coverage.byModule(Coverage.parse(xml), index),
		)
	}

	@Test
	fun countsTestsPerModuleAndTarget() {
		val root = Files.createTempDirectory("results").toFile()
		File(root, "core/build/test-results/jvmTest").mkdirs()
		File(
			root,
			"core/build/test-results/jvmTest/a.xml",
		).writeText("<testsuite tests=\"3\" failures=\"1\" errors=\"0\">")
		File(
			root,
			"core/build/test-results/jvmTest/b.xml",
		).writeText("<testsuite tests=\"2\" failures=\"0\" errors=\"1\">")
		assertEquals(mapOf("core jvmTest" to Coverage.TestCount(5, 2)), Coverage.testCounts(root))
	}

	@Test
	fun summaryNamesWhatIsNotMeasured() {
		val report = Coverage.parse(xml)
		val text = Coverage.summary(
			mapOf("core" to (7 to 1)),
			report,
			mapOf("core jvmTest" to Coverage.TestCount(5, 0)),
		)
		assertTrue("Not measured" in text)
		assertTrue("core" in text && "87.5%" in text)
		assertTrue("core jvmTest" in text)
	}
}
