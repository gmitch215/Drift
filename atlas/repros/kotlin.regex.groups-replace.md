Draft for human review. Not filed.

# kotlin.regex.groups-replace

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns cloudflare-wasm and cloudflare-jvm named below are measured transcripts in fixtures/atlas; the tools/cloudflare build in this repository produces them.

Question: How do groups, empty matches, alternation and replacement strings behave?

Classification now: unclassified (was documented); cause letters: a; basis: verified.

Measured: Kotlin 2.4.20 on 15 columns of fixtures/atlas (android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM), so its Java classes are not a JDK. Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
line("group-in-repeat", repeat.groupValues)
line("group-in-repeat-null", repeat.groups[1]?.value)
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `group-in-repeat`
  - `[ab, a]`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[ab, ]`: cloudflare-jvm
- `group-in-repeat-null`
  - `a`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `null`: cloudflare-jvm

Docs read (FULL text):
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/-regex/replace.html : "Occurrences of ${name} or $index in the replacement string will be substituted"

Existing tracker issues: none read. No tracker was queried for the cloudflare columns.

What is known: Downgraded from documented: cloudflare-jvm prints [ab, ] and null for the group of a repeated alternation where every other column prints [ab, a] and a. The replace page read covers dollar-index and dollar-brace-name references only; no page read states what a repeated group captures. cloudflare-wasm prints what wasm prints.

What is NOT known: why the cloudflare-jvm output differs (TeaVM's class library, the bytebox build or the Worker runtime were not examined line by line); whether it is a defect or by design; whether a different bytebox or TeaVM release changes the result; whether a different workerd release differs (one runtime version per column); whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
