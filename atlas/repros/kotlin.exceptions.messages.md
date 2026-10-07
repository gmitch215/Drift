Draft for human review. Not filed.

# kotlin.exceptions.messages

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports. The columns mingw-wine, linux-ubuntu and jvm-21-arm64 named below are measured transcripts kept in fixtures/atlas-extra.

Question: Which exception class and message does each common failure produce?

Classification now: unclassified (was unclassified); cause letters: e g; basis: inferred.

Measured: Kotlin 2.4.20 on 16 columns (android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, jvm 21 arm64 in a container, linux x64, linux arm64 and linux-ubuntu Native, mingw Native under wine); Kotlin 2.5.0-Beta1 on jvm, macos, wasm, android and ios. Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
val zero = "0".toInt()
failure("toInt") { "x".toInt() }
failure("toInt-empty") { "".toInt() }
failure("toLong") { "9999999999999999999".toLong() }
failure("toDouble") { "x".toDouble() }
failure("int-div-zero") { 1 / zero }
failure("long-div-zero") { 1L / zero }
failure("int-mod-zero") { 1 % zero }
failure("floorDiv-zero") { 1.floorDiv(zero) }
failure("list-index") { listOf(1)[3] }
failure("array-index") { arrayOf(1)[5] }
failure("string-index") { "abc"[10] }
failure("substring") { "abc".substring(5) }
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `toInt`
  - `err:NumberFormatException:For input string: "x"`: android, jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
  - `err:NumberFormatException:null`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `err:NumberFormatException:Invalid number format: 'x'`: wasm+3browsers
- `toInt-empty`
  - `err:NumberFormatException:For input string: ""`: android, jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
  - `err:NumberFormatException:null`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `err:NumberFormatException:Invalid number format: ''`: wasm+3browsers
- `toLong`
  - `err:NumberFormatException:For input string: "9999999999999999999"`: android, jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
  - `err:NumberFormatException:null`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `err:NumberFormatException:Invalid number format: '9999999999999999999'`: wasm+3browsers
- `toDouble`
  - `err:NumberFormatException:For input string: "x"`: android, jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
  - `err:NumberFormatException:null`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `err:NumberFormatException:Invalid number format: 'x'`: wasm+3browsers
- `int-div-zero`
  - `err:ArithmeticException:divide by zero`: android
  - `err:ArithmeticException:null`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `err:ArithmeticException:/ by zero`: jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
  - `err:ArithmeticException:Division by zero`: wasm+3browsers
- `long-div-zero`
  - `err:ArithmeticException:divide by zero`: android
  - `err:ArithmeticException:null`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `err:ArithmeticException:/ by zero`: jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
  - `err:ArithmeticException:Division by zero`: wasm+3browsers
- `int-mod-zero`
  - `err:ArithmeticException:divide by zero`: android
  - `err:ArithmeticException:null`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `err:ArithmeticException:/ by zero`: jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
  - `err:ArithmeticException:Division by zero`: wasm+3browsers
- `floorDiv-zero`
  - `err:ArithmeticException:divide by zero`: android
  - `err:ArithmeticException:null`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `err:ArithmeticException:/ by zero`: jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
  - `err:ArithmeticException:Division by zero`: wasm+3browsers
- `list-index`
  - `err:IndexOutOfBoundsException:Index: 3, Size: 1`: android, jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
  - `err:IndexOutOfBoundsException:index: 3, size: 1`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine, wasm+3browsers
- `array-index`
  - `err:ArrayIndexOutOfBoundsException:length=1; index=5`: android
  - `err:ArrayIndexOutOfBoundsException:null`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `err:ArrayIndexOutOfBoundsException:Index 5 out of bounds for length 1`: jvm+jvm-21+jvm-21-arm64+jvm-25, jvm-17
  - `err:IndexOutOfBoundsException:null`: wasm+3browsers
- `string-index`
  - `err:StringIndexOutOfBoundsException:length=3; index=10`: android
  - `err:ArrayIndexOutOfBoundsException:null`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `err:StringIndexOutOfBoundsException:Index 10 out of bounds for length 3`: jvm+jvm-21+jvm-21-arm64+jvm-25
  - `err:IndexOutOfBoundsException:null`: wasm+3browsers
  - `err:StringIndexOutOfBoundsException:String index out of range: 10`: jvm-17
- `substring`
  - `err:StringIndexOutOfBoundsException:length=3; index=5`: android
  - `err:ArrayIndexOutOfBoundsException:null`: ios+macos+linux+linux-arm64+linux-ubuntu+mingw-wine
  - `err:StringIndexOutOfBoundsException:Range [5, 3) out of bounds for length 3`: jvm+jvm-21+jvm-21-arm64+jvm-25
  - `err:IndexOutOfBoundsException:startIndex: 5 > endIndex: 3`: wasm+3browsers
  - `err:StringIndexOutOfBoundsException:begin 5, end 3, length 3`: jvm-17
- also differing, not expanded: `substring-range`, `subList`, `removeAt`, `negative-array`, `negative-list`, `removeFirst-empty`, `cast`, `null-cast`

On 2.5.0-Beta1 the cells group as: jvm; macos+ios; wasm; android.

Existing tracker issues (read through the YouTrack REST API on 2026-10-07; nothing was posted): KT-88829 Open.

What is known: No page read gives message text, so no line has a documented expectation. Class differences: Native string-index, substring and substring-range throw ArrayIndexOutOfBoundsException(null); Wasm throws IndexOutOfBoundsException; null-cast is ClassCastException on Wasm and NullPointerException elsewhere (KT-88829 Open is the generic-cast sibling, not this exact line). jvm-17 differs from jvm-21 in string-index, substring (message text) and removeFirst-empty (harness artefact: compiled on JDK 21, run on 17); no JDK release note for 18, 19, 20, 21 mentions the message change (searched). Android prints ART texts (length=3; index=10).

What is NOT known: the intended behavior where no page read states one; whether the divergent column is a defect or by design; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device of the same target differs (one device per column); whether the displayed snippet and the executed probe body are identical (they are two hand-written copies; the ladder agent re-compiled the displayed text and matched 37 of 38 probes on JVM and macOS Native).
