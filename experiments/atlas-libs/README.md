# Atlas Libraries

A standalone Kotlin Multiplatform project that runs 25 small probes against kotlinx-datetime, kotlinx.serialization (JSON), kotlinx-io and kotlinx-coroutines on four targets and compares the printed transcripts. It uses the `atlas record` transcript format (`@@ probe` headers, `label = value` lines) and is not part of Drift's Gradle build.

Exit 0 means identical transcripts on every target. Exit 1 means some lines differ, which is the expected outcome here: the differences are the data, and the accepted run has 150 of them, all classified. Exit 2 means a target did not build or run. Each differing line is classified in `classification.mjs` as `documented-contract`, `unspecified` or `no-correctness-claim`; only `documented-contract` lines can become an upstream report.

## Targets

| target | runtime |
| --- | --- |
| `jvm` | JDK 21 |
| `linuxX64` | native executable; needs an x86-64 Linux host and `/usr/share/zoneinfo` for time zone probes |
| `js` | Node through Gradle, `@js-joda/timezone` for zones |
| `wasmJs` | Node through Gradle, `@js-joda/timezone` for zones |

Versions are pinned in `gradle.properties`.

## Running

```sh
experiments/run.sh atlas-libs --check
experiments/run.sh atlas-libs
experiments/run.sh atlas-libs --paisley
```

Options go after the name: `--targets jvm,js`, `--out DIR`, `--messages` (also compare exception messages), `-- <gradle args>`. `RUN_LABEL` names the run directory under `experiments/results/atlas-libs/runs/`. The first run downloads Gradle plugins, the Kotlin/Native toolchain, Node and the npm package; the table is printed and written to `compare.md` in the run directory.

## Files

| file | content |
| --- | --- |
| `src/commonMain` | the probes, one file per library, and `Main.kt` |
| `src/linuxX64Main` | the native entry point |
| `src/jsMain`, `src/wasmJsMain` | loads `@js-joda/timezone` |
| `compare.mjs` | reads the transcripts and prints the table of differing lines |
| `classification.mjs` | the class and reason for each differing line |
