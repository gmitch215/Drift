Draft for human review. Not filed.

# kotlin.char.case-special

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns cloudflare-wasm and cloudflare-jvm named below are measured transcripts in fixtures/atlas; the tools/cloudflare build in this repository produces them.

Question: Which special case mappings (sharp s, dotted I, sigma, ligatures) apply?

Classification now: unclassified (was documented); cause letters: a; basis: verified.

Measured: Kotlin 2.4.20 on 15 columns of fixtures/atlas (android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM), so its Java classes are not a JDK. Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
line("upper-sharp-s", codes("ß".uppercase()))
line("lower-dotted-I", codes("İ".lowercase()))
line("upper-n-apostrophe", codes("ŉ".uppercase()))
line("upper-j-caron", codes("ǰ".uppercase()))
line("upper-iota-tonos", codes("ΐ".uppercase()))
line("upper-ligature-fi", codes("ﬁ".uppercase()))
line("upper-greek-iota-sub", codes("ᾳ".uppercase()))
line("lower-final-sigma", codes("ΑΣ".lowercase()))
line("lower-sigma-space", codes("ΑΣ ".lowercase()))
line("title-dz", codes("ǆ".replaceFirstChar { it.titlecase() }))
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `upper-sharp-s`
  - `[53, 53]`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[df]`: cloudflare-jvm
- `lower-dotted-I`
  - `[69, 307]`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[69]`: cloudflare-jvm
- `upper-n-apostrophe`
  - `[2bc, 4e]`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[149]`: cloudflare-jvm
- `upper-j-caron`
  - `[4a, 30c]`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[1f0]`: cloudflare-jvm
- `upper-iota-tonos`
  - `[399, 308, 301]`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[390]`: cloudflare-jvm
- `upper-ligature-fi`
  - `[46, 49]`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[fb01]`: cloudflare-jvm
- `upper-greek-iota-sub`
  - `[391, 399]`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[1fbc]`: cloudflare-jvm
- `lower-final-sigma`
  - `[3b1, 3c2]`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[3b1, 3c3]`: cloudflare-jvm
- `lower-sigma-space`
  - `[3b1, 3c2, 20]`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[3b1, 3c3, 20]`: cloudflare-jvm
- `title-dz`
  - `[1c5]`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[1c4]`: cloudflare-jvm

Docs read (FULL text):
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/uppercase.html : "Converts this character to upper case using Unicode mapping rules of the invariant locale."

Existing tracker issues: none read. No tracker was queried for the cloudflare columns.

What is known: Downgraded from documented: the KDoc pages read say uppercase and lowercase use Unicode mapping rules and allow one-to-many mappings, and cloudflare-jvm prints one-to-one results on ten lines (upper-sharp-s is [df] where every other column prints [53, 53]). No page read explains the cloudflare-jvm lines. The Java classes on that column are TeaVM's class library. cloudflare-wasm prints what wasm prints.

What is NOT known: why the cloudflare-jvm output differs (TeaVM's class library, the bytebox build or the Worker runtime were not examined line by line); whether it is a defect or by design; whether a different bytebox or TeaVM release changes the result; whether a different workerd release differs (one runtime version per column); whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
