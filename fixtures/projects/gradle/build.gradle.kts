plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.library) apply false
    id("com.android.application") version "9.0.0" apply false
    id("org.springframework.boot") version "3.3.4" apply false
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks {
    "wrapper" {
        doLast { println("done") }
    }
}

tasks.register("legacy") {
    doLast {
        exec { commandLine("true") }
    }
}

// kotlin("test") is a dependency, not a plugin
dependencies {
    testImplementation(kotlin("test"))
}
