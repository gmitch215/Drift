Draft for human review. Not filed.

# kotlin.math.pow-special-cases

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: What does pow return for NaN, infinities, zeros and huge exponents?

Classification now: unclassified (was documented); cause letters: a c d; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns (android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, jvm 21 arm64 in a container, linux x64, linux arm64 and linux-ubuntu Native, mingw Native under wine); Kotlin 2.5.0-Beta1 on jvm, macos, wasm, android and ios. Single run per cell; no spread measured.

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
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `1^nan`
  - `1.0`: android, ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `NaN`: jvm+jvm-21-arm64, wasm+3browsers, jvm-17+jvm-21+jvm-25
- `1^inf`
  - `1.0`: android
  - `NaN`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine, jvm+jvm-21-arm64, wasm+3browsers, jvm-17+jvm-21+jvm-25
- `-1^inf`
  - `1.0`: android
  - `NaN`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine, jvm+jvm-21-arm64, wasm+3browsers, jvm-17+jvm-21+jvm-25
- `2^-1074`
  - `4.9E-324`: android, ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine, jvm+jvm-21-arm64, jvm-17+jvm-21+jvm-25
  - `5e-324`: wasm+3browsers
- `10^-5`
  - `3ee4f8b588e368f1`: android, ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine, jvm-17+jvm-21+jvm-25
  - `3ee4f8b588e368f0`: jvm+jvm-21-arm64, wasm+3browsers

On 2.5.0-Beta1 the cells group as: jvm+wasm; macos+ios; android.

Docs read (FULL text):
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.math/pow.html : "b.pow(NaN) is NaN"
- https://developer.android.com/reference/java/lang/Math : "If the second argument is NaN, then the result is NaN except where the first argument is 1.0."
- https://developer.android.com/reference/java/lang/Math : "If the absolute value of the first argument equals 1 and the second argument is infinite, then the result is 1.0."
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Math.html : "all implementations of the equivalent functions of class Math are not defined to return the bit-for-bit same results"

Existing tracker issues (read through the YouTrack REST API on 2026-10-07; nothing was posted): KT-89072 Open; KT-88414 Fixed.

What is known: Downgraded from documented: the Kotlin KDoc states b.pow(NaN) is NaN, so Native violates a documented contract (open defect, not a documented difference). The Android column is also off the Kotlin contract, but its own Math.pow page documents the 1.0 results (verified).

What is NOT known: the intended behavior where no page read states one; whether the divergent column is a defect or by design; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device of the same target differs (one device per column); whether the displayed snippet and the executed probe body are identical (they are two hand-written copies; the ladder agent re-compiled the displayed text and matched 37 of 38 probes on JVM and macOS Native).
