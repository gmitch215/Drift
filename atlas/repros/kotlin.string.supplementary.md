Draft for human review. Not filed.

# kotlin.string.supplementary

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns cloudflare-wasm and cloudflare-jvm named below are measured transcripts in fixtures/atlas; the tools/cloudflare build in this repository produces them.

Question: How does a string with a supplementary character behave as UTF-16 units?

Classification now: unclassified (was documented); cause letters: a, f; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64); Kotlin 2.5.0-Beta1 on android, ios, jvm, macos and wasm (fixtures/atlas/2.5.0-beta1) and on js-ir (fixtures/atlas-ladder/2.5.0-beta1, the standalone compiler). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM 0.15.0), so its Java classes are not a JDK. The rows also carry the transcripts in fixtures/atlas-extra (jvm-21-arm64, linux-ubuntu, mingw-wine). Single run per cell; no spread measured.

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
  - `[d83d, de00]`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[de00, d83d]`: cloudflare-jvm
- `deseret-lower`
  - `[d801, dc28]`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[d801, dc00]`: cloudflare-jvm
- `deseret-upper-of-lower`
  - `[d801, dc00]`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[d801, dc28]`: cloudflare-jvm
- `compare-emoji-vs-ffff`
  - `-1`: cloudflare-wasm+ios+js-ir+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `-10178`: android+cloudflare-jvm+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm
- `compare-emoji-vs-e000`
  - `-1`: cloudflare-wasm+ios+js-ir+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `-1987`: android+cloudflare-jvm+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm

On 2.5.0-Beta1 (android, ios, jvm, macos and wasm from fixtures/atlas/2.5.0-beta1, js-ir from fixtures/atlas-ladder/2.5.0-beta1) the cells group as: android+jvm; ios+js-ir+macos+wasm.

Docs read (FULL text):
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-comparable/compare-to.html : "Returns zero if this object is equal to the specified other object, a negative number if it's less than other"
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/reversed.html : "Returns a string with characters in reversed order."
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/lowercase.html : "Returns a copy of this string converted to lower case using Unicode mapping rules of the invariant locale."

Existing tracker issues: none linked to this entry.

What is known: Downgraded from documented: cloudflare-jvm reverses a surrogate pair into [de00, d83d] and does not change the case of the Deseret letters (deseret-lower [d801, dc00], deseret-upper-of-lower [d801, dc28]), where every other column keeps the pair and maps it. The compareTo page read covers the compare-emoji magnitude lines (jvm family and cloudflare-jvm return the UTF-16 unit difference, the others -1), which promise only a sign. The reversed page is silent on pairs, so that line is unclassified rather than a violation; the lowercase page says Unicode mapping, which the cloudflare-jvm Deseret lines contradict. js-ir matches the native group. cloudflare-wasm prints what wasm prints.

What is NOT known: whether the divergent column is a defect or by design where no page read states the intended behavior; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device, runtime or workerd release of the same target differs (one run per cell); whether a TeaVM or bytebox release other than 0.15.0 and 1.0.3 changes the cloudflare-jvm column; whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
