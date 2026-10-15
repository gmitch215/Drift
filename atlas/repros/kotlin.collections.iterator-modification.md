Draft for human review. Not filed.

# kotlin.collections.iterator-modification

This text was written by an agent from measured transcripts and pages it read. A person must rerun the probe, read the pages named below and rewrite it in their own words before anything is posted; the Kotlin maintainers do not want generated reports.

Question: Which structural changes during iteration throw, and which pass silently?

Classification now: unclassified (was platform-defined); cause letters: b, g; basis: verified.

Measured: Kotlin 2.4.20 on 16 columns of fixtures/atlas (js-ir on Node 26.8.2 arm64, android emulator arm64 API 37, ios simulator arm64, jvm Temurin 21 arm64, macos arm64 Native, wasm on Node 26.8.2 arm64, wasm in Chromium, Firefox and WebKit on x86-64 Linux, jvm 17, 21 and 25 on x86-64, linux x64, linux arm64, and cloudflare-wasm and cloudflare-jvm on workerd 1.20261001.1 under wrangler 4.146.0 on macOS arm64); Kotlin 2.5.0-Beta1 on android, ios, jvm, macos and wasm (fixtures/atlas/2.5.0-beta1) and on js-ir (fixtures/atlas-ladder/2.5.0-beta1, the standalone compiler). cloudflare-jvm is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the bytebox 1.0.3 Gradle plugin (TeaVM 0.15.0), so its Java classes are not a JDK. The rows also carry the transcripts in fixtures/atlas-extra (jvm-21-arm64, linux-ubuntu, mingw-wine). Single run per cell; no spread measured.

Helpers (not shown below): `line(label, v)` prints `label = v`; `outcome(label) { }` prints `ok:<value>` or `err:<ExceptionClass>`; `failure(label) { }` prints `err:<Class>:<message>`; `bits(d)` prints `d.toRawBits()` as unsigned hex.

Code for the differing lines (from the displayed probe source):

```kotlin
outcome("list-add") {
    val l = mutableListOf(1, 2, 3)
    for (x in l) if (x == 1) l.add(4)
    l
}
outcome("list-remove-first") {
    val l = mutableListOf(1, 2, 3)
    for (x in l) if (x == 1) l.remove(1)
    l
}
outcome("list-remove-second") {
    val l = mutableListOf(1, 2, 3)
    for (x in l) if (x == 2) l.remove(2)
    l
}
outcome("list-remove-last") {
    val l = mutableListOf(1, 2, 3)
    for (x in l) if (x == 3) l.remove(3)
    l
}
outcome("sublist-after-add") {
    val l = mutableListOf(1, 2, 3)
    val s = l.subList(0, 2)
    l.add(4)
    s.toList()
}
outcome("sublist-size-after-add") {
    val l = mutableListOf(1, 2, 3)
    val s = l.subList(0, 2)
    l.add(4)
    s.size
}
```

Results at 2.4.20, one row per distinct output (columns that agree share a row):

- `list-add`
  - `err:ConcurrentModificationException`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `ok:[1, 2, 3, 4]`: js-ir
- `list-remove-first`
  - `err:ConcurrentModificationException`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `ok:[2, 3]`: js-ir
- `list-remove-second`
  - `ok:[1, 3]`: cloudflare-wasm+ios+js-ir+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `err:ConcurrentModificationException`: android+cloudflare-jvm
- `list-remove-last`
  - `ok:[1, 2]`: android+cloudflare-jvm+cloudflare-wasm+ios+js-ir+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `err:ConcurrentModificationException`: jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm
- `sublist-after-add`
  - `err:ConcurrentModificationException`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `ok:[1, 2]`: js-ir
- `sublist-size-after-add`
  - `err:ConcurrentModificationException`: android+cloudflare-jvm+cloudflare-wasm+ios+jvm-17+jvm-21-arm64+jvm-21+jvm-25+jvm+linux-arm64+linux-ubuntu+linux+macos+mingw-wine+wasm-chromium+wasm-firefox+wasm-webkit+wasm
  - `ok:2`: js-ir

On 2.5.0-Beta1 (android, ios, jvm, macos and wasm from fixtures/atlas/2.5.0-beta1, js-ir from fixtures/atlas-ladder/2.5.0-beta1) the cells group as: android; ios+macos+wasm; js-ir; jvm.

Docs read (FULL text):
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/ArrayList.html : "the fail-fast behavior of an iterator cannot be guaranteed"
- https://developer.android.com/reference/java/util/ArrayList : "the fail-fast behavior of an iterator cannot be guaranteed"

Existing tracker issues (state read through the YouTrack REST API and the GitHub API on 2026-10-15; nothing was posted): KT-88775 Open; KT-89031 To be discussed.

What is known: Downgraded from platform-defined: the JDK and Android ArrayList pages call fail-fast behavior best effort, which covers the jvm family, android, Native and Wasm lines, but js-ir never throws on this probe (all six lines finish with a value) and no page read says Kotlin/JS does not check for modification. list-remove-last (JVM throws, others finish) and list-remove-second (Android throws, others finish) are the two lines the earlier note named. KT-88775 and KT-89031 are other iterator and sub list cases, not these lines. cloudflare-wasm prints what wasm prints; cloudflare-jvm prints what android prints.

What is NOT known: whether the divergent column is a defect or by design where no page read states the intended behavior; whether a newer compiler or stdlib than 2.5.0-Beta1 changes the result; whether a different device, runtime or workerd release of the same target differs (one run per cell); whether a TeaVM or bytebox release other than 0.15.0 and 1.0.3 changes the cloudflare-jvm column; whether the displayed snippet and the executed probe body are identical (they are two hand-written copies).
