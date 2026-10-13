import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
}

apply(from = "../probes.gradle.kts")

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        binaries.executable()
    }

    sourceSets {
        wasmJsMain {
            kotlin.setSrcDirs(listOf("src/wasmJs/main", tasks.named("syncProbes")))
        }
    }
}

val stageWorker = tasks.register<Copy>("stageWorker") {
    dependsOn("wasmJsProductionExecutableCompileSync")
    val out = layout.buildDirectory.dir("compileSync/wasmJs/main/productionExecutable/optimized")
    from(out) {
        include("*.wasm", "*.import-object.mjs", "*.js-builtins.mjs")
        into("src")
        rename { name ->
            when {
                name.endsWith(".wasm") -> "kotlin.wasm"
                name.endsWith(".import-object.mjs") -> "kotlin.import-object.mjs"
                else -> name
            }
        }
    }
    from(layout.projectDirectory.dir("worker"))
    into(layout.buildDirectory.dir("worker"))
}
