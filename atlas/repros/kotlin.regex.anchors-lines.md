Draft for human review. Not filed.

# kotlin.regex.anchors-lines

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns cloudflare-wasm and cloudflare-jvm named below are measured transcripts in fixtures/atlas; the tools/cloudflare build in this repository produces them.

Question: How do ^, $ and line terminators behave, including from a start index?

Classification now: unclassified (was unclassified); cause letters: none; basis: inferred.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM), so its Java classes are not a JDK. Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
line("dollar-before-lf", Regex("^a$").containsMatchIn("a\n"))
line("dollar-before-crlf", Regex("^a$").containsMatchIn("a\r\n"))
line("dollar-before-cr", Regex("^a$").containsMatchIn("a\r"))
line("dollar-before-nel", Regex("^a$").containsMatchIn("a\u0085"))
line("caret-count-multiline", count("^", "a\nb\n", RegexOption.MULTILINE))
line("dollar-count-multiline", count("$", "a\nb\n", RegexOption.MULTILINE))
line("dollar-count-plain", count("$", "a\n"))
line("crlf-multiline", count("^b$", "a\r\nb\r\nc", RegexOption.MULTILINE))
line("cr-multiline", count("^b$", "a\rb", RegexOption.MULTILINE))
line("nel-multiline", count("^b$", "a\u0085b", RegexOption.MULTILINE))
line("ls-multiline", count("^b$", "a b", RegexOption.MULTILINE))
line("dot-star-lines", Regex(".*").findAll("a\nb").map { it.value }.toList())
line("word-boundaries", count("\\b", "ab cd"))
line("caret-from-index", Regex("^b").find("ab", 1) != null)
line("lookbehind-from-index", Regex("(?<=a)b").find("ab", 1)?.value)
line("matchAt-lookbehind", Regex("(?<=a)b").matchAt("ab", 1)?.value)
line("matchAt-caret", Regex("^b").matchAt("ab", 1)?.value)
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `dollar-before-lf`
  - `true`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
  - `false`: js-ir
- `dollar-before-crlf`
  - `true`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
  - `false`: js-ir
- `dollar-before-cr`
  - `true`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
  - `false`: js-ir
- `dollar-before-nel`
  - `true`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
  - `false`: js-ir
- `caret-count-multiline`
  - `2`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
  - `3`: js-ir
- `dollar-count-multiline`
  - `3`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `dollar-count-plain`
  - `2`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
  - `1`: js-ir
- `crlf-multiline`
  - `1`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `cr-multiline`
  - `1`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `nel-multiline`
  - `1`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
  - `0`: js-ir
- `ls-multiline`
  - `1`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `dot-star-lines`
  - `[a, , b, ]`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `word-boundaries`
  - `4`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `caret-from-index`
  - `false`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `lookbehind-from-index`
  - `b`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `matchAt-lookbehind`
  - `b`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `matchAt-caret`
  - `null`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm

Docs read (FULL text):


Existing tracker issues: none read. No tracker was queried for the cloudflare columns.

What is known: cloudflare-jvm records the probe as unavailable: the build refuses java.util.regex.Matcher.useAnchoringBounds, which Regex.matchAt calls, so the matchAt lines are replaced by a thrown UnsupportedOperationException in the Worker's copy of the source and the whole probe fails. Every other column is identical. No page or tracker issue was read for it. cloudflare-wasm prints what wasm prints.

What is NOT known: why the cloudflare-jvm output differs (TeaVM's class library, the bytebox build or the Worker runtime were not examined line by line); whether it is a defect or by design; whether a different bytebox or TeaVM release changes the result; whether a different workerd release differs (one runtime version per column); whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
