import dev.gmitch215.drift.build.CoverageModules
import dev.gmitch215.drift.build.CoverageSummary
import dev.gmitch215.drift.build.LineLengthTask
import dev.gmitch215.drift.build.SharedRatioTask
import dev.gmitch215.drift.build.VerifyCoverage
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
	jacoco
}

val moduleNames = CoverageModules.ratio
val coverageModules = CoverageModules.all
val coverageRoots = coverageModules.flatMap { listOf("$it/src/common/main", "$it/src/jvm/main") }
val sourceTrees = files(coverageModules.map { fileTree("$it/src") { include("*/main/**/*.kt") } })

val sharedRatio = tasks.register<SharedRatioTask>("sharedRatio") {
	root = layout.projectDirectory
	sources.from(sourceTrees)
	modules = moduleNames
	floor = 80.0
	hostBudget = 400
}

val checkLineLength = tasks.register<LineLengthTask>("checkLineLength") {
	root = layout.projectDirectory
	editorConfig = layout.projectDirectory.file(".editorconfig")
	sources.from(
		fileTree(layout.projectDirectory) {
			include("**/*.kt", "**/*.kts")
			exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**", "**/kotlin-js-store/**")
			exclude(".idea/**")
		},
	)
}

val jvmModules = coverageModules.filter { file(it).isDirectory }

val jvmJacocoAggregateReport = tasks.register<JacocoReport>("jvmJacocoAggregateReport") {
	dependsOn(jvmModules.map { ":$it:jvmTest" })
	classDirectories.setFrom(jvmModules.map { file("$it/build/classes/kotlin/jvm/main") })
	sourceDirectories.setFrom(coverageRoots.map { file(it) })
	executionData.setFrom(jvmModules.map { file("$it/build/jacoco/jvmTest.exec") })

	reports {
		xml.required = true
		xml.outputLocation = layout.buildDirectory.file("jacoco.xml")
		html.required = true
		html.outputLocation = layout.buildDirectory.dir("jacocoHtml")
	}
}

val verifyCoverage = tasks.register<VerifyCoverage>("verifyCoverage") {
	dependsOn(jvmJacocoAggregateReport)
	root = layout.projectDirectory
	report = layout.buildDirectory.file("jacoco.xml")
	sources.from(sourceTrees)
	sourceRoots = coverageRoots
	floor = 95.0
}

val coverageSummary = tasks.register<CoverageSummary>("coverageSummary") {
	dependsOn(jvmJacocoAggregateReport)
	root = layout.projectDirectory
	report = layout.buildDirectory.file("jacoco.xml")
	testResults.from(fileTree(rootDir) { include("*/build/test-results/*/*.xml") })
	sourceRoots = coverageRoots
	output = layout.buildDirectory.file("coverage/summary.txt")
}

tasks.named("check") {
	dependsOn(
		sharedRatio,
		checkLineLength,
		verifyCoverage,
		gradle.includedBuild("build-core").task(":check"),
	)
}
