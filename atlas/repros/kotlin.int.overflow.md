Draft for human review. Not filed.

# kotlin.int.overflow

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns cloudflare-wasm and cloudflare-jvm named below are measured transcripts in fixtures/atlas; the tools/cloudflare build in this repository produces them.

Question: What do Int and Long arithmetic and shifts do at the edges?

Classification now: unclassified (was documented); cause letters: f; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64); Kotlin 2.5.0-Beta1 on android, ios, jvm, macos and wasm (fixtures/atlas/2.5.0-beta1) and on js-ir (fixtures/atlas-ladder/2.5.0-beta1, the standalone compiler). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM 0.15.0), so its Java classes are not a JDK. The rows also carry the transcripts in fixtures/atlas-extra (jvm-21-arm64, linux-ubuntu, mingw-wine). Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
line("max+1", Int.MAX_VALUE + one)
line("min-1", min - one)
line("negate-min", -min)
line("min/-1", min / m1)
line("min%-1", min % m1)
line("long-min/-1", Long.MIN_VALUE / m1)
line("long-min%-1", Long.MIN_VALUE % m1)
line("max*max", Int.MAX_VALUE * Int.MAX_VALUE)
line("long-max+1", Long.MAX_VALUE + one)
line("shl-32", one shl 32)
line("shl-33", one shl 33)
line("shl-neg1", one shl m1)
line("long-shl-64", one.toLong() shl 64)
line("long-shl-65", one.toLong() shl 65)
line("ushr-28", m1 ushr 28)
line("shr-neg", -8 shr one)
line("long-ushr", -8L ushr one)
line("byte-200", 200.toByte())
line("short-70000", 70000.toShort())
line("long-max-to-int", Long.MAX_VALUE.toInt())
line("long-min-to-int", Long.MIN_VALUE.toInt())
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `max+1`
  - `-2147483648`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `min-1`
  - `2147483647`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `negate-min`
  - `-2147483648`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `min/-1`
  - `-2147483648`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `min%-1`
  - `0`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `long-min/-1`
  - `-9223372036854775808`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `long-min%-1`
  - `0`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `max*max`
  - `1`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `long-max+1`
  - `-9223372036854775808`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `shl-32`
  - `1`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `shl-33`
  - `2`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `shl-neg1`
  - `-2147483648`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `long-shl-64`
  - `1`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `long-shl-65`
  - `2`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `ushr-28`
  - `15`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `shr-neg`
  - `-4`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `long-ushr`
  - `9223372036854775804`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `byte-200`
  - `-56`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `short-70000`
  - `4464`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `long-max-to-int`
  - `-1`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm
- `long-min-to-int`
  - `0`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `(absent)`: cloudflare-jvm

On 2.5.0-Beta1 (android, ios, jvm, macos and wasm from fixtures/atlas/2.5.0-beta1, js-ir from fixtures/atlas-ladder/2.5.0-beta1) all six measured columns agree.

Docs read (FULL text):
- https://kotlinlang.org/docs/numbers.html : "If the result of an operation falls outside that range, overflow occurs."
- https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html#jls-15.17.2 : "Despite the overflow, no exception is thrown in this case."

Existing tracker issues (state read through the YouTrack REST API and the GitHub API on 2026-10-15; nothing was posted): teavm#1249 Open.

What is known: Downgraded from documented: cloudflare-jvm records the probe as unavailable. The compiled module traps on the integer division by -1 (RuntimeError: divide result unrepresentable) and a trap aborts the request, so the probe is skipped, not run. teavm#1249 (Open) describes the same trap: Integer.MIN_VALUE / -1 gives no result where JLS 15.17.2 says the result is the dividend with no exception. Every other column is identical and matches the numbers page, which says nothing about a trap. js-ir matches the majority on every line. cloudflare-wasm prints what wasm prints.

What is NOT known: whether the divergent column is a defect or by design where no page read states the intended behavior; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device, runtime or workerd release of the same target differs (one run per cell); whether a TeaVM or bytebox release other than 0.15.0 and 1.0.3 changes the cloudflare-jvm column; whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
