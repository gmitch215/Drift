Draft for human review. Not filed.

# kotlin.numbers.float-conversions

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: How do out-of-range floating point conversions behave?

Classification now: unclassified (was documented); cause letters: c e; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns (android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, jvm 21 arm64 in a container, linux x64, linux arm64 and linux-ubuntu Native, mingw Native under wine); Kotlin 2.5.0-Beta1 on jvm, macos, wasm, android and ios. Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
line("long-max-double", Long.MAX_VALUE.toDouble())
line("long-max-float", Long.MAX_VALUE.toFloat())
line("int-max-float", Int.MAX_VALUE.toFloat())
line("ulong-max-double", ULong.MAX_VALUE.toDouble())
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `long-max-double`
  - `9.223372036854776E18`: android+jvm+jvm-21+jvm-21-arm64+jvm-25, ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine, jvm-17
  - `9223372036854776000.0`: wasm+3browsers
- `long-max-float`
  - `9.223372E18`: android+jvm+jvm-21+jvm-21-arm64+jvm-25, ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine, jvm-17
  - `9223372000000000000.0`: wasm+3browsers
- `int-max-float`
  - `2.1474836E9`: android+jvm+jvm-21+jvm-21-arm64+jvm-25, ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `2147483600.0`: wasm+3browsers
  - `2.14748365E9`: jvm-17
- `ulong-max-double`
  - `1.8446744073709552E19`: android+jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
  - `1.844674407370955E19`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `18446744073709552000.0`: wasm+3browsers

On 2.5.0-Beta1 all five measured columns agree.

Docs read (FULL text):
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-u-long/to-double.html : "The resulting value is the closest Double to this ULong value."
- https://www.oracle.com/java/technologies/javase/19-relnote-issues.html : "One example is Double.toString(2e23), which now returns "2.0E23", whereas in earlier releases it returns "1.9999999999999998E23""

Existing tracker issues (read through the YouTrack REST API on 2026-10-07; nothing was posted): KT-88414 Fixed.

What is known: Downgraded from documented: the ULong.toDouble KDoc says "closest Double", Native 2.4.20 printed 1.844674407370955E19 (not the closest double to 2^64-1) and prints 1.8446744073709552E19 on 2.5.0-Beta1; no YouTrack issue and no GitHub commit found for that fix (queries listed). Wasm lines: KT-88414. jvm-17 int-max-float 2.14748365E9 versus 2.1474836E9: JDK 19 note (Float.toString too).

What is NOT known: the intended behavior where no page read states one; whether the divergent column is a defect or by design; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device of the same target differs (one device per column); whether the displayed snippet and the executed probe body are identical (they are two hand-written copies; the ladder agent re-compiled the displayed text and matched 37 of 38 probes on JVM and macOS Native).
