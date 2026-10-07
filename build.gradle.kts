plugins {
	alias(libs.plugins.spotless)
	id("drift.quality")
	id("drift.docs-root")
	id("drift.packaging")
	id("drift.atlas")
}

val ktlintOverrides = mapOf(
	"ktlint_standard_indent" to "disabled",
	"ktlint_function_naming_ignore_when_annotated_with" to "Composable",
)

spotless {
	kotlin {
		target("*/src/**/*.kt")
		ktlint(libs.versions.ktlint.get())
			.setEditorConfigPath("$rootDir/.editorconfig")
			.editorConfigOverride(ktlintOverrides)
	}
	kotlinGradle {
		target("*.kts", "*/*.kts", "*/src/**/*.kts")
		ktlint(libs.versions.ktlint.get())
			.setEditorConfigPath("$rootDir/.editorconfig")
			.editorConfigOverride(ktlintOverrides)
	}
}
