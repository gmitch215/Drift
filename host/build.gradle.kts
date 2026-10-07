plugins {
	id("drift.kmp")
}

kotlin {
	sourceSets {
		commonMain.dependencies {
			api(project(":core"))
		}
		jvmMain.dependencies {
			implementation(libs.oshi.core)
			runtimeOnly(libs.slf4j.nop)
		}
	}
}
