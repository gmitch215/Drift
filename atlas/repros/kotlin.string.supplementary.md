Draft for human review. Not filed.

# kotlin.string.supplementary

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns cloudflare-wasm and cloudflare-jvm named below are measured transcripts in fixtures/atlas; the tools/cloudflare build in this repository produces them.

Question: How does a string with a supplementary character behave as UTF-16 units?

Classification now: unclassified (was documented); cause letters: a; basis: verified.

Measured: Kotlin 2.4.20 on 15 columns of fixtures/atlas (android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM), so its Java classes are not a JDK. Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
line("reversed", codes(emoji.reversed()))
line("deseret-lower", codes(deseret.lowercase()))
line("deseret-upper-of-lower", codes(units(0xD801, 0xDC28).uppercase()))
line("compare-emoji-vs-ffff", emoji.compareTo(units(0xFFFF)))
line("compare-emoji-vs-e000", emoji.compareTo(units(0xE000)))
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `reversed`
  - `[d83d, de00]`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[de00, d83d]`: cloudflare-jvm
- `deseret-lower`
  - `[d801, dc28]`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[d801, dc00]`: cloudflare-jvm
- `deseret-upper-of-lower`
  - `[d801, dc00]`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[d801, dc28]`: cloudflare-jvm
- `compare-emoji-vs-ffff`
  - `-10178`: android+cloudflare-jvm+jvm-17+jvm-21+jvm-25+jvm
  - `-1`: cloudflare-wasm+ios+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `compare-emoji-vs-e000`
  - `-1987`: android+cloudflare-jvm+jvm-17+jvm-21+jvm-25+jvm
  - `-1`: cloudflare-wasm+ios+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm

Docs read (FULL text):
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-comparable/compare-to.html : "Returns zero if this object is equal to the specified other object, a negative number if it's less than other"

Existing tracker issues: none read. No tracker was queried for the cloudflare columns.

What is known: Downgraded from documented: cloudflare-jvm reverses a surrogate pair into [de00, d83d] and does not change the case of the Deseret letters (deseret-lower [d801, dc00], deseret-upper-of-lower [d801, dc28]), where every other column keeps the pair and maps it. The compareTo page read covers only the sign of a comparison. cloudflare-wasm prints what wasm prints.

What is NOT known: why the cloudflare-jvm output differs (TeaVM's class library, the bytebox build or the Worker runtime were not examined line by line); whether it is a defect or by design; whether a different bytebox or TeaVM release changes the result; whether a different workerd release differs (one runtime version per column); whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
