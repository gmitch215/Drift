# Cloudflare Probe Workers

A standalone Gradle build that compiles the Kotlin probes of `scan` into two Cloudflare Workers, so `drift probe cloudflare` can record them on workerd. Drift's own build does not include it, and `./gradlew build` never touches it.

| Directory | Output |
| --- | --- |
| `wasm` | Kotlin/Wasm (`wasmJs`) module with a hand-written Worker entry in `wasm/worker`; `:wasm:stageWorker` writes `wasm/build/worker` |
| `jvm` | the probes as Kotlin/JVM classes, turned into a WasmGC Worker by the [bytebox](https://github.com/gmitch215/bytebox) 1.0.3 Gradle plugin; `:jvm:buildWorker` writes `jvm/build/bytebox/worker` |

`probes.gradle.kts` copies the probe sources from `scan/src/common/main` and `core/src/common/main` into `build/generated/probes` and adds `KotlinBuild`, a `Host` interface with the two members the probes use, and the `bits` helpers. The Workers answer every request with the transcript.

## Build and Record

```sh
../../gradlew -p . :wasm:stageWorker :jvm:buildWorker
(cd jvm/build/bytebox/worker && npm install)
drift probe cloudflare --worker wasm/build/worker --target cloudflare-wasm --out ../../fixtures/atlas
drift probe cloudflare --worker jvm/build/bytebox/worker --target cloudflare-jvm --out ../../fixtures/atlas
```

The build needs JDK 21 for the bytebox side, Node, and `wrangler` 4 on `PATH` or through `npx`. bytebox and TeaVM download from Maven Central and the Gradle plugin portal. Nothing here is part of Drift.

## Refused Probes

TeaVM compiles whole programs, so one unsupported class stops the build, and a WebAssembly trap stops the request. `jvm` handles both without dropping a probe:

- `jvm/build.gradle.kts` rewrites the two `matchAt` lines of the copied `RegexProbes.kt` into a thrown exception, because `java.util.regex.Matcher.useAnchoringBounds` is missing from TeaVM's class library.
- `jvm/src/main/dev/gmitch215/drift/cloudflare/ProbeWorker.kt` lists the two probes that trap on integer division, `kotlin.exceptions.messages` and `kotlin.int.overflow`, and records them as unavailable with the reason.

kotlinx-coroutines does not build through bytebox. No probe in the Kotlin family uses it, so none is refused for that reason.
