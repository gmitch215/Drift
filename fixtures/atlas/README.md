Atlas transcripts of the Kotlin probes, one file per column. A column is a target label and a
Kotlin version: the files in this directory are Kotlin 2.4.20, `2.5.0-beta1/` holds Kotlin
2.5.0-Beta1. Versions are never merged. This directory is the only one embedded in Studio and the
dataset; measured transcripts that are left out are in `fixtures/atlas-extra`.

| file | column | recorded on |
| --- | --- | --- |
| `jvm.txt` | `jvm` | Temurin 21, macOS, Apple Silicon |
| `jvm-17.txt`, `jvm-21.txt`, `jvm-25.txt` | `jvm-17`, `jvm-21`, `jvm-25` | Temurin 17.0.20.1, 21.0.12.1 and 25.0.4.1, x86-64 Linux containers, the same classes (jvmTarget 17) on each JDK |
| `linux.txt` | `linux` | Kotlin/Native linuxX64 cross-linked on macOS, run in a debian:bookworm-slim container (glibc 2.36), x86-64 |
| `linux-arm64.txt` | `linux-arm64` | Kotlin/Native linuxArm64 cross-linked on macOS, run in an arm64 debian:bookworm-slim container |
| `macos.txt` | `macos` | Kotlin/Native macosArm64, Apple Silicon |
| `wasm.txt` | `wasm` | wasmJs on Node 26.8.2, Apple Silicon |
| `wasm-chromium.txt`, `wasm-firefox.txt`, `wasm-webkit.txt` | `wasm-chromium`, `wasm-firefox`, `wasm-webkit` | the Studio web build in three browser engines, x86-64 Linux container |
| `android.txt` | `android` | arm64 emulator, API 37 |
| `ios.txt` | `ios` | iPhone 17 Pro simulator, iOS 26.5, arm64 |

`jvm` keeps its name because it is the committed column the goldens and docs refer to; the JDK 21
build on x86-64 is `jvm-21`. Both are JDK 21 and differ on three probes that follow the CPU
(`kotlin.double.nan-bits`, `kotlin.math.pow-special-cases`, `kotlin.math.transcendental-bits`).
`jvm-21-arm64`, a JDK 21 arm64 container, was byte-identical to `jvm` and is not duplicated here.
On the `jvm-17` column `removeFirst` on an empty list reports `NoSuchMethodError`: the probe was
compiled against the JDK 21 API and run on 17.

`wasm-chromium.txt`, `wasm-firefox.txt` and `wasm-webkit.txt` are the same Kotlin/Wasm Studio build
loaded in three browser engines through `tools/browsers/run.mjs` (Playwright 1.63.0), recorded
2026-10-07 on one x86-64 Linux host in a container:

| file | browser | engine |
| --- | --- | --- |
| `wasm-chromium.txt` | Chromium 153.0.8010.12 | V8 |
| `wasm-firefox.txt` | Firefox 155.0 | SpiderMonkey |
| `wasm-webkit.txt` | WebKit 26.6 (Playwright build) | JavaScriptCore |

The runner reads the transcript the page produced for the this-device column and rewrites only the
`kotlin.target` header line. The three browsers agree on all 38 probes. They differ from `wasm.txt`
on `kotlin.double.nan-bits`: x86-64 prints the sign bit set for `0.0 / 0.0` (`fff8000000000000`),
Apple Silicon does not. A hand-written WebAssembly `f64.div` run on Node shows the same split
between the two machines, so the difference follows the host CPU in this data, not the engine.

`drift atlas record --out DIR --target LABEL` writes `DIR/LABEL.txt`; the atlas workflow records
the CI columns the same way.

Where a Native executable was linked changes three lines. A release executable linked on macOS
prints `3ff8eb245cbee3a5` for `tan1` and `...7365` for `asin-half` and `acos-half` in
`kotlin.math.transcendental-bits`; one linked on Ubuntu, and any debug build, prints `...a6` and
`...7366`. The `linux` and `linux-arm64` columns here are the macOS-linked release builds; the
Ubuntu-linked transcripts are in `fixtures/atlas-variants`, whose README has the measurements. The
atlas workflow links on Ubuntu runners, so a run would record the `...a6` values.
