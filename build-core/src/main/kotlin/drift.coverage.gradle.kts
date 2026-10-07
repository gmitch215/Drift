import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
	jacoco
}

val jvmJacocoTestReport = tasks.register<JacocoReport>("jvmJacocoTestReport") {
	dependsOn("jvmTest")
	classDirectories.setFrom(layout.buildDirectory.dir("classes/kotlin/jvm/main"))
	sourceDirectories.setFrom("src/common/main", "src/jvm/main")
	executionData.setFrom(layout.buildDirectory.file("jacoco/jvmTest.exec"))

	reports {
		xml.required = true
		xml.outputLocation = layout.buildDirectory.file("jacoco.xml")
		html.required = true
		html.outputLocation = layout.buildDirectory.dir("jacocoHtml")
	}
}

tasks.matching { it.name == "jvmTest" }.configureEach {
	finalizedBy(jvmJacocoTestReport)
}
