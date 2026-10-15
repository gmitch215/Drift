Draft for human review. Not filed.

# kotlin.string.hashcode

This text was written by an agent from measured transcripts. A person must rerun the probe and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports.

Question: Do hashCode() values for Double, Float and Long keys agree across Kotlin 2.4.20 columns?

Classification now: unclassified (was unclassified); cause letters: none; basis: inferred.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64); Kotlin 2.5.0-Beta1 on android, ios, jvm, macos and wasm (fixtures/atlas/2.5.0-beta1) and on js-ir (fixtures/atlas-ladder/2.5.0-beta1, the standalone compiler). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM 0.15.0), so its Java classes are not a JDK. The rows also carry the transcripts in fixtures/atlas-extra (jvm-21-arm64, linux-ubuntu, mingw-wine). Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`.

Code for the differing lines (from the displayed probe source):

```kotlin
line("double-1.5", 1.5.hashCode())
line("double-neg-0", (-0.0).hashCode())
line("double-nan", Double.NaN.hashCode())
line("float-1.5", 1.5f.hashCode())
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `double-1.5`
  - `1073217536`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `-1089994752`: js-ir
- `double-neg-0`
  - `-2147483648`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `0`: js-ir
- `double-nan`
  - `2146959360`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `2131230720`: js-ir
- `float-1.5`
  - `1069547520`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `-1089994752`: js-ir

On 2.5.0-Beta1 (android, ios, jvm, macos and wasm from fixtures/atlas/2.5.0-beta1, js-ir from fixtures/atlas-ladder/2.5.0-beta1) the cells group as: android+ios+jvm+macos+wasm; js-ir.

Docs read (FULL text):

Existing tracker issues: none linked to this entry.

What is known: The js-ir column prints different Double and Float hash codes from every other 2.4.20 column (for example double-1.5 = -1089994752 against 1073217536, double-neg-0 = 0 against -2147483648, double-nan = 2131230720 against 2146959360, float-1.5 = -1089994752 against 1069547520). Every other column agrees on every line, and the 2.5.0-Beta1 transcripts of android, ios, jvm, macos and wasm are identical to each other. js-ir is unchanged on 2.5.0-Beta1. The Any.hashCode page promises only that equal objects have equal hash codes, so the values are neither documented nor shown to be wrong. No cause found; KT-44746 and KT-61028 do not cover these lines.

What is NOT known: whether the divergent column is a defect or by design where no page read states the intended behavior; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device, runtime or workerd release of the same target differs (one run per cell); whether a TeaVM or bytebox release other than 0.15.0 and 1.0.3 changes the cloudflare-jvm column; whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
