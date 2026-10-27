pluginManagement {
    plugins { kotlin("multiplatform") version "2.4.20" }
    repositories { gradlePluginPortal(); mavenCentral() }
}

dependencyResolutionManagement { repositories { mavenCentral() } }

rootProject.name = "js-long-template"
