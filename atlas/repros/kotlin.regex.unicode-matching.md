Draft for human review. Not filed.

# kotlin.regex.unicode-matching

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: What do \d, \w, \s, dot and ignore-case match outside ASCII?

Classification now: unclassified (was unclassified); cause letters: a e g; basis: inferred.

Measured: Kotlin 2.4.20 on 16 columns (android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, jvm 21 arm64 in a container, linux x64, linux arm64 and linux-ubuntu Native, mingw Native under wine); Kotlin 2.5.0-Beta1 on jvm, macos, wasm, android and ios. Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
fun find(label: String, pattern: String, input: String) =
fun option(label: String, pattern: String, option: RegexOption, input: String) =
find("digit-arabic", "^\\d${'$'}", "٣")
find("digit-fullwidth", "^\\d${'$'}", "３")
find("word-accent", "^\\w${'$'}", "é")
find("word-greek", "^\\w${'$'}", "α")
find("space-nbsp", "^\\s${'$'}", " ")
find("space-em", "^\\s${'$'}", " ")
find("space-nel", "^\\s${'$'}", "\u0085")
find("boundary-accent", "a\\b", "aé")
option("ic-sharp-s", "^straße${'$'}", RegexOption.IGNORE_CASE, "STRASSE")
option("ic-final-sigma", "^σ${'$'}", RegexOption.IGNORE_CASE, "ς")
option("ic-dotted-i", "^i${'$'}", RegexOption.IGNORE_CASE, "İ")
option("ic-dotless-i", "^ı${'$'}", RegexOption.IGNORE_CASE, "I")
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `digit-arabic`
  - `ok:true`: android
  - `ok:false`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine+wasm+3browsers, jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
- `digit-fullwidth`
  - `ok:true`: android
  - `ok:false`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine+wasm+3browsers, jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
- `word-accent`
  - `ok:true`: android
  - `ok:false`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine+wasm+3browsers, jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
- `word-greek`
  - `ok:true`: android
  - `ok:false`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine+wasm+3browsers, jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
- `space-nbsp`
  - `ok:true`: android
  - `ok:false`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine+wasm+3browsers, jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
- `space-em`
  - `ok:true`: android
  - `ok:false`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine+wasm+3browsers, jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
- `space-nel`
  - `ok:true`: android
  - `ok:false`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine+wasm+3browsers, jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
- `boundary-accent`
  - `ok:false`: android, ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine+wasm+3browsers, jvm-17
  - `ok:true`: jvm+jvm-21+jvm-21-arm64+jvm-25
- `ic-sharp-s`
  - `ok:true`: android
  - `ok:false`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine+wasm+3browsers, jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
- `ic-final-sigma`
  - `ok:true`: android, jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
  - `ok:false`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine+wasm+3browsers
- `ic-dotted-i`
  - `ok:false`: android
  - `ok:true`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine+wasm+3browsers, jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
- `ic-dotless-i`
  - `ok:false`: android, ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine+wasm+3browsers
  - `ok:true`: jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
- also differing, not expanded: `ic-deseret`

On 2.5.0-Beta1 the cells group as: jvm; macos+wasm+ios; android.

Docs read (FULL text):
- https://developer.android.com/reference/java/util/regex/Pattern : "This flag is not supported on Android, and Unicode character classes are always used."
- https://www.oracle.com/java/technologies/javase/19-relnote-issues.html : "The \b metacharacter now matches ASCII word characters by default in the same way that the \w metacharacter does."
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/-regex/ : "Note that the pattern syntax and the option set has differences on each platform."

Existing tracker issues (read through the YouTrack REST API on 2026-10-07; nothing was posted): KT-51859 Open; KT-58195 Open; KT-53212 As Designed; KT-58678 Open.

What is known: Three groups of lines. Android: Unicode classes always on (documented). jvm-17 versus jvm-21 and jvm-25 on boundary-accent: JDK 19 note on \b (consistent with the note; Native, Wasm and Android match JDK 17). Native and Wasm versus JVM on ic-final-sigma, ic-dotless-i, ic-deseret: unexplained. KT-51859 says UNICODE_CASE is always on in Native, which does not predict these three lines.

What is NOT known: the intended behavior where no page read states one; whether the divergent column is a defect or by design; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device of the same target differs (one device per column); whether the displayed snippet and the executed probe body are identical (they are two hand-written copies; the ladder agent re-compiled the displayed text and matched 37 of 38 probes on JVM and macOS Native).
