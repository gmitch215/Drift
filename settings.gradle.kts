pluginManagement {
	includeBuild("build-core")
	repositories {
		gradlePluginPortal()
		google {
			content {
				includeGroupAndSubgroups("androidx")
				includeGroupAndSubgroups("com.android")
				includeGroupAndSubgroups("com.google")
			}
		}
	}
}

plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
	repositories {
		mavenCentral()
		google {
			content {
				includeGroupAndSubgroups("androidx")
				includeGroupAndSubgroups("com.android")
				includeGroupAndSubgroups("com.google")
			}
		}
	}
}

rootProject.name = "Drift"

include("host", "core", "scan", "cli", "studio", "tools", "bench")

if (providers.gradleProperty("drift.mobile").orNull == "true") {
	include("androidApp")
}
