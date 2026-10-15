Draft for human review. Not filed.

# kotlin.double.tostring-boundaries

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: Which of these Doubles print in plain form and which in E notation?

Classification now: unclassified (was unclassified); cause letters: b, c; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64); Kotlin 2.5.0-Beta1 on android, ios, jvm, macos and wasm (fixtures/atlas/2.5.0-beta1) and on js-ir (fixtures/atlas-ladder/2.5.0-beta1, the standalone compiler). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM 0.15.0), so its Java classes are not a JDK. The rows also carry the transcripts in fixtures/atlas-extra (jvm-21-arm64, linux-ubuntu, mingw-wine). Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
for ((name, d) in listOf(
    "1e-4" to 1e-4, "1e-3" to 1e-3, "9.999e-4" to 9.999e-4, "9999999" to 9999999.0,
    "1e7" to 1e7, "12345678" to 12345678.0, "1e21" to 1e21, "1e22" to 1e22,
    "1e-7" to 1e-7, "100" to 100.0, "0.5" to 0.5,
)) line(name, d)
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `1e-4`
  - `1.0E-4`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `0.0001`: cloudflare-wasm+js-ir+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `9.999e-4`
  - `9.999E-4`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `0.0009999`: cloudflare-wasm+js-ir+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `9999999`
  - `9999999.0`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `9999999`: js-ir
- `1e7`
  - `1.0E7`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `10000000.0`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `10000000`: js-ir
- `12345678`
  - `1.2345678E7`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `12345678.0`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `12345678`: js-ir
- `1e21`
  - `1.0E21`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `1e+21`: cloudflare-wasm+js-ir+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `1e22`
  - `1.0E22`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `1e+22`: cloudflare-wasm+js-ir+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `1e-7`
  - `1.0E-7`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `1e-7`: cloudflare-wasm+js-ir+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `100`
  - `100.0`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `100`: js-ir

On 2.5.0-Beta1 (android, ios, jvm, macos and wasm from fixtures/atlas/2.5.0-beta1, js-ir from fixtures/atlas-ladder/2.5.0-beta1) the cells group as: android+ios+jvm+macos+wasm; js-ir.

Docs read (FULL text):
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Double.html : "This decimal is (almost always) the shortest one that rounds to m according to the round to nearest rounding policy"

Existing tracker issues (state read through the YouTrack REST API and the GitHub API on 2026-10-15; nothing was posted): KT-88414 Fixed.

What is known: Wasm family: seven lines differ (1e-4 as 0.0001, 1e7 as 10000000.0, 1e21 as 1e+21 and so on); KT-88414 Fixed, equal on 2.5.0-Beta1. js-ir: nine of the eleven lines differ (the seven above plus 9999999 and 100 without .0); the strings are what ECMAScript Number::toString produces, and they are unchanged on 2.5.0-Beta1 (measured with the standalone compiler). 1e-3 and 0.5 agree everywhere. cloudflare-wasm prints what wasm prints; cloudflare-jvm prints what android, ios, jvm, jvm-17 and 5 more print.

What is NOT known: whether the divergent column is a defect or by design where no page read states the intended behavior; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device, runtime or workerd release of the same target differs (one run per cell); whether a TeaVM or bytebox release other than 0.15.0 and 1.0.3 changes the cloudflare-jvm column; whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
