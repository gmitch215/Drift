plugins {
    id("dev.gmitch215.bytebox")
    kotlin("jvm")
}

apply(from = "../probes.gradle.kts")

kotlin {
    jvmToolchain(21)

    sourceSets.named("main") {
        kotlin.setSrcDirs(listOf("src/main", tasks.named("syncProbes")))
    }
}

bytebox {
    handlerClass = "dev.gmitch215.drift.cloudflare.ProbeWorker"

    wrangler {
        name = "drift-probe-jvm"
        compatibilityDate = "2026-08-22"
    }
}

val refusal =
    "refused by TeaVM: java.util.regex.Matcher.useAnchoringBounds is not in its class library"

tasks.named<Sync>("syncProbes") {
    inputs.property("refusal", refusal)

    filesMatching("**/RegexProbes.kt") {
        filter { line ->
            when {
                line.startsWith("\t\tline(\"matchAt-lookbehind\"") ->
                    "\t\tthrow UnsupportedOperationException(\"$refusal\")"
                line.startsWith("\t\tline(\"matchAt-caret\"") -> ""
                else -> line
            }
        }
    }
}
