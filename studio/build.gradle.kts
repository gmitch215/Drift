import dev.gmitch215.drift.build.EmbedFixtures
import dev.gmitch215.drift.build.VerifyNoticesTask
import org.gradle.api.tasks.AbstractCopyTask
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

plugins {
    id("drift.layout")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

val composeVersion = libs.versions.compose.get()

val embedFixtures = tasks.register<EmbedFixtures>("embedFixtures") {
    fixtures = rootProject.layout.projectDirectory.dir("fixtures/drangler/capsules")
    outputDir = layout.buildDirectory.dir("generated/fixtures")
}

val embedAtlas = tasks.register<EmbedFixtures>("embedAtlas") {
    fixtures = rootProject.layout.projectDirectory.dir("fixtures/atlas")
    objectName = "AtlasFixtures"
    outputDir = layout.buildDirectory.dir("generated/atlas")
}

val embedUpgrade = tasks.register<EmbedFixtures>("embedUpgrade") {
    fixtures = rootProject.layout.projectDirectory.dir("fixtures/upgrade-check")
    objectName = "UpgradeFixtures"
    outputDir = layout.buildDirectory.dir("generated/upgrade")
}

val embedSet = tasks.register<EmbedFixtures>("embedSet") {
    fixtures = rootProject.layout.projectDirectory.dir("fixtures/set")
    objectName = "SetFixtures"
    outputDir = layout.buildDirectory.dir("generated/set")
}

val stageSampleLogs = tasks.register<Sync>("stageSampleLogs") {
    from(rootProject.layout.projectDirectory.dir("fixtures/drangler/logs")) {
        include("36702683742.log", "36995781138.log")
    }
    into(layout.buildDirectory.dir("sample-logs"))
}

val embedSampleLogs = tasks.register<EmbedFixtures>("embedSampleLogs") {
    dependsOn(stageSampleLogs)
    fixtures = layout.buildDirectory.dir("sample-logs")
    objectName = "SampleLogFixtures"
    outputDir = layout.buildDirectory.dir("generated/sample-logs")
}

tasks.withType<AbstractCopyTask>()
    .matching { it.name == "wasmJsBrowserDistribution" }
    .configureEach {
        from(rootProject.layout.projectDirectory.dir("notices"))
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
            kotlin.srcDir(embedUpgrade)
            kotlin.srcDir(embedSet)
            kotlin.srcDir(embedSampleLogs)
        }

        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":host"))
            implementation(project(":scan"))
            implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
            implementation("org.jetbrains.compose.foundation:foundation:$composeVersion")
            implementation("org.jetbrains.compose.components:components-resources:$composeVersion")
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }

        jvmTest.dependencies {
            implementation("org.jetbrains.compose.ui:ui-test-junit4:$composeVersion")
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

val caseMime = "application/x-driftcase"

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
            appResourcesRootDir.set(rootProject.layout.projectDirectory.dir("notices"))
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
                fileAssociation(
                    caseMime,
                    "driftcase",
                    "Drift Case",
                    project.file("src/jvm/packaging/drift-case.icns"),
                )
            }
            windows {
                iconFile.set(project.file("src/jvm/packaging/drift.ico"))
                fileAssociation(
                    caseMime,
                    "driftcase",
                    "Drift Case",
                    project.file("src/jvm/packaging/drift-case.ico"),
                )
                menuGroup = "Drift"
                shortcut = true
                dirChooser = true
                perUserInstall = false
                upgradeUuid = "83691580-f268-43b3-84bb-b720ebb8ae69"
            }
            linux {
                iconFile.set(project.file("src/jvm/packaging/drift.png"))
                fileAssociation(
                    caseMime,
                    "driftcase",
                    "Drift Case",
                    project.file("src/jvm/packaging/drift-case.png"),
                )
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

val verifyNotices = tasks.register<VerifyNoticesTask>("verifyNotices") {
    notices.set(rootProject.layout.projectDirectory.file("notices/THIRD-PARTY-NOTICES.txt"))
    inputs.file(
        layout.projectDirectory.file("src/common/composeResources/font/jetbrains_mono_regular.ttf"),
    )
    val graphs = listOf(
        project.configurations.getByName("jvmRuntimeClasspath"),
        project.configurations.getByName("wasmJsRuntimeClasspath"),
        project.configurations.detachedConfiguration(
            *listOf("linux-x64", "windows-x64", "macos-arm64")
                .map {
                    project.dependencies.create(
                        "org.jetbrains.compose.desktop:desktop-jvm-$it:$composeVersion",
                    )
                }
                .toTypedArray(),
        ),
    )
    configurations.set(graphs)
    inputs.files(graphs)
}

tasks.named("check") { dependsOn(verifyNotices) }
