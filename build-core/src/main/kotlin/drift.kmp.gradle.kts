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
