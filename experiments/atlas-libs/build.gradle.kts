import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

val datetimeVersion = providers.gradleProperty("datetimeVersion").get()
val serializationVersion = providers.gradleProperty("serializationVersion").get()
val ioVersion = providers.gradleProperty("ioVersion").get()
val coroutinesVersion = providers.gradleProperty("coroutinesVersion").get()
val jsJodaTimezoneVersion = providers.gradleProperty("jsJodaTimezoneVersion").get()

kotlin {
    jvmToolchain(21)
    jvm {
        mainRun { mainClass.set("atlaslibs.MainKt") }
    }
    linuxX64 {
        binaries.executable { entryPoint = "atlaslibs.nativeMain" }
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

    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:$datetimeVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-io-core:$ioVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
        }
        jsMain.dependencies {
            implementation(npm("@js-joda/timezone", jsJodaTimezoneVersion))
        }
        wasmJsMain.dependencies {
            implementation(npm("@js-joda/timezone", jsJodaTimezoneVersion))
        }
    }
}
