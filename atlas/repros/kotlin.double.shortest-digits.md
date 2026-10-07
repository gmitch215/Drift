Draft for human review. Not filed.

# kotlin.double.shortest-digits

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: Does Double.toString print the shortest digits that round-trip?

Classification now: unclassified (was unclassified); cause letters: c e; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns (android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, jvm 21 arm64 in a container, linux x64, linux arm64 and linux-ubuntu Native, mingw Native under wine); Kotlin 2.5.0-Beta1 on jvm, macos, wasm, android and ios. Single run per cell; no spread measured.

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
  - `1.9999999999999998E23`: android+jvm-17
  - `2.0E23`: ios+jvm+macos+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `1.9999999999999998e+23`: wasm+3browsers
- `1e23`
  - `9.999999999999999E22`: android+jvm-17
  - `1.0E23`: ios+jvm+macos+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `9.999999999999999e+22`: wasm+3browsers
- `8.41e21`
  - `8.409999999999999E21`: android+jvm-17
  - `8.41E21`: ios+jvm+macos+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `8.409999999999999e+21`: wasm+3browsers
- `5e-324`
  - `4.9E-324`: android+jvm-17, ios+jvm+macos+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `5e-324`: wasm+3browsers
- `1e16/3`
  - `3.3333333333333335E15`: android+jvm-17, ios+jvm+macos+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `3333333333333333.5`: wasm+3browsers

On 2.5.0-Beta1 the cells group as: jvm+macos+wasm+ios; android.

Docs read (FULL text):
- https://www.oracle.com/java/technologies/javase/19-relnote-issues.html : "One example is Double.toString(2e23), which now returns "2.0E23", whereas in earlier releases it returns "1.9999999999999998E23""
- https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Double.html : "as many, but only as many, more digits as are needed to uniquely distinguish the argument value from adjacent values of type double"
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Double.html : "This decimal is (almost always) the shortest one that rounds to m according to the round to nearest rounding policy"

Existing tracker issues (read through the YouTrack REST API on 2026-10-07; nothing was posted): KT-88414 Fixed.

What is known: Two groups of cells differ from the majority: jvm-17 and android print the pre-JDK-19 digits (1.9999999999999998E23 for 2e23), explained by the JDK 19 release note JDK-4511638 (verified for jvm-17; Android matching the old algorithm is observed, the Android Double page read carries the old spec text); Wasm family prints 1.9999999999999998e+23 and plain form (KT-88414, Fixed, equal on 2.5.0-Beta1). Android is still the odd column on 2.5.0-Beta1.

What is NOT known: the intended behavior where no page read states one; whether the divergent column is a defect or by design; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device of the same target differs (one device per column); whether the displayed snippet and the executed probe body are identical (they are two hand-written copies; the ladder agent re-compiled the displayed text and matched 37 of 38 probes on JVM and macOS Native).
