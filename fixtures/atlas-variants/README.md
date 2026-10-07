Atlas transcripts of Kotlin 2.4.20 for builds that print different bits from the committed column of
the same target. They are not part of the dataset or the Studio build (`fixtures/atlas` is the only
directory embedded); the native tests read them. A file is named `<target>@<build host>.txt`.

| file | target | build host | recorded |
| --- | --- | --- | --- |
| `linux@linux.txt` | `linux` | Linux | release executable linked on Ubuntu 24.04.5 (glibc 2.39) and on Ubuntu 26.04.1 (glibc 2.43), run in a debian:bookworm-slim container (glibc 2.36), x86-64 |
| `linux-arm64@linux.txt` | `linux-arm64` | Linux | the same two builds for linuxArm64, run in an arm64 debian:bookworm-slim container |

The committed `fixtures/atlas/linux.txt` and `fixtures/atlas/linux-arm64.txt` were linked on macOS,
so they are the `macos` build-host variants of those targets.

## What Differs

The variants differ from the committed columns on three lines of `kotlin.math.transcendental-bits`
and nowhere else:

| line | committed (linked on macOS) | variant (linked on Linux) |
| --- | --- | --- |
| `tan1` | `3ff8eb245cbee3a5` | `3ff8eb245cbee3a6` |
| `asin-half` | `3fe0c152382d7365` | `3fe0c152382d7366` |
| `acos-half` | `3ff0c152382d7365` | `3ff0c152382d7366` |

## Measured

Each row is `drift atlas record` of one executable built from the same sources, run in a Debian
container for its architecture. A cell is `a5` when the three lines above end in `...a5`, `...65`,
`...65` and `a6` when they end in `...a6`, `...66`, `...66`.

| target | linked on macOS, release | linked on macOS, debug | linked on Ubuntu, release | linked on Ubuntu, debug |
| --- | --- | --- | --- | --- |
| linux x64 | a5 (the committed file) | a6 | a6 | a6 |
| linux arm64 | a5 (the committed file) | a6 | a6 | a6 |

The Ubuntu release transcripts were identical for the 24.04.5 and the 26.04.1 build host. The
debug test binaries of `scan` and `cli`, linked on macOS and run on Linux, fail the committed
transcript on the same three lines and agree with the variants.

A throwaway program (`tan(1.0)` written as a constant and as a value read at run time, linuxX64
release) printed `a5` for the constant and `a6` for the run-time value when linked on macOS, and `a6`
for both when linked on Ubuntu. The macOS executable of the same program printed `a5` for both. The
same program linked as debug printed `a6` for both on either build host.

## Cause

Measured: in a release executable linked on macOS, the three values that appear as constants in the
probes are the ones Apple's `libm` returns, and in the throwaway program the same executable
computes the glibc value when the argument is only known at run time. A debug executable keeps the run-time value.

Inferred, not shown by reading the compiler: an optimizing build evaluates `tan`, `asin` and `acos`
on constant arguments while compiling, with the `libm` of the machine that runs the compiler, as
LLVM's constant folder does, and a debug build does not fold. Nothing here inspected the
Kotlin/Native compiler to confirm that.

## How the Tests Use It

Native test binaries are debug builds, so a Linux test binary computes the glibc value at run time
whatever machine linked it. `KotlinProbeTest` and `AtlasCommandTest` therefore compare it with
`<target>@linux.txt`, byte for byte. If the file for a target is missing they fail and print the
`drift atlas record --target` command to run.

## Recording

On a Linux host (x86-64 for `linux`, arm64 for `linux-arm64`), link the release executable
(`./gradlew :cli:linkReleaseExecutableLinuxX64`, or `LinuxArm64`), record it, and rename the file:

```
cli/build/bin/linuxX64/releaseExecutable/drift.kexe atlas record --target linux --out /tmp/atlas
mv /tmp/atlas/linux.txt fixtures/atlas-variants/linux@linux.txt
```
