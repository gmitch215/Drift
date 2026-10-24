import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("drift.kmp")
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
kotlin {
    applyDefaultHierarchyTemplate {
        common {
            group("native") {
                group("posix") {
                    withLinux()
                    withMacos()
                }
            }
        }
    }
    targets.withType<KotlinNativeTarget>().matching {
        it.name.startsWith("linux") || it.name.startsWith("macos")
    }.configureEach {
        compilations.getByName("main").cinterops.create("spawn") {
            definitionFile.set(file("src/posix/cinterop/spawn.def"))
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
        }
        jvmMain.dependencies {
            implementation(libs.oshi.core)
            runtimeOnly(libs.slf4j.nop)
        }
    }
}
