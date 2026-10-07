import dev.gmitch215.drift.build.EmbedDriftVersion
import dev.gmitch215.drift.build.EmbedFixtures
import dev.gmitch215.drift.build.EmbedRules

plugins {
	id("drift.kmp")
}

val embedFixtures = tasks.register<EmbedFixtures>("embedFixtures") {
	fixtures = rootProject.layout.projectDirectory.dir("fixtures/transcripts")
	outputDir = layout.buildDirectory.dir("generated/fixtures")
}

val embedRunFixtures = tasks.register<EmbedFixtures>("embedRunFixtures") {
	fixtures = rootProject.layout.projectDirectory.dir("fixtures/drangler")
	objectName = "RunFixtures"
	outputDir = layout.buildDirectory.dir("generated/runFixtures")
}

val embedLabFixtures = tasks.register<EmbedFixtures>("embedLabFixtures") {
	fixtures = rootProject.layout.projectDirectory.dir("fixtures/lab")
	objectName = "LabFixtures"
	outputDir = layout.buildDirectory.dir("generated/labFixtures")
}

val embedRules = tasks.register<EmbedRules>("embedRules") {
	rules = rootProject.layout.projectDirectory.dir("rules")
	outputDir = layout.buildDirectory.dir("generated/rules")
}

val embedDriftVersion = tasks.register<EmbedDriftVersion>("embedDriftVersion") {
	version = project.version.toString()
	outputDir = layout.buildDirectory.dir("generated/driftVersion")
}

kotlin {
	sourceSets {
		commonMain {
			kotlin.srcDir(embedRules)
			kotlin.srcDir(embedDriftVersion)
		}

		commonTest {
			kotlin.srcDir(embedFixtures)
			kotlin.srcDir(embedRunFixtures)
			kotlin.srcDir(embedLabFixtures)
		}
	}
}
