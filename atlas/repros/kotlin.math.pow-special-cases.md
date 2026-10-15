Draft for human review. Not filed.

# kotlin.math.pow-special-cases

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: What does pow return for NaN, infinities, zeros and huge exponents?

Classification now: unclassified (was documented); cause letters: a, b, c, d; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64); Kotlin 2.5.0-Beta1 on android, ios, jvm, macos and wasm (fixtures/atlas/2.5.0-beta1) and on js-ir (fixtures/atlas-ladder/2.5.0-beta1, the standalone compiler). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM 0.15.0), so its Java classes are not a JDK. The rows also carry the transcripts in fixtures/atlas-extra (jvm-21-arm64, linux-ubuntu, mingw-wine). Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
val nan = Double.NaN
val inf = Double.POSITIVE_INFINITY
line("1^nan", 1.0.pow(nan))
line("1^inf", 1.0.pow(inf))
line("-1^inf", (-1.0).pow(inf))
line("2^-1074", 2.0.pow(-1074.0))
line("10^-5", bits(10.0.pow(-5.0)))
line("nan^0", nan.pow(0.0))
line("2^-1075", 2.0.pow(-1075.0))
line("2^int-10", 2.0.pow(10))
line("int-pow-nan", nan.pow(0))
line("inf^0", inf.pow(0.0))
line("inf^-1", inf.pow(-1.0))
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `nan^0`
  - `1.0`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `1`: js-ir
- `1^nan`
  - `NaN`: cloudflare-jvm+cloudflare-wasm+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `1.0`: android+ios+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
- `1^inf`
  - `NaN`: cloudflare-jvm+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `1.0`: android
- `-1^inf`
  - `NaN`: cloudflare-jvm+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `1.0`: android
- `2^-1075`
  - `0.0`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `0`: js-ir
- `2^-1074`
  - `4.9E-324`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `5e-324`: cloudflare-wasm+js-ir+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `10^-5`
  - `3ee4f8b588e368f1`: android+cloudflare-jvm+ios+js-ir+jvm-17+jvm-21+jvm-25+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `3ee4f8b588e368f0`: cloudflare-wasm+jvm-21-arm64+jvm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `2^int-10`
  - `1024.0`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `1024`: js-ir
- `int-pow-nan`
  - `1.0`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `1`: js-ir
- `inf^0`
  - `1.0`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `1`: js-ir
- `inf^-1`
  - `0.0`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `0`: js-ir

On 2.5.0-Beta1 (android, ios, jvm, macos and wasm from fixtures/atlas/2.5.0-beta1, js-ir from fixtures/atlas-ladder/2.5.0-beta1) the cells group as: android; ios+macos; js-ir; jvm+wasm.

Docs read (FULL text):
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.math/pow.html : "b.pow(NaN) is NaN"
- https://developer.android.com/reference/java/lang/Math : "If the second argument is NaN, then the result is NaN except where the first argument is 1.0."
- https://developer.android.com/reference/java/lang/Math : "If the absolute value of the first argument equals 1 and the second argument is infinite, then the result is 1.0."
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Math.html : "all implementations of the equivalent functions of class Math are not defined to return the bit-for-bit same results"

Existing tracker issues (state read through the YouTrack REST API and the GitHub API on 2026-10-15; nothing was posted): KT-89072 Open; KT-88414 Fixed.

What is known: Downgraded from documented: the Kotlin KDoc states b.pow(NaN) is NaN, so Native violates a documented contract (open defect, KT-89072, not a documented difference). The Android column is also off the Kotlin contract, but its own Math.pow page documents the 1.0 results (verified). The 10^-5 bits line follows the CPU on one JDK and is inside the 1 ulp the JDK page allows. js-ir differs only in text form. The wasm 2^-1074 line is KT-88414 (Fixed, equal on 2.5.0-Beta1). cloudflare-wasm prints what wasm prints; cloudflare-jvm prints what jvm-17, jvm-21, jvm-25 print.

What is NOT known: whether the divergent column is a defect or by design where no page read states the intended behavior; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device, runtime or workerd release of the same target differs (one run per cell); whether a TeaVM or bytebox release other than 0.15.0 and 1.0.3 changes the cloudflare-jvm column; whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
