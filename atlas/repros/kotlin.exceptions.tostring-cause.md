Draft for human review. Not filed.

# kotlin.exceptions.tostring-cause

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns cloudflare-wasm and cloudflare-jvm named below are measured transcripts in fixtures/atlas; the tools/cloudflare build in this repository produces them.

Question: What do toString, cause and the stack text of an exception look like?

Classification now: unclassified (was documented); cause letters: a; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM), so its Java classes are not a JDK. Single run per cell; no spread measured.

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
  - `dev.gmitch215.drift.scan.probe.kotlin.ProbeException: m`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
  - `probe16$ProbeException: m`: js-ir
- `simple-name`
  - `ProbeException`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `message`
  - `m`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `bare-toString`
  - `java.lang.Exception`: android+jvm-17+jvm-21+jvm-25+jvm
  - `(absent)`: cloudflare-jvm
  - `kotlin.Exception`: cloudflare-wasm+ios+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `Exception`: js-ir
- `bare-message`
  - `null`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `cause-null`
  - `null`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `cause-message`
  - `b`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `cause-only-message`
  - `java.lang.Exception: b`: android+jvm-17+jvm-21+jvm-25+jvm
  - `(absent)`: cloudflare-jvm
  - `kotlin.Exception: b`: cloudflare-wasm+ios+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `Exception: b`: js-ir
- `illegal-state`
  - `java.lang.IllegalStateException: x`: android+jvm-17+jvm-21+jvm-25+jvm
  - `(absent)`: cloudflare-jvm
  - `kotlin.IllegalStateException: x`: cloudflare-wasm+ios+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `IllegalStateException: x`: js-ir
- `npe`
  - `java.lang.NullPointerException`: android+jvm-17+jvm-21+jvm-25+jvm
  - `(absent)`: cloudflare-jvm
  - `kotlin.NullPointerException`: cloudflare-wasm+ios+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `NullPointerException`: js-ir
- `assertion`
  - `java.lang.AssertionError: x`: android+jvm-17+jvm-21+jvm-25+jvm
  - `(absent)`: cloudflare-jvm
  - `kotlin.AssertionError: x`: cloudflare-wasm+ios+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `AssertionError: x`: js-ir
- `not-implemented`
  - `kotlin.NotImplementedError: An operation is not implemented.`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
  - `NotImplementedError: An operation is not implemented.`: js-ir
- `stack-first-line`
  - `dev.gmitch215.drift.scan.probe.kotlin.ProbeException: m`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
  - `probe16$ProbeException: m`: js-ir
- `stack-multiline`
  - `true`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `caused-by-lines`
  - `1`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `suppressed`
  - `1`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `equality`
  - `false`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `same-instance`
  - `true`: android+cloudflare-wasm+ios+jvm-17+jvm-21+jvm-25+jvm+linux-arm64+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm

Docs read (FULL text):
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-exception/ : "actual typealias Exception = java.lang.Exception"
- https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-illegal-state-exception/ : "actual typealias IllegalStateException = java.lang.IllegalStateException"
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Throwable.html : "Returns a short description of this throwable. The result is the concatenation of: the name of the class of this object"

Existing tracker issues: KT-78708 Fixed; No tracker was queried for the cloudflare columns.

What is known: Downgraded from documented: the pages read explain the java.lang versus kotlin prefix (typealiases on the JVM), but on cloudflare-jvm the probe fails as a whole with a NullPointerException and is recorded as unavailable, which no page read covers. The cause inside TeaVM's class library was not isolated. cloudflare-wasm prints what wasm prints.

What is NOT known: why the cloudflare-jvm output differs (TeaVM's class library, the bytebox build or the Worker runtime were not examined line by line); whether it is a defect or by design; whether a different bytebox or TeaVM release changes the result; whether a different workerd release differs (one runtime version per column); whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
