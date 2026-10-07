Draft for human review. Not filed.

# kotlin.double.nan-bits

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: Which bit patterns do NaN values have?

Classification now: unclassified (was unclassified); cause letters: d g; basis: inferred.

Measured: Kotlin 2.4.20 on 16 columns (android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, jvm 21 arm64 in a container, linux x64, linux arm64 and linux-ubuntu Native, mingw Native under wine); Kotlin 2.5.0-Beta1 on jvm, macos, wasm, android and ios. Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
val zero = "0".toDouble()
val payload = Double.fromBits(0x7ff0000000000001)
line("0/0-raw", bits(0.0 / zero))
line("neg-nan-raw", bits(-Double.NaN))
line("sqrt-neg-raw", bits(sqrt(-1.0)))
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `0/0-raw`
  - `7ff8000000000000`: android+jvm+wasm+jvm-21-arm64, ios+macos+linux-arm64
  - `fff8000000000000`: wasm-chromium+wasm-firefox+wasm-webkit, jvm-17+jvm-21+jvm-25, linux+linux-ubuntu+mingw-wine
- `neg-nan-raw`
  - `fff8000000000000`: android+jvm+wasm+jvm-21-arm64, wasm-chromium+wasm-firefox+wasm-webkit, jvm-17+jvm-21+jvm-25
  - `7ff8000000000000`: ios+macos+linux-arm64, linux+linux-ubuntu+mingw-wine
- `sqrt-neg-raw`
  - `7ff8000000000000`: android+jvm+wasm+jvm-21-arm64, ios+macos+linux-arm64, wasm-chromium+wasm-firefox+wasm-webkit
  - `fff8000000000000`: jvm-17+jvm-21+jvm-25, linux+linux-ubuntu+mingw-wine

On 2.5.0-Beta1 the cells group as: jvm+wasm+android; macos+ios.

Docs read (FULL text):
- https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.2.3 : "IEEE 754 allows multiple distinct NaN values for each of its binary32 and binary64 floating-point formats."
- https://webassembly.github.io/spec/core/exec/numerics.html : "Some operators are non-deterministic, because they can return one of several possible results (such as different NaN values)."
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/to-raw-bits.html : "preserving NaN values exact layout"

Existing tracker issues (read through the YouTrack REST API on 2026-10-07; nothing was posted): KT-53258 In Progress.

What is known: Three lines differ at 2.4.20. 0/0-raw follows the CPU in every column (x86 fff8..., arm64 7ff8...; the browsers on x86 print fff8 like x86 JVM and Linux, Node on arm64 prints 7ff8; checked for Wasm with a hand-assembled f64.div module on Node arm64 and Node x64). sqrt-neg-raw follows the CPU on JVM and Native but not in the three x86 browsers (7ff8). neg-nan-raw follows the Kotlin target: Native 7ff8 on every host, JVM, Android and Wasm fff8 on every host; no FULL doc explains it. The source is the constant expression -Double.NaN; whether the compiler folds it was not checked. The ladder fixtures show Native neg-nan-raw fff8 on 1.6.21 and 7ff8 from 1.9.25 on macos and linux.

What is NOT known: the intended behavior where no page read states one; whether the divergent column is a defect or by design; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device of the same target differs (one device per column); whether the displayed snippet and the executed probe body are identical (they are two hand-written copies; the ladder agent re-compiled the displayed text and matched 37 of 38 probes on JVM and macOS Native).
