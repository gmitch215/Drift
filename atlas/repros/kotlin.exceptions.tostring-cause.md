Draft for human review. Not filed.

# kotlin.exceptions.tostring-cause

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns cloudflare-wasm and cloudflare-jvm named below are measured transcripts in fixtures/atlas; the tools/cloudflare build in this repository produces them.

Question: What do toString, cause and the stack text of an exception look like?

Classification now: unclassified (was documented); cause letters: a, g; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64); Kotlin 2.5.0-Beta1 on android, ios, jvm, macos and wasm (fixtures/atlas/2.5.0-beta1) and on js-ir (fixtures/atlas-ladder/2.5.0-beta1, the standalone compiler). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM 0.15.0), so its Java classes are not a JDK. The rows also carry the transcripts in fixtures/atlas-extra (jvm-21-arm64, linux-ubuntu, mingw-wine). Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
line("toString", e.toString())
line("simple-name", e::class.simpleName)
line("message", e.message)
line("bare-toString", Exception().toString())
line("bare-message", Exception().message)
line("cause-null", Exception("a").cause)
line("cause-message", Exception("a", Exception("b")).cause?.message)
line("cause-only-message", Exception(Exception("b")).message)
line("illegal-state", IllegalStateException("x").toString())
line("npe", NullPointerException().toString())
line("assertion", AssertionError("x").toString())
line("not-implemented", NotImplementedError().toString())
line("stack-first-line", e.stackTraceToString().lines().first())
line("stack-multiline", e.stackTraceToString().lines().size > 1)
line("caused-by-lines", Exception("a", Exception("b")).stackTraceToString().lines()
line("suppressed", Exception("a").also { it.addSuppressed(Exception("s")) }
line("equality", Exception("a") == Exception("a"))
line("same-instance", e == e)
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `toString`
  - `dev.gmitch215.drift.scan.probe.kotlin.ProbeException: m`: android+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `probe16$ProbeException: m`: js-ir
  - `(absent)`: cloudflare-jvm
- `simple-name`
  - `ProbeException`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `message`
  - `m`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `bare-toString`
  - `kotlin.Exception`: cloudflare-wasm+ios+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `java.lang.Exception`: android+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm
  - `Exception`: js-ir
  - `(absent)`: cloudflare-jvm
- `bare-message`
  - `null`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `cause-null`
  - `null`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `cause-message`
  - `b`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `cause-only-message`
  - `kotlin.Exception: b`: cloudflare-wasm+ios+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `java.lang.Exception: b`: android+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm
  - `Exception: b`: js-ir
  - `(absent)`: cloudflare-jvm
- `illegal-state`
  - `kotlin.IllegalStateException: x`: cloudflare-wasm+ios+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `java.lang.IllegalStateException: x`: android+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm
  - `IllegalStateException: x`: js-ir
  - `(absent)`: cloudflare-jvm
- `npe`
  - `kotlin.NullPointerException`: cloudflare-wasm+ios+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `java.lang.NullPointerException`: android+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm
  - `NullPointerException`: js-ir
  - `(absent)`: cloudflare-jvm
- `assertion`
  - `kotlin.AssertionError: x`: cloudflare-wasm+ios+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `java.lang.AssertionError: x`: android+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm
  - `AssertionError: x`: js-ir
  - `(absent)`: cloudflare-jvm
- `not-implemented`
  - `kotlin.NotImplementedError: An operation is not implemented.`: android+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `NotImplementedError: An operation is not implemented.`: js-ir
  - `(absent)`: cloudflare-jvm
- `stack-first-line`
  - `dev.gmitch215.drift.scan.probe.kotlin.ProbeException: m`: android+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `probe16$ProbeException: m`: js-ir
  - `(absent)`: cloudflare-jvm
- `stack-multiline`
  - `true`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `caused-by-lines`
  - `1`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `suppressed`
  - `1`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `equality`
  - `false`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `same-instance`
  - `true`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm

On 2.5.0-Beta1 (android, ios, jvm, macos and wasm from fixtures/atlas/2.5.0-beta1, js-ir from fixtures/atlas-ladder/2.5.0-beta1) the cells group as: android+jvm; ios+macos+wasm; js-ir.

Docs read (FULL text):
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-exception/ : "actual typealias Exception = java.lang.Exception"
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-illegal-state-exception/ : "actual typealias IllegalStateException = java.lang.IllegalStateException"
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Throwable.html : "Returns a short description of this throwable. The result is the concatenation of: the name of the class of this object"

Existing tracker issues (state read through the YouTrack REST API and the GitHub API on 2026-10-15; nothing was posted): KT-78708 Fixed.

What is known: Downgraded from documented: the pages read explain the java.lang versus kotlin prefix (a typealias on the JVM, own classes on Native and Wasm), but two groups are not explained. js-ir prints bare names (Exception, IllegalStateException) and the user class as probe16$ProbeException: m; the Throwable.toString text says the name is fully qualified if possible, which reads for the bare names but not for the probe16$ form. On cloudflare-jvm the probe fails as a whole with a NullPointerException and is recorded as unavailable; no page or tracker item read covers the TeaVM class library there. cloudflare-wasm prints what wasm prints.

What is NOT known: whether the divergent column is a defect or by design where no page read states the intended behavior; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device, runtime or workerd release of the same target differs (one run per cell); whether a TeaVM or bytebox release other than 0.15.0 and 1.0.3 changes the cloudflare-jvm column; whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
