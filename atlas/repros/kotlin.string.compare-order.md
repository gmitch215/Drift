Draft for human review. Not filed.

# kotlin.string.compare-order

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns cloudflare-wasm and cloudflare-jvm named below are measured transcripts in fixtures/atlas; the tools/cloudflare build in this repository produces them.

Question: In which order do strings compare, sort and match ignoring case?

Classification now: unclassified (was documented); cause letters: a; basis: verified.

Measured: Kotlin 2.4.20 on 15 columns of fixtures/atlas (android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM), so its Java classes are not a JDK. Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
line("dotless-i-vs-I", "ı".equals("I", ignoreCase = true))
line("final-sigma", "ς".equals("Σ", ignoreCase = true))
line("compare-ignore-case-sigma", "ς".compareTo("σ", ignoreCase = true))
line("compare-ignore-case-sharp-s", "ß".compareTo("ss", ignoreCase = true))
line("compare-ignore-case-upper-lower", "[".compareTo("a", ignoreCase = true))
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `dotless-i-vs-I`
  - `true`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `false`: cloudflare-jvm
- `final-sigma`
  - `true`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `false`: cloudflare-jvm
- `compare-ignore-case-sigma`
  - `0`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `-1`: cloudflare-jvm
- `compare-ignore-case-sharp-s`
  - `108`: android+cloudflare-jvm+jvm-17+jvm-21+jvm-25+jvm
  - `1`: cloudflare-wasm+ios+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `compare-ignore-case-upper-lower`
  - `-6`: android+cloudflare-jvm+jvm-17+jvm-21+jvm-25+jvm
  - `-1`: cloudflare-wasm+ios+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm

Docs read (FULL text):
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-comparable/compare-to.html : "Returns zero if this object is equal to the specified other object, a negative number if it's less than other"
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/compare-to.html : "If ignoreCase is true, the result of Char.uppercaseChar().lowercaseChar() on each character is compared."

Existing tracker issues: none read. No tracker was queried for the cloudflare columns.

What is known: Downgraded from documented: cloudflare-jvm differs in value, not only in magnitude: dotless-i-vs-I and final-sigma print false where every other column prints true, and compare-ignore-case-sigma is -1 where the others print 0. The ignoreCase page read says Char.uppercaseChar().lowercaseChar() is compared, which the other columns follow. cloudflare-wasm prints what wasm prints.

What is NOT known: why the cloudflare-jvm output differs (TeaVM's class library, the bytebox build or the Worker runtime were not examined line by line); whether it is a defect or by design; whether a different bytebox or TeaVM release changes the result; whether a different workerd release differs (one runtime version per column); whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
