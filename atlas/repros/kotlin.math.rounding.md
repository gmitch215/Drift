Draft for human review. Not filed.

# kotlin.math.rounding

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: How do round and roundToInt treat ties and values just below a tie?

Classification now: unclassified (was documented); cause letters: b, c, f; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64); Kotlin 2.5.0-Beta1 on android, ios, jvm, macos and wasm (fixtures/atlas/2.5.0-beta1) and on js-ir (fixtures/atlas-ladder/2.5.0-beta1, the standalone compiler). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM 0.15.0), so its Java classes are not a JDK. The rows also carry the transcripts in fixtures/atlas-extra (jvm-21-arm64, linux-ubuntu, mingw-wine). Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
for ((name, d) in listOf(
    "0.5" to 0.5, "1.5" to 1.5, "2.5" to 2.5, "-0.5" to -0.5, "-1.5" to -1.5,
    "-2.5" to -2.5, "0.49999999999999994" to 0.49999999999999994,
    "2.4999999999999996" to 2.4999999999999996,
    "4503599627370497" to 4503599627370497.0,
)) {
    val r = round(d)
    line(name, "round=" + r + " int=" + d.roundToInt() + " long=" + d.roundToLong())
}
line("float-8388609", 8388609f.roundToInt())
line("float-neg-2.5", (-2.5f).roundToInt())
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `0.5`
  - `round=0.0 int=1 long=1`: android+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `round=0 int=1 long=1`: js-ir
  - `round=1.0 int=1 long=1`: cloudflare-jvm
- `1.5`
  - `round=2.0 int=2 long=2`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `round=2 int=2 long=2`: js-ir
- `2.5`
  - `round=2.0 int=3 long=3`: android+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `round=2 int=3 long=3`: js-ir
  - `round=3.0 int=3 long=3`: cloudflare-jvm
- `-0.5`
  - `round=-0.0 int=0 long=0`: android+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `round=-1.0 int=-1 long=-1`: cloudflare-jvm
  - `round=0 int=0 long=0`: js-ir
- `-1.5`
  - `round=-2.0 int=-1 long=-1`: android+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `round=-2 int=-1 long=-1`: js-ir
  - `round=-2.0 int=-2 long=-2`: cloudflare-jvm
- `-2.5`
  - `round=-2.0 int=-2 long=-2`: android+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `round=-2 int=-2 long=-2`: js-ir
  - `round=-3.0 int=-3 long=-3`: cloudflare-jvm
- `0.49999999999999994`
  - `round=0.0 int=1 long=1`: cloudflare-wasm+ios+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `round=0.0 int=0 long=0`: android+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm
  - `round=0 int=0 long=0`: js-ir
  - `round=1.0 int=1 long=1`: cloudflare-jvm
- `2.4999999999999996`
  - `round=2.0 int=2 long=2`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `round=2 int=2 long=2`: js-ir
- `4503599627370497`
  - `round=4.503599627370497E15 int=2147483647 long=4503599627370497`: android+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm
  - `round=4.503599627370497E15 int=2147483647 long=4503599627370498`: ios+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `round=4503599627370497.0 int=2147483647 long=4503599627370498`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `round=4.503599627370498E15 int=2147483647 long=4503599627370498`: cloudflare-jvm
  - `round=4503599627370497 int=2147483647 long=4503599627370497`: js-ir
- `float-neg-2.5`
  - `-2`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `-3`: cloudflare-jvm
- `float-8388609`
  - `8388610`: cloudflare-jvm+cloudflare-wasm+ios+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `8388609`: android+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm

On 2.5.0-Beta1 (android, ios, jvm, macos and wasm from fixtures/atlas/2.5.0-beta1, js-ir from fixtures/atlas-ladder/2.5.0-beta1) the cells group as: android+jvm; ios+macos+wasm; js-ir.

Docs read (FULL text):
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.math/round-to-int.html : "Rounds this Double value to the nearest integer and converts the result to Int"

Existing tracker issues (state read through the YouTrack REST API and the GitHub API on 2026-10-15; nothing was posted): KT-89074 Open; teavm#1263 Closed.

What is known: Downgraded from documented: the KDoc says "nearest integer" and the inputs are not ties, so the Native and Wasm results (1, 4503599627370498, 8388610) violate it (KT-89074 Open, unchanged on 2.5.0-Beta1). cloudflare-jvm differs on the round lines and the roundToInt and roundToLong lines (0.5, 2.5, -0.5, -1.5, -2.5, 0.49999999999999994, 4503599627370497, float-neg-2.5): TeaVM 0.15.0 (the version bytebox 1.0.3 pins) implements rint as round and round as the signum formula above, which rounds ties away from zero, and adding 0.5 in float or double precision moves the large integer inputs. teavm#1263 describes the round formula and was closed on 2026-10-04, after that release, so its fix is not in the measured build. js-ir differs only in text (round=0 against 0.0). cloudflare-wasm prints what wasm prints.

What is NOT known: whether the divergent column is a defect or by design where no page read states the intended behavior; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device, runtime or workerd release of the same target differs (one run per cell); whether a TeaVM or bytebox release other than 0.15.0 and 1.0.3 changes the cloudflare-jvm column; whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
