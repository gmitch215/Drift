import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(21)
    jvm {
        mainRun { mainClass.set("MainKt") }
    }
    linuxX64 {
        binaries.executable()
    }
    js {
        nodejs()
        binaries.executable()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        binaries.executable()
    }
}
