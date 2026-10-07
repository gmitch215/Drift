import dev.gmitch215.drift.build.EmbedFixtures
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

plugins {
	id("drift.layout")
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.compose.multiplatform)
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

kotlin {
	jvm()

	@OptIn(ExperimentalWasmDsl::class)
	wasmJs {
		outputModuleName = "studio"
		browser {
			testTask {
				useKarma {
					when (providers.gradleProperty("drift.browser").orNull) {
						"firefox" -> useFirefoxHeadless()
						else -> useChromeHeadless()
					}
				}
			}
		}
		binaries.executable()
	}

	sourceSets {
		commonMain {
			kotlin.srcDir(embedFixtures)
			kotlin.srcDir(embedAtlas)
		}

		commonMain.dependencies {
			implementation(project(":core"))
			implementation(project(":host"))
			implementation(project(":scan"))
			implementation(compose.runtime)
			implementation(compose.foundation)
			implementation(compose.components.resources)
		}

		jvmMain.dependencies {
			implementation(compose.desktop.currentOs)
		}

		jvmTest.dependencies {
			@OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
			implementation(compose.uiTest)
		}
	}
}

if (providers.gradleProperty("drift.mobile").orNull == "true") {
	kotlin {
		targets.withType<KotlinNativeTarget>().matching { it.konanTarget.family == Family.IOS }
			.configureEach {
				binaries.framework {
					baseName = "Studio"
					isStatic = true
				}
			}
	}
}

compose.resources {
	customDirectory(
		"commonMain",
		provider {
		layout.projectDirectory.dir("src/common/composeResources")
	},
	)
}

val appVersion = project.version.toString().substringBefore("-")

compose.desktop {
	application {
		mainClass = "dev.gmitch215.drift.studio.ui.MainKt"

		nativeDistributions {
			targetFormats(
				TargetFormat.Dmg,
				TargetFormat.Pkg,
				TargetFormat.Msi,
				TargetFormat.Exe,
				TargetFormat.Deb,
				TargetFormat.Rpm,
			)
			packageName = "Drift"
			packageVersion = appVersion
			vendor = "Gregory Mitchell"
			description = "Drift Studio: explore how Kotlin behaves across platforms"
			copyright = "Copyright (c) 2026 Gregory Mitchell"
			val os = System.getProperty("os.name")
			when {
				os == "Linux" -> licenseFile.set(rootProject.file("LICENSE"))

				os.startsWith("Windows") ->
					licenseFile.set(project.file("src/jvm/packaging/LICENSE.rtf"))
			}
			modules("java.instrument", "java.management", "jdk.unsupported")

			macOS {
				bundleID = "dev.gmitch215.drift"
				appCategory = "public.app-category.developer-tools"
				iconFile.set(project.file("src/jvm/packaging/drift.icns"))
			}
			windows {
				iconFile.set(project.file("src/jvm/packaging/drift.ico"))
				menuGroup = "Drift"
				shortcut = true
				dirChooser = true
				perUserInstall = false
				upgradeUuid = "83691580-f268-43b3-84bb-b720ebb8ae69"
			}
			linux {
				iconFile.set(project.file("src/jvm/packaging/drift.png"))
				packageName = "drift"
				appCategory = "devel"
				menuGroup = "Development"
				rpmLicenseType = "MIT"
				shortcut = true
			}
		}
	}
}

val packageAppImage = tasks.register<Exec>("packageAppImage") {
	val binaries = layout.buildDirectory.dir("compose/binaries/main")
	val script = layout.projectDirectory.file("src/jvm/packaging/appimage.sh")
	val image = binaries.map { it.file("appimage/Drift-$appVersion.AppImage") }
	dependsOn("createDistributable")
	onlyIf { System.getProperty("os.name") == "Linux" }
	inputs.dir(binaries.map { it.dir("app/Drift") })
	inputs.file(script)
	outputs.file(image)
	commandLine(
		"sh",
		script.asFile.path,
		binaries.get().dir("app/Drift").asFile.path,
		image.get().asFile.path,
	)
}
