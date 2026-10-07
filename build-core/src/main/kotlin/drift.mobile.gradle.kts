plugins {
	id("org.jetbrains.kotlin.multiplatform")
	id("com.android.kotlin.multiplatform.library")
}

kotlin {
	android {
		namespace = "dev.gmitch215.drift.${project.name}"
		compileSdk = 37
		minSdk = 26

		withDeviceTest {
			instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		}
	}

	iosArm64()
	iosSimulatorArm64()

	sourceSets.getByName("androidDeviceTest").dependencies {
		implementation(kotlin("test"))
		implementation("androidx.test:runner:1.7.0")
		implementation("androidx.test.ext:junit:1.3.0")
	}
}
