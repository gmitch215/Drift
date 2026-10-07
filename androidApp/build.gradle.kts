plugins {
	id("com.android.application")
	alias(libs.plugins.kotlin.compose)
}

android {
	namespace = "dev.gmitch215.drift.android"
	compileSdk = 37

	defaultConfig {
		applicationId = "dev.gmitch215.drift"
		minSdk = 26
		targetSdk = 36
		versionCode = 1
		versionName = project.version.toString()
	}

	buildTypes {
		release {
			signingConfig = signingConfigs.getByName("debug")
			isMinifyEnabled = false
		}
	}
}

val apkName = "drift-${project.version}-android.apk"

tasks.register<Copy>("packageApk") {
	dependsOn("assembleRelease")
	from(layout.buildDirectory.file("outputs/apk/release/androidApp-release.apk"))
	rename("androidApp-release.apk", apkName)
	into(layout.buildDirectory.dir("distributions"))
}

dependencies {
	implementation(project(":studio"))
	implementation(project(":scan"))
	implementation(project(":host"))
	implementation(libs.androidx.activity.compose)
}
