Draft for human review. Not filed.

# kotlin.collections.tostring-forms

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: What do these values print through toString?

Classification now: unclassified (was unclassified); cause letters: a c g; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns (android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, jvm 21 arm64 in a container, linux x64, linux arm64 and linux-ubuntu Native, mingw Native under wine); Kotlin 2.5.0-Beta1 on jvm, macos, wasm, android and ios. Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
line("list-doubles", listOf(1.0, 2.5f, 1e10))
line("result-failure", Result.failure<Int>(IllegalStateException("x")))
line("kclass-int", Int::class)
line("kclass-list", listOf(1)::class.simpleName)
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `list-doubles`
  - `[1.0, 2.5, 1.0E10]`: android+jvm+jvm-17+jvm-21+jvm-21-arm64+jvm-25, ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `[1.0, 2.5, 10000000000.0]`: wasm+3browsers
- `result-failure`
  - `Failure(java.lang.IllegalStateException: x)`: android+jvm+jvm-17+jvm-21+jvm-21-arm64+jvm-25
  - `Failure(kotlin.IllegalStateException: x)`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine, wasm+3browsers
- `kclass-int`
  - `int (Kotlin reflection is not available)`: android+jvm+jvm-17+jvm-21+jvm-21-arm64+jvm-25
  - `class kotlin.Int`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine, wasm+3browsers
- `kclass-list`
  - `SingletonList`: android+jvm+jvm-17+jvm-21+jvm-21-arm64+jvm-25
  - `ArrayList`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine, wasm+3browsers

On 2.5.0-Beta1 the cells group as: jvm+android; macos+wasm+ios.

Docs read (FULL text):
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-exception/ : "actual typealias Exception = java.lang.Exception"
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Throwable.html : "Returns a short description of this throwable. The result is the concatenation of: the name of the class of this object"
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/list-of.html : "Returns a new read-only list of given elements."

Existing tracker issues (read through the YouTrack REST API on 2026-10-07; nothing was posted): KT-88414 Fixed; KT-69456 Open; KT-78708 Fixed.

What is known: Four lines, three causes; the probe stays unclassified because kclass-list has none.

What is NOT known: the intended behavior where no page read states one; whether the divergent column is a defect or by design; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device of the same target differs (one device per column); whether the displayed snippet and the executed probe body are identical (they are two hand-written copies; the ladder agent re-compiled the displayed text and matched 37 of 38 probes on JVM and macOS Native).
