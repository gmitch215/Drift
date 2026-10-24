rootProject.name = "demo"

pluginManagement {
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
    }
}

include(":shared", ":androidApp")
