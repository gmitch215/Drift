import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

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
