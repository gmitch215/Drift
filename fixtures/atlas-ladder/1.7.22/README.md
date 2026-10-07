Transcripts of the Kotlin probes built with the standalone Kotlin 1.7.22 compiler.
Each probe is its own source file made from the displayed probe source by `tools/ladder`. Probe files that
do not compile are dropped and recorded as UNAVAILABLE with the first compiler error.
- jvm: compiled on JDK 21.0.8, run on JDK 21, arm64 Mac
- macos: Kotlin/Native macosArm64 debug executable, arm64 Mac
- linux: Kotlin/Native linuxX64 debug executable, docker on x86_64 Linux
- js-legacy: legacy backend, Node 26.8.2
- js-ir: IR backend, Node 26.8.2
