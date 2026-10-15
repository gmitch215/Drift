Draft for human review. Not filed.

# kotlin.math.transcendental-bits

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns cloudflare-wasm and cloudflare-jvm named below are measured transcripts in fixtures/atlas; the tools/cloudflare build in this repository produces them.

Question: Which last bits do the kotlin.math functions return?

Classification now: unclassified (was platform-defined); cause letters: b, d, f, g; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64); Kotlin 2.5.0-Beta1 on android, ios, jvm, macos and wasm (fixtures/atlas/2.5.0-beta1) and on js-ir (fixtures/atlas-ladder/2.5.0-beta1, the standalone compiler). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM 0.15.0), so its Java classes are not a JDK. The rows also carry the transcripts in fixtures/atlas-extra (jvm-21-arm64, linux-ubuntu, mingw-wine). Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
line("tan1", bits(tan(1.0)))
line("exp1", bits(exp(1.0)))
line("log10-1000", bits(log10(1000.0)))
line("log2-10", bits(log2(10.0)))
line("asin-half", bits(asin(0.5)))
line("acos-half", bits(acos(0.5)))
line("cosh1", bits(cosh(1.0)))
line("cbrt2", bits(cbrt(2.0)))
line("cbrt27", bits(cbrt(27.0)))
line("expm1-tiny", bits(expm1(1e-10)))
line("ln1p-tiny", bits(ln1p(1e-10)))
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `tan1`
  - `3ff8eb245cbee3a6`: android+cloudflare-jvm+cloudflare-wasm+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `3ff8eb245cbee3a5`: ios+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
- `exp1`
  - `4005bf0a8b145769`: android+cloudflare-jvm+ios+js-ir+jvm-17+jvm-21+jvm-25+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
  - `4005bf0a8b14576a`: cloudflare-wasm+jvm-21-arm64+jvm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
- `log10-1000`
  - `4008000000000000`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `4007ffffffffffff`: cloudflare-jvm
- `log2-10`
  - `400a934f0979a371`: cloudflare-wasm+ios+js-ir+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `400a934f0979a372`: android+cloudflare-jvm+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm
- `asin-half`
  - `3fe0c152382d7366`: android+cloudflare-jvm+cloudflare-wasm+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `3fe0c152382d7365`: ios+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
- `acos-half`
  - `3ff0c152382d7366`: android+cloudflare-jvm+cloudflare-wasm+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `3ff0c152382d7365`: ios+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
- `cosh1`
  - `3ff8b07551d9f551`: cloudflare-wasm+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `3ff8b07551d9f550`: android+cloudflare-jvm+ios+js-ir+linux-arm64+linux-ubuntu+linux+macos+mingw-wine
- `cbrt2`
  - `3ff428a2f98d728b`: android+cloudflare-jvm+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `3ff428a2f98d728c`: linux-arm64+linux-ubuntu+linux
- `cbrt27`
  - `4008000000000000`: android+cloudflare-jvm+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `4008000000000001`: linux-arm64+linux-ubuntu+linux
- `expm1-tiny`
  - `3ddb7cdfd9dda4e3`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `3ddb7cdfd9dda4e4`: mingw-wine
  - `3ddb7ce000000000`: cloudflare-jvm
- `ln1p-tiny`
  - `3ddb7cdfd9d1d693`: android+cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `3ddb7cdffffa18d8`: cloudflare-jvm

On 2.5.0-Beta1 (android, ios, jvm, macos and wasm from fixtures/atlas/2.5.0-beta1, js-ir from fixtures/atlas-ladder/2.5.0-beta1) the cells group as: android; ios+macos; js-ir; jvm; wasm.

Docs read (FULL text):
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Math.html : "all implementations of the equivalent functions of class Math are not defined to return the bit-for-bit same results"
- https://developer.android.com/reference/java/lang/Math : "all implementations of the equivalent functions of class Math are not defined to return the bit-for-bit same results"
- https://tc39.es/ecma262/#sec-math.acos : "Return an implementation-approximated Number value representing the inverse cosine"

Existing tracker issues: none linked to this entry.

What is known: Downgraded from platform-defined: the Math pages read allow results to differ in the last bit, which covers the JVM, Native, wasm and js-ir lines (JDK: not defined to return the same bits; ECMAScript: implementation-approximated), but cloudflare-jvm differs by far more on expm1-tiny (3ddb7ce000000000 against 3ddb7cdfd9dda4e3, about 6e8 ulps) and ln1p-tiny (3ddb7cdffffa18d8 against 3ddb7cdfd9d1d693), and log10-1000 is one ulp below 3 (4007ffffffffffff). TeaVM 0.15.0 (the version bytebox 1.0.3 pins) computes expm1 as exp(x) - 1, log1p as log(x + 1) and log10 as log(a) / 2.302585092994046, which explains the three lines and breaks the JDK bounds. js-ir is its own group on exp1, cosh1 and log2-10. The Native cbrt27 line has no explanation. cloudflare-wasm prints what wasm prints.

What is NOT known: whether the divergent column is a defect or by design where no page read states the intended behavior; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device, runtime or workerd release of the same target differs (one run per cell); whether a TeaVM or bytebox release other than 0.15.0 and 1.0.3 changes the cloudflare-jvm column; whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
