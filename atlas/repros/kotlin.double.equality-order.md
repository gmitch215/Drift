Draft for human review. Not filed.

# kotlin.double.equality-order

This text was written by an agent from measured transcripts and the page it read. A person must rerun the probe, read the page named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports.

Question: How do NaN and signed zero compare under == , equals, compareTo and generic collection code?

Classification now: unclassified (was documented); cause letters: none; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, js-ir on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1). Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`.

Code for the differing lines (from the displayed probe source):

```kotlin
line("minOf", minOf(0.0, -0.0))
line("maxOf", maxOf(-0.0, 0.0))
line("distinct", listOf(0.0, -0.0).distinct())
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `minOf`
  - `-0.0`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `0`: js-ir
- `maxOf`
  - `0.0`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `0`: js-ir
- `distinct`
  - `[0.0, -0.0]`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `[0, 0]`: js-ir

The lines `0.0==-0.0 = true`, `any-0.0==any-neg-0.0 = false`, `nan==nan = false` and `any-nan==any-nan = true` agree on all 16 columns, including js-ir.

Docs read (FULL text):
- https://kotlinlang.org/docs/numbers.html : "NaN is considered equal to itself" (the page also states that generic and Any-typed comparison uses equals and compareTo, with -0.0 less than 0.0; that sentence was read on the earlier pass and is quoted from that read)

Existing tracker issues: none read for the js-ir lines.

What is known: js-ir prints both zeros as 0 in minOf, maxOf and distinct, where every other column prints 0.0 or -0.0. distinct keeps two elements on js-ir, so the two zeros are not merged. The js-ir hash of -0.0 is also 0 (see kotlin.string.hashcode).

What is NOT known: whether js-ir keeps the sign of -0.0 in the value and only prints it as 0, or drops it; whether the numbers page's ordering (-0.0 less than 0.0) holds for generic code on js-ir; whether a different js-ir release changes the result; whether the displayed snippet matches the executed probe body (they are two hand-written copies).
