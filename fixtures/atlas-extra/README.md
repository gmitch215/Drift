Atlas transcripts of Kotlin 2.4.20 that are measured and kept, but not part of the dataset or the
Studio build. `fixtures/atlas` is the only directory embedded.

| file | measured on | why it is not in `fixtures/atlas` |
| --- | --- | --- |
| `mingw-wine/mingw-wine.txt` | the mingw executable under Wine 9.0 on Linux | Wine is not Windows; the real column comes from a Windows runner |
| `linux-ubuntu/linux-ubuntu.txt` | the linuxX64 executable on ubuntu:24.04 (glibc 2.39) | identical to `linux` (debian:bookworm-slim, glibc 2.36) on all 38 probes |
| `jvm-21-arm64/jvm-21-arm64.txt` | the CLI JVM classes on Temurin 21, arm64 Linux container | identical to the committed `jvm` column (JDK 21, macOS arm64) on all 38 probes; only the target header differs |

The Ubuntu-linked `linux` and `linux-arm64` transcripts, which differ from the committed columns on
three lines, are in `fixtures/atlas-variants`.

`mingw-wine` differs from `linux` on `kotlin.math.transcendental-bits` only (expm1 of a tiny value),
so its NaN bits follow the x86-64 host and its last-bit results follow the mingw C library.
