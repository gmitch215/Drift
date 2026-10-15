Draft for human review. Not filed.

# kotlin.collections.tostring-forms

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: What do these values print through toString?

Classification now: unclassified (was unclassified); cause letters: a, c, g; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64); Kotlin 2.5.0-Beta1 on android, ios, jvm, macos and wasm (fixtures/atlas/2.5.0-beta1) and on js-ir (fixtures/atlas-ladder/2.5.0-beta1, the standalone compiler). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM 0.15.0), so its Java classes are not a JDK. The rows also carry the transcripts in fixtures/atlas-extra (jvm-21-arm64, linux-ubuntu, mingw-wine). Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
line("list-doubles", listOf(1.0, 2.5f, 1e10))
line("result-failure", Result.failure<Int>(IllegalStateException("x")))
line("kclass-int", Int::class)
line("kclass-list", listOf(1)::class.simpleName)
line("nested", mapOf("k" to listOf(1, 2), "e" to emptyList<Int>()))
line("pair-null", Pair(1, null))
line("triple", Triple('a', "b", 1L))
line("array-content", arrayOf(1, null).contentToString())
line("array-deep", arrayOf<Any>(arrayOf(1), intArrayOf(2)).contentDeepToString())
line("char-array", charArrayOf('a', 'b').contentToString())
line("range-step", 1..3 step 2)
line("empty-range", 1..0)
line("down-to", 3 downTo 1 step 2)
line("char-range", 'a'..'c')
line("long-range", 1L..3L)
line("unit", Unit)
line("null", null)
line("result-success", Result.success(1))
line("set-null", setOf(null))
line("map-null-value", mapOf(1 to null))
line("char-list", listOf('a', 'b'))
line("string-list", listOf("a b", ""))
line("regex", Regex("a+"))
line("regex-option", Regex("a", RegexOption.IGNORE_CASE))
line("enum", Classification.DOCUMENTED)
line("uint", 5u)
line("ulong-max", ULong.MAX_VALUE)
line("ubyte", 200.toUByte())
line("char-plus", 'a' + 1)
line("char-minus", 'c' - 'a')
line("kclass-simple", String::class.simpleName)
outcome("kclass-qualified") { String::class.qualifiedName }
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `list-doubles`
  - `[1.0, 2.5, 1.0E10]`: android+cloudflare-jvm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `[1.0, 2.5, 10000000000.0]`: cloudflare-wasm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `nested`
  - `{k=[1, 2], e=[]}`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `pair-null`
  - `(1, null)`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `triple`
  - `(a, b, 1)`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `array-content`
  - `[1, null]`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `array-deep`
  - `[[1], [2]]`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `char-array`
  - `[a, b]`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `range-step`
  - `1..3 step 2`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `empty-range`
  - `1..0`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `down-to`
  - `3 downTo 1 step 2`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `char-range`
  - `a..c`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `long-range`
  - `1..3`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `unit`
  - `kotlin.Unit`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `null`
  - `null`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `result-success`
  - `Success(1)`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `result-failure`
  - `Failure(kotlin.IllegalStateException: x)`: cloudflare-wasm+ios+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `Failure(java.lang.IllegalStateException: x)`: android+cloudflare-jvm+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm
  - `(absent)`: js-ir
- `set-null`
  - `[null]`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `map-null-value`
  - `{1=null}`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `char-list`
  - `[a, b]`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `string-list`
  - `[a b, ]`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `regex`
  - `a+`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `regex-option`
  - `a`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `enum`
  - `DOCUMENTED`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `uint`
  - `5`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `ulong-max`
  - `18446744073709551615`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `ubyte`
  - `200`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `char-plus`
  - `b`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `char-minus`
  - `2`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `kclass-int`
  - `class kotlin.Int`: cloudflare-wasm+ios+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `int (Kotlin reflection is not available)`: android+cloudflare-jvm+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm
  - `(absent)`: js-ir
- `kclass-simple`
  - `String`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: js-ir
- `kclass-qualified`
  - `ok:kotlin.String`: android+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `ok:java.lang.String`: cloudflare-jvm
  - `(absent)`: js-ir
- `kclass-list`
  - `ArrayList`: cloudflare-wasm+ios+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `SingletonList`: android+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm
  - ``: cloudflare-jvm
  - `(absent)`: js-ir

On 2.5.0-Beta1 (android, ios, jvm, macos and wasm from fixtures/atlas/2.5.0-beta1, js-ir from fixtures/atlas-ladder/2.5.0-beta1) the cells group as: android+jvm; ios+macos+wasm; js-ir.

Docs read (FULL text):
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-exception/ : "actual typealias Exception = java.lang.Exception"
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Throwable.html : "the name of the class of this object"
- https://kotlinlang.org/docs/js-reflection.html : "In Kotlin/JS, the value of a ::class expression is a stripped-down KClass implementation that supports only: simpleName and isInstance() members."

Existing tracker issues (state read through the YouTrack REST API and the GitHub API on 2026-10-15; nothing was posted): KT-88414 Fixed; KT-69456 Open; KT-78708 Fixed.

What is known: Five lines, four causes; the probe stays unclassified because kclass-list and kclass-qualified have none documented. js-ir is its own group: the probe does not compile there (the Kotlin/JS reflection guide limits ::class to simpleName and isInstance), so no js-ir value is shown for any line. The JVM exception name is documented by the typealias page and Throwable.toString; the Native and Wasm names are not on any page read. cloudflare-wasm prints what wasm prints; cloudflare-jvm differs from every other column on kclass-qualified and kclass-list.

What is NOT known: whether the divergent column is a defect or by design where no page read states the intended behavior; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device, runtime or workerd release of the same target differs (one run per cell); whether a TeaVM or bytebox release other than 0.15.0 and 1.0.3 changes the cloudflare-jvm column; whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
