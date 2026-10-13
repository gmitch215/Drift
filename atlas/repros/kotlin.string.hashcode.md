Draft for human review. Not filed.

# kotlin.string.hashcode

This text was written by an agent from measured transcripts. A person must rerun the probe and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports.

Question: Do hashCode() values for Double, Float and Long keys agree across Kotlin 2.4.20 columns?

Classification now: unclassified (was unclassified); cause letters: none; basis: inferred.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, js-ir on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1). Single run per cell; no spread measured.

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
  - `1073217536`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `-1089994752`: js-ir
- `double-neg-0`
  - `-2147483648`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `0`: js-ir
- `double-nan`
  - `2146959360`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `2131230720`: js-ir
- `float-1.5`
  - `1069547520`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `-1089994752`: js-ir

Every other probe line in the hashcode block agrees across all 16 columns, including `hello`, `long`, `list`, `pair` and `long-max`.

Tracker issues named in the classification entry, read on the earlier pass and not re-read for this draft: KT-44746 (Fixed, Available in 1.5.0-M1) and KT-61028 (Fixed, Available in 1.9.20). Both changed the Native stdlib API and hash behavior across older versions; neither was checked against js-ir.

What is known: js-ir prints different hash codes for Double and Float keys from every other 2.4.20 column; the other columns agree with each other on all four lines.

What is NOT known: whether js-ir is wrong or by design; which Kotlin doc page, if any, fixes the Double and Float hash formula (none was read); whether the js-ir values follow from JavaScript number representation or from the hash function itself; whether a later js-ir release changes the result; whether the displayed snippet matches the executed probe body (they are two hand-written copies).
