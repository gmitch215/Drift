Draft for human review. Not filed.

# kotlin.string.split-replace-lines

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: What do split, replace and lines return at the edges?

Classification now: unclassified (was documented); cause letters: g; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64); Kotlin 2.5.0-Beta1 on android, ios, jvm, macos and wasm (fixtures/atlas/2.5.0-beta1) and on js-ir (fixtures/atlas-ladder/2.5.0-beta1, the standalone compiler). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM 0.15.0), so its Java classes are not a JDK. The rows also carry the transcripts in fixtures/atlas-extra (jvm-21-arm64, linux-ubuntu, mingw-wine). Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
line("indexOf-empty", "abc".indexOf("", 10))
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `indexOf-empty`
  - `3`: android+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `-1`: cloudflare-jvm+cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm

On 2.5.0-Beta1 (android, ios, jvm, macos and wasm from fixtures/atlas/2.5.0-beta1, js-ir from fixtures/atlas-ladder/2.5.0-beta1) the cells group as: android+ios+js-ir+jvm+macos; wasm.

Docs read (FULL text):
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/String.html#indexOf(java.lang.String,int) : "k >= Math.min(fromIndex, this.length()) && this.startsWith(str, k)"
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/String.html#indexOf(java.lang.String,int) : "it returns -1 when fromIndex is larger than the length of the string"

Existing tracker issues (state read through the YouTrack REST API and the GitHub API on 2026-10-15; nothing was posted): KT-63088 Answered; KT-50777 Open.

What is known: Downgraded from documented: the divergent line is indexOf("", 10) on a 3-char string (3 on android, ios, js-ir, jvm, linux and macos; -1 on the wasm family and cloudflare-jvm). The JDK 21 String.indexOf page contradicts itself here: its formula (k >= Math.min(fromIndex, length) and startsWith) gives 3, and its API note (it returns -1 when fromIndex is larger than the length) says -1. The JVM follows the formula; the -1 columns follow the note, and the Kotlin common loop and TeaVM 0.15.0 both iterate over an empty range for a start index past the end. The Kotlin indexOf KDoc gives no rule for that case. KT-63088 (Answered) asks about the same call and its answer comment rests on screenshots; KT-50777 is about lastIndexOf, which agrees on every column. Unchanged on 2.5.0-Beta1. cloudflare-wasm prints what wasm prints; cloudflare-jvm prints what wasm, wasm-chromium, wasm-firefox, wasm-webkit print.

What is NOT known: whether the divergent column is a defect or by design where no page read states the intended behavior; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device, runtime or workerd release of the same target differs (one run per cell); whether a TeaVM or bytebox release other than 0.15.0 and 1.0.3 changes the cloudflare-jvm column; whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
