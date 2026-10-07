Draft for human review. Not filed.

# kotlin.math.rounding

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: How do round and roundToInt treat ties and values just below a tie?

Classification now: unclassified (was documented); cause letters: c; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns (android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, jvm 21 arm64 in a container, linux x64, linux arm64 and linux-ubuntu Native, mingw Native under wine); Kotlin 2.5.0-Beta1 on jvm, macos, wasm, android and ios. Single run per cell; no spread measured.

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
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `0.49999999999999994`
  - `round=0.0 int=0 long=0`: android+jvm+jvm-17+jvm-21+jvm-21-arm64+jvm-25
  - `round=0.0 int=1 long=1`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine, wasm+3browsers
- `4503599627370497`
  - `round=4.503599627370497E15 int=2147483647 long=4503599627370497`: android+jvm+jvm-17+jvm-21+jvm-21-arm64+jvm-25
  - `round=4.503599627370497E15 int=2147483647 long=4503599627370498`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `round=4503599627370497.0 int=2147483647 long=4503599627370498`: wasm+3browsers
- `float-8388609`
  - `8388609`: android+jvm+jvm-17+jvm-21+jvm-21-arm64+jvm-25
  - `8388610`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine, wasm+3browsers

On 2.5.0-Beta1 the cells group as: jvm+android; macos+wasm+ios.

Docs read (FULL text):
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.math/round-to-int.html : "Rounds this Double value to the nearest integer and converts the result to Int"

Existing tracker issues (read through the YouTrack REST API on 2026-10-07; nothing was posted): KT-89074 Open.

What is known: Downgraded from documented: the KDoc says "nearest integer" and the inputs are not ties, so Native and Wasm results (1, 4503599627370498, 8388610) violate it. KT-89074 Open. Unchanged on 2.5.0-Beta1.

What is NOT known: the intended behavior where no page read states one; whether the divergent column is a defect or by design; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device of the same target differs (one device per column); whether the displayed snippet and the executed probe body are identical (they are two hand-written copies; the ladder agent re-compiled the displayed text and matched 37 of 38 probes on JVM and macOS Native).
