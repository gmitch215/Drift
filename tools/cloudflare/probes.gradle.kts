val repo = rootDir.resolve("../..")
val scan = "dev/gmitch215/drift/scan/probe"
val core = "dev/gmitch215/drift"

val generatedBuild = layout.buildDirectory.dir("generated/kotlinBuild")

val writeKotlinBuild = tasks.register("writeKotlinBuild") {
    val version = providers.gradleProperty("kotlinVersion").get()
    val out = generatedBuild
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val file = out.get().file("dev/gmitch215/drift/scan/KotlinBuild.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package dev.gmitch215.drift.scan\n\nobject KotlinBuild {\n" +
                "\tconst val VERSION = \"$version\"\n}\n",
        )
    }
}

tasks.register<Sync>("syncProbes") {
    into(layout.buildDirectory.dir("generated/probes"))
    from(repo.resolve("scan/src/common/main")) {
        include("$scan/Probe.kt", "$scan/kotlin/**")
    }
    from(repo.resolve("core/src/common/main")) {
        include("$core/model/ProbeResult.kt", "$core/hash/Sha256.kt", "$core/json/**")
    }
    from(rootDir.resolve("shared/src/main"))
    from(writeKotlinBuild)
}
