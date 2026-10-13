plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    jvm()
    wasmJs {
        browser()
    }
    js(IR) {
        nodejs()
    }
    androidTarget()
    jvmToolchain(21)
    compilerOptions {
        languageVersion = KotlinVersion.KOTLIN_2_3
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation("com.example:literal:1.2.3")
        }
    }
}
