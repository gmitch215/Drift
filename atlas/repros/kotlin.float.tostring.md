Draft for human review. Not filed.

# kotlin.float.tostring

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: What do these Floats print, and what is their Double widening?

Classification now: unclassified (was unclassified); cause letters: c; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns (android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, jvm 21 arm64 in a container, linux x64, linux arm64 and linux-ubuntu Native, mingw Native under wine); Kotlin 2.5.0-Beta1 on jvm, macos, wasm, android and ios. Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
for ((name, f) in listOf(
	"0.1" to 0.1f, "1e7" to 1e7f, "1e10" to 1e10f, "16777217" to 16777217f,
	"min" to Float.MIN_VALUE, "max" to Float.MAX_VALUE, "1/3" to 1f / 3f,
	"1e-3" to 1e-3f, "1e-4" to 1e-4f, "9999999" to 9999999f, "0.1+0.2" to 0.1f + 0.2f,
)) line(name, f)
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `1e7`
  - `1.0E7`: android+ios+jvm+macos+jvm-17+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `10000000.0`: wasm+3browsers
- `1e10`
  - `1.0E10`: android+ios+jvm+macos+jvm-17+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `10000000000.0`: wasm+3browsers
- `16777217`
  - `1.6777216E7`: android+ios+jvm+macos+jvm-17+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `16777216.0`: wasm+3browsers
- `min`
  - `1.4E-45`: android+ios+jvm+macos+jvm-17+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `1e-45`: wasm+3browsers
- `max`
  - `3.4028235E38`: android+ios+jvm+macos+jvm-17+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `3.4028235e+38`: wasm+3browsers
- `1e-4`
  - `1.0E-4`: android+ios+jvm+macos+jvm-17+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `0.0001`: wasm+3browsers

On 2.5.0-Beta1 all five measured columns agree.

Docs read (FULL text):
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Double.html : "This decimal is (almost always) the shortest one that rounds to m according to the round to nearest rounding policy"

Existing tracker issues (read through the YouTrack REST API on 2026-10-07; nothing was posted): KT-88414 Fixed.

What is known: Wasm family only; 6 lines; equal on 2.5.0-Beta1.

What is NOT known: the intended behavior where no page read states one; whether the divergent column is a defect or by design; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device of the same target differs (one device per column); whether the displayed snippet and the executed probe body are identical (they are two hand-written copies; the ladder agent re-compiled the displayed text and matched 37 of 38 probes on JVM and macOS Native).
