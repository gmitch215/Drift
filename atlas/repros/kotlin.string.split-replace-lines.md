Draft for human review. Not filed.

# kotlin.string.split-replace-lines

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: What do split, replace and lines return at the edges?

Classification now: unclassified (was documented); cause letters: g; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns (android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, jvm 21 arm64 in a container, linux x64, linux arm64 and linux-ubuntu Native, mingw Native under wine); Kotlin 2.5.0-Beta1 on jvm, macos, wasm, android and ios. Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
line("indexOf-empty", "abc".indexOf("", 10))
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `indexOf-empty`
  - `3`: android+ios+jvm+macos+jvm-17+jvm-21+jvm-21-arm64+jvm-25+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `-1`: wasm+3browsers

On 2.5.0-Beta1 the cells group as: jvm+macos+android+ios; wasm.

Docs read (FULL text):
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/String.html#indexOf(java.lang.String,int) : "k >= Math.min(fromIndex, this.length()) && this.startsWith(str, k)"
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/String.html#indexOf(java.lang.String,int) : "it returns -1 when fromIndex is larger than the length of the string"

Existing tracker issues (read through the YouTrack REST API on 2026-10-07; nothing was posted): KT-63088 Answered; KT-50777 Open.

What is known: Downgraded from documented: the cited doc covers lines(), the divergent line is indexOf("", 10) on a 3-char string (3 on every column except the Wasm family: -1). The Kotlin indexOf KDoc gives no rule for startIndex past the end. JDK 21 gives the formula that yields 3 and an API note that says -1 for fromIndex past the end; KT-63088 (Answered) points to the JDK behavior. Unchanged on 2.5.0-Beta1.

What is NOT known: the intended behavior where no page read states one; whether the divergent column is a defect or by design; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device of the same target differs (one device per column); whether the displayed snippet and the executed probe body are identical (they are two hand-written copies; the ladder agent re-compiled the displayed text and matched 37 of 38 probes on JVM and macOS Native).
