Draft for human review. Not filed.

# kotlin.string.compare-order

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns cloudflare-wasm and cloudflare-jvm named below are measured transcripts in fixtures/atlas; the tools/cloudflare build in this repository produces them.

Question: In which order do strings compare, sort and match ignoring case?

Classification now: unclassified (was documented); cause letters: a, f; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64); Kotlin 2.5.0-Beta1 on android, ios, jvm, macos and wasm (fixtures/atlas/2.5.0-beta1) and on js-ir (fixtures/atlas-ladder/2.5.0-beta1, the standalone compiler). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM 0.15.0), so its Java classes are not a JDK. The rows also carry the transcripts in fixtures/atlas-extra (jvm-21-arm64, linux-ubuntu, mingw-wine). Single run per cell; no spread measured.

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
  - `true`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `false`: cloudflare-jvm
- `final-sigma`
  - `true`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `false`: cloudflare-jvm
- `compare-ignore-case-sigma`
  - `0`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `-1`: cloudflare-jvm
- `compare-ignore-case-sharp-s`
  - `1`: cloudflare-wasm+ios+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `108`: android+cloudflare-jvm+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm
- `compare-ignore-case-upper-lower`
  - `-1`: cloudflare-wasm+ios+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `-6`: android+cloudflare-jvm+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm

On 2.5.0-Beta1 (android, ios, jvm, macos and wasm from fixtures/atlas/2.5.0-beta1, js-ir from fixtures/atlas-ladder/2.5.0-beta1) the cells group as: android+js-ir+jvm; ios+macos+wasm.

Docs read (FULL text):
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-comparable/compare-to.html : "Returns zero if this object is equal to the specified other object, a negative number if it's less than other"
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/compare-to.html : "If ignoreCase is true, the result of Char.uppercaseChar().lowercaseChar() on each character is compared."
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/equals.html : "If ignoreCase is true, the result of Char.uppercaseChar().lowercaseChar() on each character is compared."

Existing tracker issues: none linked to this entry.

What is known: Downgraded from documented: cloudflare-jvm differs in value, not only in magnitude: dotless-i-vs-I and final-sigma print false where every other column prints true, and compare-ignore-case-sigma is -1 where the others print 0. The ignoreCase pages say Char.uppercaseChar().lowercaseChar() is compared, which the other columns follow; TeaVM 0.15.0 (the version bytebox 1.0.3 pins) lower-cases each unit and never upper-cases first, which gives the three values. The two magnitude lines (108 and -6 against 1 and -1 on native and wasm) are the part that the compareTo page documents, since it promises only a sign. js-ir matches the JVM family. cloudflare-wasm prints what wasm prints.

What is NOT known: whether the divergent column is a defect or by design where no page read states the intended behavior; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device, runtime or workerd release of the same target differs (one run per cell); whether a TeaVM or bytebox release other than 0.15.0 and 1.0.3 changes the cloudflare-jvm column; whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
