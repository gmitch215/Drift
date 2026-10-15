import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation

plugins {
    id("drift.layout")
}

kotlin {
    jvm {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        mainRun {
            mainClass = "dev.gmitch215.drift.tools.MainKt"
        }
    }

    sourceSets.jvmMain.dependencies {
        implementation(project(":core"))
        implementation(libs.snakeyaml.engine)
    }
}

val corpusMain =
    kotlin.targets.getByName("jvm").compilations.getByName("main") as KotlinJvmCompilation
val corpusRepo = rootProject.layout.projectDirectory
val corpusDir = layout.buildDirectory.dir("corpus")
val corpusFile = providers.gradleProperty("drift.corpus").orElse(
    corpusDir.map { it.file("corpus.json.zst").asFile.absolutePath },
)

fun JavaExec.corpus(vararg arguments: String) {
    group = "triage"
    classpath = files(corpusMain.output.allOutputs, corpusMain.runtimeDependencyFiles)
    mainClass = "dev.gmitch215.drift.tools.corpus.CorpusMainKt"
    maxHeapSize = "3g"
    dependsOn("jvmMainClasses")
    args = arguments.toList()
}

tasks.register<JavaExec>("buildCorpus") {
    description = "Fetches the manifest repos with gh and glab into a zstd corpus in build/corpus"
    corpus(
        "build",
        corpusRepo.file("triage/corpus.yml").asFile.absolutePath,
        corpusDir.get().dir("work").asFile.absolutePath,
        corpusDir.get().asFile.absolutePath,
    )
}

val downloadCorpus = tasks.register<JavaExec>("downloadCorpus") {
    description = "Downloads the corpus release asset by its pinned SHA-256 into build/corpus"
    corpus(
        "fetch",
        corpusRepo.file("triage/corpus.yml").asFile.absolutePath,
        corpusDir.get().file("corpus.json.zst").asFile.absolutePath,
    )
}

tasks.register<JavaExec>("evalTriage") {
    description = "Scores drift triage on the corpus; -Pdrift.corpus=<file> skips the download"
    if (!providers.gradleProperty("drift.corpus").isPresent) dependsOn(downloadCorpus)
    corpus(
        "eval",
        corpusRepo.file("triage/corpus.yml").asFile.absolutePath,
        corpusFile.get(),
        corpusDir.get().file("eval.txt").asFile.absolutePath,
        corpusDir.get().file("claims.tsv").asFile.absolutePath,
    )
}

tasks.register<JavaExec>("imageTags") {
    group = "tools"
    description = "Fetches Docker Hub tags into core's ImageTags.kt; run by hand, not part of build"
    classpath = files(corpusMain.output.allOutputs, corpusMain.runtimeDependencyFiles)
    mainClass = "dev.gmitch215.drift.tools.MainKt"
    dependsOn("jvmMainClasses")
    args = listOf(
        "image-tags",
        rootProject.layout.projectDirectory
            .file("core/src/common/main/dev/gmitch215/drift/lab/ImageTags.kt").asFile.absolutePath,
    )
}
