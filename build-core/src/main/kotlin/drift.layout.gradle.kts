import dev.gmitch215.drift.build.MobileModules
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
	id("org.jetbrains.kotlin.multiplatform")
	id("drift.coverage")
	id("drift.docs")
}

if (providers.gradleProperty("drift.mobile").orNull == "true" && name in MobileModules.all) {
	apply(plugin = "drift.mobile")
}

kotlin {
	jvmToolchain(21)

	compilerOptions {
		allWarningsAsErrors = true
		extraWarnings = true
		progressiveMode = true
	}

	sourceSets.configureEach {
		val base = name.replace(Regex("(Main|Test)$"), "")
		val kind = if (name.endsWith("Test")) "test" else "main"
		kotlin.setSrcDirs(listOf("src/$base/$kind"))
		resources.setSrcDirs(
			listOf(
				if (kind ==
			"test"
				) {
				"src/$base/test-resources"
			} else {
				"src/$base/resources"
			},
			),
		)
	}

	sourceSets.commonTest.dependencies {
		implementation(kotlin("test"))
	}
}

providers.gradleProperty("drift.jvmTarget").orNull?.let { target ->
	tasks.withType<KotlinJvmCompile>().configureEach {
		compilerOptions.jvmTarget.set(JvmTarget.fromTarget(target))
	}
}
