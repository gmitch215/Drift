import dev.gmitch215.drift.build.EmbedDriftVersion
import dev.gmitch215.drift.build.EmbedFixtures
import dev.gmitch215.drift.build.EmbedRules

plugins {
    id("drift.kmp")
}

val embedFixtures = tasks.register<EmbedFixtures>("embedFixtures") {
    fixtures = rootProject.layout.projectDirectory.dir("fixtures/transcripts")
    outputDir = layout.buildDirectory.dir("generated/fixtures")
}

val embedRunFixtures = tasks.register<EmbedFixtures>("embedRunFixtures") {
    fixtures = rootProject.layout.projectDirectory.dir("fixtures/drangler")
    objectName = "RunFixtures"
    outputDir = layout.buildDirectory.dir("generated/runFixtures")
}

val embedLabFixtures = tasks.register<EmbedFixtures>("embedLabFixtures") {
    fixtures = rootProject.layout.projectDirectory.dir("fixtures/lab")
    objectName = "LabFixtures"
    outputDir = layout.buildDirectory.dir("generated/labFixtures")
}

val embedCiFixtures = tasks.register<EmbedFixtures>("embedCiFixtures") {
    fixtures = rootProject.layout.projectDirectory.dir("fixtures/ci")
    objectName = "CiFixtures"
    outputDir = layout.buildDirectory.dir("generated/ciFixtures")
}

val embedFlagshipBFixtures = tasks.register<EmbedFixtures>("embedFlagshipBFixtures") {
    fixtures = rootProject.layout.projectDirectory.dir("fixtures/cases/b")
    objectName = "FlagshipBFixtures"
    outputDir = layout.buildDirectory.dir("generated/flagshipBFixtures")
}

val embedTriageFixtures = tasks.register<EmbedFixtures>("embedTriageFixtures") {
    fixtures = rootProject.layout.projectDirectory.dir("fixtures/triage")
    objectName = "TriageFixtures"
    outputDir = layout.buildDirectory.dir("generated/triageFixtures")
}

val stageFlagshipA = tasks.register<Sync>("stageFlagshipA") {
    from(rootProject.layout.projectDirectory.dir("fixtures/cases/a")) {
        include("case/**", "ruled-out.yml", "vacuous-green.txt")
    }
    into(layout.buildDirectory.dir("flagshipA"))
}

val embedFlagshipAFixtures = tasks.register<EmbedFixtures>("embedFlagshipAFixtures") {
    dependsOn(stageFlagshipA)
    fixtures = layout.buildDirectory.dir("flagshipA")
    objectName = "FlagshipAFixtures"
    outputDir = layout.buildDirectory.dir("generated/flagshipAFixtures")
}

val stageFlagshipC = tasks.register<Sync>("stageFlagshipC") {
    from(rootProject.layout.projectDirectory.dir("fixtures/cases/c")) {
        include("sealed.json", "results.json", "INC-13.driftcase")
    }
    into(layout.buildDirectory.dir("flagshipC"))
}

val embedFlagshipCFixtures = tasks.register<EmbedFixtures>("embedFlagshipCFixtures") {
    dependsOn(stageFlagshipC)
    fixtures = layout.buildDirectory.dir("flagshipC")
    objectName = "FlagshipCFixtures"
    outputDir = layout.buildDirectory.dir("generated/flagshipCFixtures")
}

val stageFlagshipD = tasks.register<Sync>("stageFlagshipD") {
    from(rootProject.layout.projectDirectory.dir("fixtures/cases/d")) {
        include(
            "selection.json",
            "outcomes.json",
            "prediction.json",
            "prediction.sha256",
            "score-protocol.json",
            "score.json",
        )
    }
    into(layout.buildDirectory.dir("flagshipD"))
}

val embedFlagshipDFixtures = tasks.register<EmbedFixtures>("embedFlagshipDFixtures") {
    dependsOn(stageFlagshipD)
    fixtures = layout.buildDirectory.dir("flagshipD")
    objectName = "FlagshipDFixtures"
    outputDir = layout.buildDirectory.dir("generated/flagshipDFixtures")
}

val embedRules = tasks.register<EmbedRules>("embedRules") {
    rules = rootProject.layout.projectDirectory.dir("rules")
    outputDir = layout.buildDirectory.dir("generated/rules")
}

val embedDriftVersion = tasks.register<EmbedDriftVersion>("embedDriftVersion") {
    version = project.version.toString()
    outputDir = layout.buildDirectory.dir("generated/driftVersion")
}

kotlin {
    sourceSets {
        commonMain {
            kotlin.srcDir(embedRules)
            kotlin.srcDir(embedDriftVersion)
        }

        commonTest {
            kotlin.srcDir(embedFixtures)
            kotlin.srcDir(embedRunFixtures)
            kotlin.srcDir(embedLabFixtures)
            kotlin.srcDir(embedCiFixtures)
            kotlin.srcDir(embedTriageFixtures)
            kotlin.srcDir(embedFlagshipBFixtures)
            kotlin.srcDir(embedFlagshipAFixtures)
            kotlin.srcDir(embedFlagshipCFixtures)
            kotlin.srcDir(embedFlagshipDFixtures)
        }
    }
}
