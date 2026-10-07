import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
	id("drift.layout")
}

kotlin {
	jvm {
		@OptIn(ExperimentalKotlinGradlePluginApi::class)
		mainRun {
			mainClass = "dev.gmitch215.drift.tools.MainKt"
		}
	}

	sourceSets.jvmMain.dependencies {
		implementation(project(":core"))
		implementation(libs.snakeyaml.engine)
	}
}
