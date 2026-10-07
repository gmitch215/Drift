import dev.gmitch215.drift.build.EmbedClassification
import dev.gmitch215.drift.build.EmbedFixtures
import dev.gmitch215.drift.build.EmbedKotlinVersion

plugins {
	id("drift.kmp")
}

val embedFixtures = tasks.register<EmbedFixtures>("embedFixtures") {
	fixtures = rootProject.layout.projectDirectory.dir("fixtures/transcripts")
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

val embedKotlinVersion = tasks.register<EmbedKotlinVersion>("embedKotlinVersion") {
	version = libs.versions.kotlin
	outputDir = layout.buildDirectory.dir("generated/kotlinVersion")
}

val embedClassification = tasks.register<EmbedClassification>("embedClassification") {
	classification = rootProject.layout.projectDirectory.file("atlas/classification.yml")
	outputDir = layout.buildDirectory.dir("generated/classification")
}

kotlin {
	sourceSets {
		commonMain {
			kotlin.srcDir(embedKotlinVersion)
			kotlin.srcDir(embedClassification)
		}
		commonMain.dependencies {
			implementation(project(":core"))
			implementation(project(":host"))
		}
		commonTest {
			kotlin.srcDir(embedFixtures)
			kotlin.srcDir(embedAtlas)
			kotlin.srcDir(embedVariants)
		}
	}
}
