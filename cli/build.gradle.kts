import dev.gmitch215.drift.build.EmbedFixtures
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
	id("drift.kmp")
}

val embedFixtures = tasks.register<EmbedFixtures>("embedFixtures") {
	fixtures = rootProject.layout.projectDirectory.dir("fixtures/drangler/capsules")
	outputDir = layout.buildDirectory.dir("generated/fixtures")
}

val embedAtlas = tasks.register<EmbedFixtures>("embedAtlas") {
	fixtures = rootProject.layout.projectDirectory.dir("fixtures/atlas")
	objectName = "AtlasFixtures"
	outputDir = layout.buildDirectory.dir("generated/atlas")
}

val embedVariants = tasks.register<EmbedFixtures>("embedVariants") {
	fixtures = rootProject.layout.projectDirectory.dir("fixtures/atlas-variants")
	objectName = "AtlasVariants"
	outputDir = layout.buildDirectory.dir("generated/variants")
}

tasks.register<Sync>("stageStudio") {
	dependsOn(":studio:wasmJsBrowserDistribution")
	from(rootProject.layout.projectDirectory.dir("studio/build/dist/wasmJs/productionExecutable")) {
		exclude("*.map")
	}
	into(layout.buildDirectory.dir("serve/studio"))
}

kotlin {
	jvm {
		@OptIn(ExperimentalKotlinGradlePluginApi::class)
		mainRun {
			mainClass = "dev.gmitch215.drift.cli.MainKt"
		}
	}

	listOf(linuxX64(), linuxArm64(), macosArm64(), mingwX64()).forEach {
		it.binaries.executable {
			entryPoint = "dev.gmitch215.drift.cli.main"
			baseName = "drift"
		}
	}

	listOf(linuxX64(), linuxArm64()).forEach {
		// clikt and clikt-mordant both define an identical selfAndAncestors (KT-81760)
		it.binaries.all { linkerOpts("--allow-multiple-definition") }
	}

	sourceSets {
		commonMain.dependencies {
			implementation(project(":core"))
			implementation(project(":host"))
			implementation(project(":scan"))
			implementation(libs.clikt)
		}
		commonTest {
			kotlin.srcDir(embedFixtures)
			kotlin.srcDir(embedAtlas)
			kotlin.srcDir(embedVariants)
		}
		macosMain { kotlin.srcDir("src/posix/main") }
		linuxMain { kotlin.srcDir("src/posix/main") }
		macosTest { kotlin.srcDir("src/posix/test") }
		linuxTest { kotlin.srcDir("src/posix/test") }
	}
}
