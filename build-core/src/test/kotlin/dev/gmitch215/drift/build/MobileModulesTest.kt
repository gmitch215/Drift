package dev.gmitch215.drift.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobileModulesTest {
	private val root = generateSequence(File("").absoluteFile) { it.parentFile }
		.first { File(it, "settings.gradle.kts").isFile && File(it, "build-core").isDirectory }

	@Test
	fun mobileModulesAreKotlinModulesOfTheBuild() {
		val settings = File(root, "settings.gradle.kts").readText()
		for (module in MobileModules.all) {
			assertTrue("\"$module\"" in settings, module)
			assertTrue(File(root, "$module/src/common").isDirectory, module)
		}
	}

	@Test
	fun mobileTargetsAreOffUnlessAskedFor() {
		val properties = File(root, "gradle.properties").readText()
		assertFalse("drift.mobile" in properties)
	}

	@Test
	fun theAndroidAppIsOnlyIncludedWhenMobileIsOn() {
		val settings = File(root, "settings.gradle.kts").readText()
		val block = settings.substringAfter("if (providers.gradleProperty(\"drift.mobile\")")
		assertEquals("androidApp", Regex("include\\(\"(\\w+)\"\\)").find(block)?.groupValues?.get(1))
	}
}
