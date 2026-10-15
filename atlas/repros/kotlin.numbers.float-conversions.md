Draft for human review. Not filed.

# kotlin.numbers.float-conversions

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: How do out-of-range floating point conversions behave?

Classification now: unclassified (was documented); cause letters: c, e, g; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64); Kotlin 2.5.0-Beta1 on android, ios, jvm, macos and wasm (fixtures/atlas/2.5.0-beta1) and on js-ir (fixtures/atlas-ladder/2.5.0-beta1, the standalone compiler). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM 0.15.0), so its Java classes are not a JDK. The rows also carry the transcripts in fixtures/atlas-extra (jvm-21-arm64, linux-ubuntu, mingw-wine). Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
line("long-max-double", Long.MAX_VALUE.toDouble())
line("long-max-float", Long.MAX_VALUE.toFloat())
line("int-max-float", Int.MAX_VALUE.toFloat())
line("ulong-max-double", ULong.MAX_VALUE.toDouble())
line("1e40-float", 1e40.toFloat())
line("1e-50-float", 1e-50.toFloat())
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `1e40-float`
  - `Infinity`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `1e+40`: js-ir
- `1e-50-float`
  - `0.0`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `1e-50`: js-ir
- `long-max-double`
  - `9.223372036854776E18`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `9223372036854776000.0`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `9223372036854776000`: js-ir
- `long-max-float`
  - `9.223372E18`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `9223372000000000000.0`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `9223372036854776000`: js-ir
- `int-max-float`
  - `2.1474836E9`: android+cloudflare-jvm+ios+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `2147483600.0`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `2.14748365E9`: jvm-17
  - `2147483647`: js-ir
- `ulong-max-double`
  - `1.8446744073709552E19`: android+cloudflare-jvm+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm
  - `1.844674407370955E19`: ios+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `18446744073709552000.0`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `18446744073709552000`: js-ir

On 2.5.0-Beta1 (android, ios, jvm, macos and wasm from fixtures/atlas/2.5.0-beta1, js-ir from fixtures/atlas-ladder/2.5.0-beta1) the cells group as: android+ios+jvm+macos+wasm; js-ir.

Docs read (FULL text):
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-u-long/to-double.html : "The resulting value is the closest Double to this ULong value."
- https://www.oracle.com/java/technologies/javase/19-relnote-issues.html : "One example is Double.toString(2e23), which now returns "2.0E23", whereas in earlier releases it returns "1.9999999999999998E23""

Existing tracker issues (state read through the YouTrack REST API and the GitHub API on 2026-10-15; nothing was posted): KT-88414 Fixed; KT-24975 Backlog.

What is known: Downgraded from documented: the ULong.toDouble KDoc says "closest Double", Native 2.4.20 printed 1.844674407370955E19 (not the closest double to 2^64-1) and prints 1.8446744073709552E19 on 2.5.0-Beta1; no YouTrack issue and no GitHub commit found for that fix (queries listed). Wasm lines: KT-88414. jvm-17 int-max-float 2.14748365E9 versus 2.1474836E9: JDK 19 note (Float.toString too). js-ir keeps Float at double range and precision (1e40-float prints 1e+40, 1e-50-float prints 1e-50) and prints the large values without .0, as KT-24975 (Backlog) describes for Float on JS; js-ir is unchanged on 2.5.0-Beta1. cloudflare-wasm prints what wasm prints; cloudflare-jvm prints what android, jvm, jvm-21, jvm-25 print.

What is NOT known: whether the divergent column is a defect or by design where no page read states the intended behavior; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device, runtime or workerd release of the same target differs (one run per cell); whether a TeaVM or bytebox release other than 0.15.0 and 1.0.3 changes the cloudflare-jvm column; whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
