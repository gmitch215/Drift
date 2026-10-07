import dev.gmitch215.drift.build.EmbedFixtures
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
	id("drift.kmp")
}

val embedBenchData = tasks.register<EmbedFixtures>("embedBenchData") {
	fixtures = rootProject.layout.projectDirectory.dir("bench/data")
	objectName = "BenchData"
	outputDir = layout.buildDirectory.dir("generated/benchData")
}

kotlin {
	jvm {
		@OptIn(ExperimentalKotlinGradlePluginApi::class)
		mainRun {
			mainClass = "dev.gmitch215.drift.bench.MainKt"
		}
	}

	sourceSets {
		commonTest {
			kotlin.srcDir(embedBenchData)
		}
		commonMain.dependencies {
			implementation(project(":core"))
		}
		jvmMain.dependencies {
			implementation(libs.snakeyaml.engine)
		}
	}
}
