Transcripts of the Kotlin probes on 2.5.0-Beta1. `jvm.txt`, `macos.txt` and `wasm.txt` were
recorded 2026-10-06 on one Mac (macosArm64 debug executable, JVM 21, wasmJs on Node). `android.txt`
and `ios.txt` were recorded 2026-10-07 on an arm64 Android emulator (API 37) and an iPhone 17 Pro
simulator (iOS 26.5) from the same sources with the Kotlin pin changed in a scratch copy. The
`kotlin.version` header is the Kotlin version the build was compiled with, so it keeps the Beta
qualifier that `KotlinVersion.CURRENT` drops. A fresh `drift atlas record` on a 2.5.0-Beta1 build
writes `jvm.txt` byte for byte.
