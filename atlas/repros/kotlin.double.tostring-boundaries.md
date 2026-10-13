Draft for human review. Not filed.

# kotlin.double.tostring-boundaries

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: Which of these Doubles print in plain form and which in E notation?

Classification now: unclassified (was unclassified); cause letters: c; basis: verified.

Measured: Kotlin 2.4.20 on 17 columns (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, jvm 21 arm64 in a container, linux x64, linux arm64 and linux-ubuntu Native, mingw Native under wine); Kotlin 2.5.0-Beta1 on jvm, macos, wasm, android and ios. Single run per cell; no spread measured.

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
  - `1.0E-4`: android+ios+jvm+macos+jvm-17+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `0.0001`: wasm+3browsers
- `9.999e-4`
  - `9.999E-4`: android+ios+jvm+macos+jvm-17+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `0.0009999`: wasm+3browsers
- `1e7`
  - `1.0E7`: android+ios+jvm+macos+jvm-17+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `10000000.0`: wasm+3browsers
  - `10000000`: js-ir
- `12345678`
  - `1.2345678E7`: android+ios+jvm+macos+jvm-17+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `12345678.0`: wasm+3browsers
  - `12345678`: js-ir
- `1e21`
  - `1.0E21`: android+ios+jvm+macos+jvm-17+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `1e+21`: wasm+3browsers
- `1e22`
  - `1.0E22`: android+ios+jvm+macos+jvm-17+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `1e+22`: wasm+3browsers
- `1e-7`
  - `1.0E-7`: android+ios+jvm+macos+jvm-17+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `1e-7`: wasm+3browsers

On 2.5.0-Beta1 all five measured columns agree.

Docs read (FULL text):
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Double.html : "This decimal is (almost always) the shortest one that rounds to m according to the round to nearest rounding policy"

Existing tracker issues (read through the YouTrack REST API on 2026-10-07; nothing was posted): KT-88414 Fixed.

What is known: Wasm family only; 7 lines; equal on 2.5.0-Beta1.

What is NOT known: the intended behavior where no page read states one; whether the divergent column is a defect or by design; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device of the same target differs (one device per column); whether the displayed snippet and the executed probe body are identical (they are two hand-written copies; the ladder agent re-compiled the displayed text and matched 37 of 38 probes on JVM and macOS Native).

## Cloudflare columns

Recorded 2026-10-12 on workerd 1.20261001.1 under wrangler 4.146.0 (wrangler dev --local, macOS arm64), one run per cell. cloudflare-wasm is the Kotlin/Wasm probe module with a hand-written Worker entry; cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 plugin (TeaVM), so its Java classes are not a JDK. These rows are not part of the results above.

Rows for the differing lines of this draft:

- `1e-4`
  - `1.0E-4`: android+cloudflare-jvm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos
  - `0.0001`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `9.999e-4`
  - `9.999E-4`: android+cloudflare-jvm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos
  - `0.0009999`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `1e7`
  - `1.0E7`: android+cloudflare-jvm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos
  - `10000000.0`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `12345678`
  - `1.2345678E7`: android+cloudflare-jvm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos
  - `12345678.0`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `1e21`
  - `1.0E21`: android+cloudflare-jvm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos
  - `1e+21`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `1e22`
  - `1.0E22`: android+cloudflare-jvm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos
  - `1e+22`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `1e-7`
  - `1.0E-7`: android+cloudflare-jvm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos
  - `1e-7`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
