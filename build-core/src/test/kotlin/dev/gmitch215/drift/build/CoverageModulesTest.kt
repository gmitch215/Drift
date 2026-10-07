package dev.gmitch215.drift.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverageModulesTest {
	private val root = generateSequence(File("").absoluteFile) { it.parentFile }
		.first { File(it, "settings.gradle.kts").isFile && File(it, "build-core").isDirectory }

	private val included = Regex("""include\(([^)]*)\)""")
		.findAll(File(root, "settings.gradle.kts").readText())
		.flatMap { Regex(""""([^"]+)"""").findAll(it.groupValues[1]).map { m -> m.groupValues[1] } }
		.toList()

	@Test
	fun everyJvmModuleIsAnAggregateInput() {
		val jvm = included.filter { File(root, "$it/src/jvm").isDirectory }
		assertTrue(jvm.isNotEmpty())
		assertEquals(emptyList(), jvm.filter { it !in CoverageModules.all })
	}

	@Test
	fun ratioModulesAreCoverageModules() {
		assertTrue(CoverageModules.all.containsAll(CoverageModules.ratio))
	}
}
