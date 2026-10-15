Draft for human review. Not filed.

# kotlin.double.nan-bits

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: Which bit patterns do NaN values have?

Classification now: unclassified (was unclassified); cause letters: b, d, g; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64); Kotlin 2.5.0-Beta1 on android, ios, jvm, macos and wasm (fixtures/atlas/2.5.0-beta1) and on js-ir (fixtures/atlas-ladder/2.5.0-beta1, the standalone compiler). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM 0.15.0), so its Java classes are not a JDK. The rows also carry the transcripts in fixtures/atlas-extra (jvm-21-arm64, linux-ubuntu, mingw-wine). Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
val zero = "0".toDouble()
val payload = Double.fromBits(0x7ff0000000000001)
line("0/0-raw", bits(0.0 / zero))
line("neg-nan-raw", bits(-Double.NaN))
line("sqrt-neg-raw", bits(sqrt(-1.0)))
line("float-payload-raw", bits(Float.fromBits(0x7f800001)))
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `0/0-raw`
  - `7ff8000000000000`: android+cloudflare-jvm+cloudflare-wasm+ios+js-ir+jvm-21-arm64+jvm+linux-arm64+macos+wasm
  - `fff8000000000000`: jvm-17+jvm-21+jvm-25+linux-ubuntu+linux+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit
- `neg-nan-raw`
  - `fff8000000000000`: android+cloudflare-jvm+cloudflare-wasm+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `7ff8000000000000`: ios+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
- `sqrt-neg-raw`
  - `7ff8000000000000`: android+cloudflare-jvm+cloudflare-wasm+ios+js-ir+jvm-21-arm64+jvm+linux-arm64+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `fff8000000000000`: jvm-17+jvm-21+jvm-25+linux-ubuntu+linux+mingw-wine
- `float-payload-raw`
  - `7f800001`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `7fc00001`: js-ir

On 2.5.0-Beta1 (android, ios, jvm, macos and wasm from fixtures/atlas/2.5.0-beta1, js-ir from fixtures/atlas-ladder/2.5.0-beta1) the cells group as: android+jvm+wasm; ios+macos; js-ir.

Docs read (FULL text):
- https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.2.3 : "IEEE 754 allows multiple distinct NaN values for each of its binary32 and binary64 floating-point formats."
- https://webassembly.github.io/spec/core/exec/numerics.html : "Some operators are non-deterministic, because they can return one of several possible results (such as different NaN values)."
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/to-raw-bits.html : "preserving NaN values exact layout"
- https://tc39.es/ecma262/#sec-ecmascript-language-types-number-type : "are represented in ECMAScript as a single special NaN value"

Existing tracker issues (state read through the YouTrack REST API and the GitHub API on 2026-10-15; nothing was posted): KT-53258 In Progress.

What is known: Four lines differ at 2.4.20. 0/0-raw follows the CPU in every column (x86 fff8..., arm64 7ff8...; the browsers on x86 print fff8 like x86 JVM and Linux, Node on arm64 prints 7ff8; checked for Wasm with a hand-assembled f64.div module on Node arm64 and Node x64). sqrt-neg-raw follows the CPU on JVM and Native but not in the three x86 browsers (7ff8), which the Wasm NaN rule allows. neg-nan-raw follows the Kotlin target: Native 7ff8 on every host, JVM, Android and Wasm fff8 on every host; no page explains it, and the Wasm fneg rule (a deterministic sign flip) gives fff8 as the JVM does. The source is the constant expression -Double.NaN; whether the compiler folds it was not checked. float-payload-raw differs only on js-ir (7fc00001), which ECMAScript's single NaN value allows. The ladder fixtures show Native neg-nan-raw fff8 on 1.6.21 and 7ff8 from 1.9.25 on macos and linux. cloudflare-wasm prints what wasm prints; cloudflare-jvm prints what android, jvm, wasm print.

What is NOT known: whether the divergent column is a defect or by design where no page read states the intended behavior; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device, runtime or workerd release of the same target differs (one run per cell); whether a TeaVM or bytebox release other than 0.15.0 and 1.0.3 changes the cloudflare-jvm column; whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
