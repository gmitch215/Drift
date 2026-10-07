Transcripts of the Kotlin probes built with the standalone Kotlin 2.4.20 compiler.
Each probe is its own source file made from the displayed probe source by `tools/ladder`. Probe files that
do not compile are dropped and recorded as UNAVAILABLE with the first compiler error.
- linux: Kotlin/Native linuxX64 debug executable, docker on x86_64 Linux
- js-ir: IR backend, Node 26.8.2
The jvm and macos transcripts for 2.4.20 are the files one directory up.
`linux.txt` here and `fixtures/atlas/linux.txt` are the same column (Linux Native, 2.4.20), and `atlas build` refuses both together, so a build over the ladder leaves this file out. They differ on 5 lines: two class names (the ladder compiles the displayed source) and three last-bit results of `kotlin.math.transcendental-bits`, which differ between an executable built on Linux and one cross-linked on macOS (see `fixtures/atlas/README.md`).
