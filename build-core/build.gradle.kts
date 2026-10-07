plugins {
	`kotlin-dsl`
}

kotlin {
	jvmToolchain(21)
}

dependencies {
	implementation(libs.android.gradle.plugin)
	implementation(libs.kotlin.gradle.plugin)
	implementation(libs.dokka.gradle.plugin)
	implementation(libs.snakeyaml.engine)

	testImplementation(kotlin("test"))
	testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
	useJUnitPlatform()
}
