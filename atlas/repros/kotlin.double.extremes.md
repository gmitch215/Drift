Draft for human review. Not filed.

# kotlin.double.extremes

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: What do the extreme and special Doubles print?

Classification now: unclassified (was unclassified); cause letters: c; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns (android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, jvm 21 arm64 in a container, linux x64, linux arm64 and linux-ubuntu Native, mingw Native under wine); Kotlin 2.5.0-Beta1 on jvm, macos, wasm, android and ios. Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
line("min-value", Double.MIN_VALUE)
line("max-value", Double.MAX_VALUE)
line("min-normal", 2.2250738585072014E-308)
line("2^53+1", 9007199254740993.0)
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `min-value`
  - `4.9E-324`: android+ios+jvm+macos+jvm-17+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `5e-324`: wasm+3browsers
- `max-value`
  - `1.7976931348623157E308`: android+ios+jvm+macos+jvm-17+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `1.7976931348623157e+308`: wasm+3browsers
- `min-normal`
  - `2.2250738585072014E-308`: android+ios+jvm+macos+jvm-17+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `2.2250738585072014e-308`: wasm+3browsers
- `2^53+1`
  - `9.007199254740992E15`: android+ios+jvm+macos+jvm-17+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `9007199254740992.0`: wasm+3browsers

On 2.5.0-Beta1 all five measured columns agree.

Docs read (FULL text):
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Double.html : "This decimal is (almost always) the shortest one that rounds to m according to the round to nearest rounding policy"

Existing tracker issues (read through the YouTrack REST API on 2026-10-07; nothing was posted): KT-88414 Fixed; KT-88035 Open; KT-88036 Open.

What is known: Wasm family only. KT-88414 Fixed 2026-08-30; Wasm equals JVM on the 2.5.0-Beta1 transcript. No Kotlin page specifies the Double.toString format.

What is NOT known: the intended behavior where no page read states one; whether the divergent column is a defect or by design; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device of the same target differs (one device per column); whether the displayed snippet and the executed probe body are identical (they are two hand-written copies; the ladder agent re-compiled the displayed text and matched 37 of 38 probes on JVM and macOS Native).
