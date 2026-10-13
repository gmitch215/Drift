import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()

    linuxArm64 {
        binaries.executable {
            entryPoint = "dev.gmitch215.drift.cases.main"
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        binaries.executable()
    }

    sourceSets {
        commonMain {
            kotlin.setSrcDirs(listOf("src/common/main"))
        }
    }
}
