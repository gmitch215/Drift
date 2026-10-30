pluginManagement {
    plugins { kotlin("multiplatform") version "2.4.21" }
    repositories { gradlePluginPortal(); mavenCentral() }
}

dependencyResolutionManagement { repositories { mavenCentral() } }

rootProject.name = "box-tests"
