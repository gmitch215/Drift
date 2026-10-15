Draft for human review. Not filed.

# kotlin.double.shortest-digits

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: Does Double.toString print the shortest digits that round-trip?

Classification now: unclassified (was unclassified); cause letters: b, c, e; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64); Kotlin 2.5.0-Beta1 on android, ios, jvm, macos and wasm (fixtures/atlas/2.5.0-beta1) and on js-ir (fixtures/atlas-ladder/2.5.0-beta1, the standalone compiler). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM 0.15.0), so its Java classes are not a JDK. The rows also carry the transcripts in fixtures/atlas-extra (jvm-21-arm64, linux-ubuntu, mingw-wine). Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
for ((name, d) in listOf(
    "2e23" to 2e23, "1e23" to 1e23, "8.41e21" to 8.41e21, "5e-324" to 5e-324,
    "4.35" to 4.35, "0.3" to 0.3, "1/3" to 1.0 / 3.0, "2/3" to 2.0 / 3.0,
    "1e16/3" to 1e16 / 3.0, "0.1f" to 0.1f.toDouble(), "sqrt2" to sqrt(2.0),
    "1.1*1.1" to 1.1 * 1.1, "123456.789e-6" to 123456.789e-6,
)) line(name, d)
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `2e23`
  - `2.0E23`: cloudflare-jvm+ios+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `1.9999999999999998e+23`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `1.9999999999999998E23`: android+jvm-17
  - `2e+23`: js-ir
- `1e23`
  - `1.0E23`: cloudflare-jvm+ios+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `9.999999999999999e+22`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `9.999999999999999E22`: android+jvm-17
  - `1e+23`: js-ir
- `8.41e21`
  - `8.41E21`: cloudflare-jvm+ios+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `8.409999999999999e+21`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `8.409999999999999E21`: android+jvm-17
  - `8.41e+21`: js-ir
- `5e-324`
  - `4.9E-324`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `5e-324`: cloudflare-wasm+js-ir+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `1e16/3`
  - `3.3333333333333335E15`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `3333333333333333.5`: cloudflare-wasm+js-ir+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `0.1f`
  - `0.10000000149011612`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `0.1`: js-ir

On 2.5.0-Beta1 (android, ios, jvm, macos and wasm from fixtures/atlas/2.5.0-beta1, js-ir from fixtures/atlas-ladder/2.5.0-beta1) the cells group as: android; ios+jvm+macos+wasm; js-ir.

Docs read (FULL text):
- https://www.oracle.com/java/technologies/javase/19-relnote-issues.html : "One example is Double.toString(2e23), which now returns "2.0E23", whereas in earlier releases it returns "1.9999999999999998E23""
- https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Double.html : "as many, but only as many, more digits as are needed to uniquely distinguish the argument value from adjacent values of type double"
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Double.html : "This decimal is (almost always) the shortest one that rounds to m according to the round to nearest rounding policy"

Existing tracker issues (state read through the YouTrack REST API and the GitHub API on 2026-10-15; nothing was posted): KT-88414 Fixed.

What is known: Three groups differ from the majority. jvm-17 and android print the pre-JDK-19 digits (1.9999999999999998E23 for 2e23): the JDK 19 release note JDK-4511638 explains jvm-17 (verified), and Android matching the old algorithm is observed, the Android Double page read carries the old spec text. The wasm family prints 1.9999999999999998e+23 and plain forms (KT-88414 Fixed, equal on 2.5.0-Beta1). js-ir prints the ECMAScript shortest digits (2e+23, 1e+23) and 0.1f as 0.1 because a Float is a double there; its lines are unchanged on 2.5.0-Beta1 (measured with the standalone compiler). Android is still the odd column on 2.5.0-Beta1. cloudflare-wasm prints what wasm prints; cloudflare-jvm prints what ios, jvm, jvm-21, jvm-25 and 3 more print.

What is NOT known: whether the divergent column is a defect or by design where no page read states the intended behavior; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device, runtime or workerd release of the same target differs (one run per cell); whether a TeaVM or bytebox release other than 0.15.0 and 1.0.3 changes the cloudflare-jvm column; whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
