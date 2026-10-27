# Limits

## What a Result Shows

- The ranking weights are hand-set. Scores order the candidates and are not probabilities, and a
  `plausible` tier candidate is more often wrong than right.
- `upgrade-check` reports what its rules cover. A breaking change that has no rule is not listed, and
  a rule can fire on code that the upgrade does not break.
- The `triage` claim check is experimental. It flags a statement only when the statement contradicts
  something Drift knows from a rule or from two Atlas columns.
- The Lab confirms a cause only under a rule fixed before the first trial, and `verify` checks the
  arithmetic of a case. It does not check that the experiment would repeat.
- Some Atlas probes depend on hardware and on the build host. NaN bit patterns and the last digits of
  some math functions differ between CPUs, and a Linux executable linked on Ubuntu prints different
  digits for three math probes than one linked on macOS. Each Atlas column is one run.

The measurements behind these statements are in the
[Evaluation and Limits](../TECHNICAL_REPORT.md#evaluation-and-limits) section of the technical report.

## Platforms

- **Docker:** `drift solve` needs Docker, and `docker info` must succeed. `drift reproduce` writes its
  files without Docker, and running `run.sh` needs it.
- **Linux:** the executables are built for glibc and do not run on musl systems such as Alpine. The
  `.deb` and `.rpm` packages need the desktop directories, so they do not install on a headless
  server.
- **macOS:** there is no Intel asset. The installers and the apps are not signed with a developer
  certificate.
- **Windows:** the installers are not signed, and SmartScreen blocks the first launch.
- **Web:** Studio is tested in Chromium, Firefox and WebKit through Playwright. Safari itself is not
  a tested browser. `drift serve` needs a Studio web build directory, which `./gradlew :cli:stageStudio`
  writes and the release archives do not hold.
- **Android:** the APK is signed with a debug key and is not published to a store. It is tested on an
  emulator.
- **iOS:** the build targets the simulator only. The iOS host cannot start processes, so process
  probes are unavailable there.
- **Android and iOS:** Studio replays the embedded Atlas transcripts and measures the live
  this-device probes. It does not sample the machine.
- **Wasm:** the Wasm host has no filesystem and no process access.

See [Install](install.md) for the downloads and the steps for each platform.

## Building from Source

The build uses Gradle 9.8.0 and AGP 9.4.1, newer than the Gradle 7.6.3 to 9.7.0 and AGP 8.5.2 to
9.3.1 that the
[Kotlin Multiplatform compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
lists for Kotlin 2.4.20. Coverage measures JVM code only, because Kotlin/Native and Wasm have no
coverage tool.
