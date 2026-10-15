Draft for human review. Not filed.

# kotlin.double.parse

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: Which of these strings does toDoubleOrNull accept, and as what value?

Classification now: unclassified (was unclassified); cause letters: b, c, g; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64); Kotlin 2.5.0-Beta1 on android, ios, jvm, macos and wasm (fixtures/atlas/2.5.0-beta1) and on js-ir (fixtures/atlas-ladder/2.5.0-beta1, the standalone compiler). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM 0.15.0), so its Java classes are not a JDK. The rows also carry the transcripts in fixtures/atlas-extra (jvm-21-arm64, linux-ubuntu, mingw-wine). Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
line("float-above-max", "3.4028236e38".toFloatOrNull())
line("float-1e39", "1e39".toFloatOrNull())
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `'1e3'`
  - `1000.0`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `1000`: js-ir
- `'1.'`
  - `1.0`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `1`: js-ir
- `'0x1p3'`
  - `8.0`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `null`: cloudflare-wasm+js-ir+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `'1d'`
  - `1.0`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `null`: cloudflare-wasm+js-ir+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `'1f'`
  - `1.0`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `null`: cloudflare-wasm+js-ir+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `' 2 '`
  - `2.0`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `2`: js-ir
- `'1e-400'`
  - `0.0`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `0`: js-ir
- `'nan'`
  - `null`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `NaN`: js-ir
- `'00012'`
  - `12.0`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `12`: js-ir
- `'1E+2'`
  - `100.0`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `100`: js-ir
- `'0x10'`
  - `null`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `16`: js-ir
- `'0.1e1'`
  - `1.0`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `1`: js-ir
- `float-above-max`
  - `Infinity`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `3.4028236e+38`: js-ir
- `float-1e39`
  - `Infinity`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `1e+39`: js-ir

On 2.5.0-Beta1 (android, ios, jvm, macos and wasm from fixtures/atlas/2.5.0-beta1, js-ir from fixtures/atlas-ladder/2.5.0-beta1) the cells group as: android+ios+jvm+macos+wasm; js-ir.

Docs read (FULL text):
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Double.html : "This decimal is (almost always) the shortest one that rounds to m according to the round to nearest rounding policy"

Existing tracker issues (state read through the YouTrack REST API and the GitHub API on 2026-10-15; nothing was posted): KT-88414 Fixed; KT-72509 Open; KT-72508 Open.

What is known: Wasm family and js-ir both return null for 0x1p3, 1d and 1f, for different reasons. Wasm: KT-88414 Fixed (Kotlin/Wasm used its own parser); equal on 2.5.0-Beta1. js-ir: the parser is ECMAScript StringToNumber behind a NaN guard, which has no hex float and no d or f suffix; the same route accepts 0x10 (KT-72509 Open) and the guard keeps the string nan (no tracker item). js-ir also prints Float values outside the single precision range (float-above-max, float-1e39) and 1e-400 as 0. js-ir lines are unchanged on 2.5.0-Beta1 (measured with the standalone compiler). KT-72508 (Open) says the toDouble pages give no accepted formats, so no line is documented. cloudflare-wasm prints what wasm prints; cloudflare-jvm prints what android, ios, jvm, jvm-17 and 5 more print.

What is NOT known: whether the divergent column is a defect or by design where no page read states the intended behavior; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device, runtime or workerd release of the same target differs (one run per cell); whether a TeaVM or bytebox release other than 0.15.0 and 1.0.3 changes the cloudflare-jvm column; whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
