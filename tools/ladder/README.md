# Ladder

Builds the Kotlin probe transcripts for older compilers (1.3.72 to 2.4.20) from the probe source text, one standalone compiler per version.

- `LadderProbes` reads the `*Probes.kt` catalog files and writes one Kotlin file per probe, plus `Core.kt` and a `Main.kt` that prints the `atlas record` transcript format.
- `ladder.sh compile|run|assemble|separate VERSION TARGET` drives one compiler. `compile` drops every probe file that fails to compile and records the first error, `assemble` writes `transcript.txt`, `separate` compiles each probe alone and compares the dropped set.
- `batch.sh` runs the three stages in a container on Linux, `mac.sh` runs them on the Mac (JDK 21 first, one fallback JDK), `fetch.sh` downloads the compiler and Native bundles, `collect.sh` copies finished transcripts out, `axis.py` lists where each probe changes along the version axis.
- Targets: `jvm`, `linux`, `macos`, `js-legacy`, `js-ir`. Wasm has no standalone route (`kotlinc-js -Xwasm` 2.0.21 fails with `jsFrontEndResult has not been initialized`).
