package dev.gmitch215.drift.build

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedRatioTest {
	private fun tree(vararg files: Pair<String, String>): File {
		val root = Files.createTempDirectory("shared-ratio").toFile()
		for ((path, text) in files) File(root, path).apply { parentFile.mkdirs() }.writeText(text)
		return root
	}

	@Test
	fun countsCodeLinesOnly() {
		val root = tree(
			"core/src/common/main/dev/A.kt" to
				"package a\n\n// note\nval x = 1\n/* c */\nval y = 2\n * star\n",
			"host/src/jvm/main/dev/B.kt" to "package b\nval z = 3\n",
		)
		val result = SharedRatio.measure(root, listOf("host", "core"))
		assertEquals(SharedRatio.Lines(3, 0), result["core"])
		assertEquals(SharedRatio.Lines(0, 2), result["host"])
	}

	@Test
	fun testsAreIgnored() {
		val root = tree("core/src/common/test/dev/T.kt" to "val t = 1\n")
		assertEquals(SharedRatio.Lines(0, 0), SharedRatio.measure(root, listOf("core"))["core"])
	}

	@Test
	fun missingModulesAreSkipped() {
		val root = tree("core/src/common/main/dev/A.kt" to "val a = 1\n")
		assertEquals(listOf("core"), SharedRatio.measure(root, listOf("core", "studio")).keys.toList())
	}

	@Test
	fun floorFailsBelowThreshold() {
		val root = tree(
			"core/src/common/main/dev/A.kt" to "val a = 1\n",
			"host/src/jvm/main/dev/B.kt" to "val b = 1\nval c = 2\nval d = 3\n",
		)
		val result = SharedRatio.measure(root, listOf("host", "core"))
		assertEquals(1, SharedRatio.violations(result, 50.0, 0).size)
		assertEquals(emptyList(), SharedRatio.violations(result, 20.0, 0))
	}

	@Test
	fun hostBudget() {
		val root = tree("host/src/jvm/main/dev/B.kt" to "val b = 1\nval c = 2\n")
		val result = SharedRatio.measure(root, listOf("host"))
		assertEquals(1, SharedRatio.violations(result, 0.0, 1).size)
		assertEquals(emptyList(), SharedRatio.violations(result, 0.0, 2))
	}

	@Test
	fun coreMustHaveNoPlatformLines() {
		val root = tree("core/src/jvm/main/dev/A.kt" to "val a = 1\n")
		val problems = SharedRatio.violations(SharedRatio.measure(root, listOf("core")), 0.0, 0)
		assertEquals(1, problems.size)
		assertTrue("core has 1 platform lines" in problems.single())
	}

	@Test
	fun emptyTreeIsFullyShared() {
		assertEquals(100.0, SharedRatio.percent(0, 0))
	}

	@Test
	fun tableListsEveryModuleAndTheTotal() {
		val table = SharedRatio.table(mapOf("core" to SharedRatio.Lines(9, 1)))
		assertTrue(table.lines().any { it.startsWith("core") && it.endsWith("90.0%") })
		assertTrue(table.lines().any { it.startsWith("total") })
	}
}
