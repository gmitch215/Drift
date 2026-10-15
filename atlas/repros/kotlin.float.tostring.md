Draft for human review. Not filed.

# kotlin.float.tostring

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: What do these Floats print, and what is their Double widening?

Classification now: unclassified (was unclassified); cause letters: b, c, g; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64); Kotlin 2.5.0-Beta1 on android, ios, jvm, macos and wasm (fixtures/atlas/2.5.0-beta1) and on js-ir (fixtures/atlas-ladder/2.5.0-beta1, the standalone compiler). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM 0.15.0), so its Java classes are not a JDK. The rows also carry the transcripts in fixtures/atlas-extra (jvm-21-arm64, linux-ubuntu, mingw-wine). Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
for ((name, f) in listOf(
    "0.1" to 0.1f, "1e7" to 1e7f, "1e10" to 1e10f, "16777217" to 16777217f,
    "min" to Float.MIN_VALUE, "max" to Float.MAX_VALUE, "1/3" to 1f / 3f,
    "1e-3" to 1e-3f, "1e-4" to 1e-4f, "9999999" to 9999999f, "0.1+0.2" to 0.1f + 0.2f,
)) line(name, f)
line("widen-0.1", 0.1f.toDouble())
line("widen-sum", (0.1f + 0.2f).toDouble())
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `1e7`
  - `1.0E7`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `10000000.0`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `10000000`: js-ir
- `1e10`
  - `1.0E10`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `10000000000.0`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `10000000000`: js-ir
- `16777217`
  - `1.6777216E7`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `16777216.0`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `16777216`: js-ir
- `min`
  - `1.4E-45`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `1e-45`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `1.4e-45`: js-ir
- `max`
  - `3.4028235E38`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `3.4028235e+38`: cloudflare-wasm+js-ir+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `1/3`
  - `0.33333334`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `0.3333333333333333`: js-ir
- `1e-4`
  - `1.0E-4`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `0.0001`: cloudflare-wasm+js-ir+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `9999999`
  - `9999999.0`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `9999999`: js-ir
- `0.1+0.2`
  - `0.3`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `0.30000000000000004`: js-ir
- `widen-0.1`
  - `0.10000000149011612`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `0.1`: js-ir
- `widen-sum`
  - `0.30000001192092896`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `0.30000000000000004`: js-ir

On 2.5.0-Beta1 (android, ios, jvm, macos and wasm from fixtures/atlas/2.5.0-beta1, js-ir from fixtures/atlas-ladder/2.5.0-beta1) the cells group as: android+ios+jvm+macos+wasm; js-ir.

Docs read (FULL text):
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Double.html : "This decimal is (almost always) the shortest one that rounds to m according to the round to nearest rounding policy"

Existing tracker issues (state read through the YouTrack REST API and the GitHub API on 2026-10-15; nothing was posted): KT-88414 Fixed; KT-21235 Open.

What is known: Three columns of divergence. Wasm family: six lines print another text form (1e7 as 10000000.0, 1e-4 as 0.0001); KT-88414 Fixed, and the wasm group equals the JVM group on 2.5.0-Beta1. js-ir: eleven lines differ, seven only in text form (ECMAScript Number::toString: plain decimal for 1e7, lowercase e) and four in value (1/3 prints 0.3333333333333333 where the JVM prints 0.33333334; a Float carries double precision on Kotlin/JS, which contradicts the Float page); js-ir is unchanged on 2.5.0-Beta1 (measured with the standalone compiler). cloudflare-jvm matches the JVM group. cloudflare-wasm prints what wasm prints.

What is NOT known: whether the divergent column is a defect or by design where no page read states the intended behavior; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device, runtime or workerd release of the same target differs (one run per cell); whether a TeaVM or bytebox release other than 0.15.0 and 1.0.3 changes the cloudflare-jvm column; whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
