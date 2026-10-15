Draft for human review. Not filed.

# kotlin.regex.unicode-matching

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: What do \d, \w, \s, dot and ignore-case match outside ASCII?

Classification now: unclassified (was unclassified); cause letters: a, b, e, g; basis: inferred.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64); Kotlin 2.5.0-Beta1 on android, ios, jvm, macos and wasm (fixtures/atlas/2.5.0-beta1) and on js-ir (fixtures/atlas-ladder/2.5.0-beta1, the standalone compiler). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM 0.15.0), so its Java classes are not a JDK. The rows also carry the transcripts in fixtures/atlas-extra (jvm-21-arm64, linux-ubuntu, mingw-wine). Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
fun find(label: String, pattern: String, input: String) =
fun option(label: String, pattern: String, option: RegexOption, input: String) =
find("digit-arabic", "^\\d${'$'}", "٣")
find("digit-fullwidth", "^\\d${'$'}", "３")
find("word-accent", "^\\w${'$'}", "é")
find("word-greek", "^\\w${'$'}", "α")
find("space-nbsp", "^\\s${'$'}", " ")
find("space-em", "^\\s${'$'}", " ")
find("space-nel", "^\\s${'$'}", "\u0085")
find("boundary-accent", "a\\b", "aé")
option("ic-sharp-s", "^straße${'$'}", RegexOption.IGNORE_CASE, "STRASSE")
option("ic-final-sigma", "^σ${'$'}", RegexOption.IGNORE_CASE, "ς")
option("ic-dotted-i", "^i${'$'}", RegexOption.IGNORE_CASE, "İ")
option("ic-dotless-i", "^ı${'$'}", RegexOption.IGNORE_CASE, "I")
find("dot-nel", "^.${'$'}", "\u0085")
find("letter-deseret", "^\\p{L}${'$'}", "𐐀")
find("upper-deseret", "^\\p{Lu}${'$'}", "𐐀")
option("ic-kelvin", "^k${'$'}", RegexOption.IGNORE_CASE, "K")
option("ic-deseret", "^𐐨${'$'}", RegexOption.IGNORE_CASE, "𐐀")
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `digit-arabic`
  - `ok:false`: cloudflare-jvm+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `ok:true`: android
- `digit-fullwidth`
  - `ok:false`: cloudflare-jvm+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `ok:true`: android
- `word-accent`
  - `ok:false`: cloudflare-jvm+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `ok:true`: android
- `word-greek`
  - `ok:false`: cloudflare-jvm+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `ok:true`: android
- `space-nbsp`
  - `ok:false`: cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `ok:true`: android+js-ir
- `space-em`
  - `ok:false`: cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `ok:true`: android+js-ir
- `space-nel`
  - `ok:false`: cloudflare-jvm+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `ok:true`: android
- `boundary-accent`
  - `ok:false`: android+cloudflare-wasm+ios+jvm-17+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `ok:true`: cloudflare-jvm+js-ir+jvm-21-arm64+jvm-21+jvm-25+jvm
- `dot-nel`
  - `ok:false`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `ok:true`: js-ir
- `letter-deseret`
  - `ok:true`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `err:PatternSyntaxException`: cloudflare-jvm
- `upper-deseret`
  - `ok:true`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `err:PatternSyntaxException`: cloudflare-jvm
- `ic-sharp-s`
  - `ok:false`: cloudflare-jvm+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `ok:true`: android
- `ic-kelvin`
  - `ok:true`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `ok:false`: cloudflare-jvm
- `ic-final-sigma`
  - `ok:false`: cloudflare-wasm+ios+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `ok:true`: android+cloudflare-jvm+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm
- `ic-dotted-i`
  - `ok:true`: cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `ok:false`: android+cloudflare-jvm+js-ir
- `ic-dotless-i`
  - `ok:false`: android+cloudflare-jvm+cloudflare-wasm+ios+js-ir+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `ok:true`: jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm
- `ic-deseret`
  - `ok:false`: cloudflare-jvm+cloudflare-wasm+ios+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `ok:true`: android+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm

On 2.5.0-Beta1 (android, ios, jvm, macos and wasm from fixtures/atlas/2.5.0-beta1, js-ir from fixtures/atlas-ladder/2.5.0-beta1) the cells group as: android; ios+macos+wasm; js-ir; jvm.

Docs read (FULL text):
- https://developer.android.com/reference/java/util/regex/Pattern : "This flag is not supported on Android, and Unicode character classes are always used."
- https://www.oracle.com/java/technologies/javase/19-relnote-issues.html : "The \b metacharacter now matches ASCII word characters by default in the same way that the \w metacharacter does."
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/-regex/ : "Note that the pattern syntax and the option set has differences on each platform."

Existing tracker issues (state read through the YouTrack REST API and the GitHub API on 2026-10-15; nothing was posted): KT-51859 Open; KT-58195 Open; KT-53212 As Designed; KT-58678 Open.

What is known: Four groups of lines. Android: Unicode classes always on (documented). js-ir: ECMAScript \d, \w and \b are ASCII, \s covers every Zs code point (space-nbsp and space-em true, space-nel false) and . matches U+0085 (dot-nel true); these follow the ECMAScript definitions, which the Kotlin Regex page leaves to the platform. jvm-17 versus jvm-21 and jvm-25 on boundary-accent: JDK 19 note on \b (Native, Wasm and Android match JDK 17). Native and Wasm versus JVM on ic-final-sigma, ic-dotless-i, ic-deseret: unexplained; KT-51859 says UNICODE_CASE is always on in Native, which does not predict these lines, and KT-53212 (As Designed) keeps the JVM dotless i result. ic-dotted-i is false on android, js-ir and cloudflare-jvm. cloudflare-wasm prints what wasm prints; cloudflare-jvm differs from every other column on letter-deseret, upper-deseret, ic-kelvin.

What is NOT known: whether the divergent column is a defect or by design where no page read states the intended behavior; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device, runtime or workerd release of the same target differs (one run per cell); whether a TeaVM or bytebox release other than 0.15.0 and 1.0.3 changes the cloudflare-jvm column; whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
