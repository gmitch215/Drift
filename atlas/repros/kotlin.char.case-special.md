Draft for human review. Not filed.

# kotlin.char.case-special

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns cloudflare-wasm and cloudflare-jvm named below are measured transcripts in fixtures/atlas; the tools/cloudflare build in this repository produces them.

Question: Which special case mappings (sharp s, dotted I, sigma, ligatures) apply?

Classification now: unclassified (was documented); cause letters: f; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64); Kotlin 2.5.0-Beta1 on android, ios, jvm, macos and wasm (fixtures/atlas/2.5.0-beta1) and on js-ir (fixtures/atlas-ladder/2.5.0-beta1, the standalone compiler). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM 0.15.0), so its Java classes are not a JDK. The rows also carry the transcripts in fixtures/atlas-extra (jvm-21-arm64, linux-ubuntu, mingw-wine). Single run per cell; no spread measured.

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
  - `[53, 53]`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[df]`: cloudflare-jvm
- `lower-dotted-I`
  - `[69, 307]`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[69]`: cloudflare-jvm
- `upper-n-apostrophe`
  - `[2bc, 4e]`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[149]`: cloudflare-jvm
- `upper-j-caron`
  - `[4a, 30c]`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[1f0]`: cloudflare-jvm
- `upper-iota-tonos`
  - `[399, 308, 301]`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[390]`: cloudflare-jvm
- `upper-ligature-fi`
  - `[46, 49]`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[fb01]`: cloudflare-jvm
- `upper-greek-iota-sub`
  - `[391, 399]`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[1fbc]`: cloudflare-jvm
- `lower-final-sigma`
  - `[3b1, 3c2]`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[3b1, 3c3]`: cloudflare-jvm
- `lower-sigma-space`
  - `[3b1, 3c2, 20]`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[3b1, 3c3, 20]`: cloudflare-jvm
- `title-dz`
  - `[1c5]`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[1c4]`: cloudflare-jvm

On 2.5.0-Beta1 (android, ios, jvm, macos and wasm from fixtures/atlas/2.5.0-beta1, js-ir from fixtures/atlas-ladder/2.5.0-beta1) all six measured columns agree.

Docs read (FULL text):
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/uppercase.html : "This function supports one-to-many and many-to-one character mapping, thus the length of the returned string can be different from the length of the original string."

Existing tracker issues: none linked to this entry.

What is known: Downgraded from documented: the Kotlin String.uppercase page says one-to-many mappings are supported, and cloudflare-jvm prints one-to-one results on ten lines (upper-sharp-s is [df] where every other column prints [53, 53]). The mechanism is in the class library: TeaVM 0.15.0 (the version bytebox 1.0.3 pins) maps one UTF-16 unit at a time (TString.toUpperCaseChars, toLowerCaseChars), so sharp s, ligatures, final sigma and the dotted capital I get no special casing. That makes the column a build tool difference that contradicts the page read, which is why the probe stays unclassified. js-ir prints what the majority prints. cloudflare-wasm prints what wasm prints.

What is NOT known: whether the divergent column is a defect or by design where no page read states the intended behavior; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device, runtime or workerd release of the same target differs (one run per cell); whether a TeaVM or bytebox release other than 0.15.0 and 1.0.3 changes the cloudflare-jvm column; whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
