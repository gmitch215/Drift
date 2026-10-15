plugins {
    id("com.android.application")
}

android {
    namespace = "demo.app"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = 36
    }
}
