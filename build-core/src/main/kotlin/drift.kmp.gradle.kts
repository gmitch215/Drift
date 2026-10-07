import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
	id("drift.layout")
}

kotlin {
	jvm()
	linuxX64()
	linuxArm64()
	macosArm64()
	mingwX64()

	@OptIn(ExperimentalWasmDsl::class)
	wasmJs {
		nodejs()
	}
}

tasks.withType<AbstractTestTask>().configureEach {
	testLogging {
		events("failed")
		exceptionFormat = TestExceptionFormat.FULL
		showCauses = true
	}
}
