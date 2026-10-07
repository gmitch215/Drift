<div style="display: flex; align-items: center; flex-direction: column;" align="center">
    <img align="center" style="align-self: center; max-width: 256px" src="assets/drift.png" width="30%" alt="Drift logo" />
    <h1 style="text-align: center;">Drift</h1>
    <p style="text-align: center;">Why it passes here and fails there</p>
    <div align="center">
        <img src="https://img.shields.io/github/actions/workflow/status/gmitch215/Drift/build.yml?label=build" alt="build">
        <img src="https://img.shields.io/github/license/gmitch215/Drift" alt="license">
        <img src="https://img.shields.io/codecov/c/github/gmitch215/Drift" alt="codecov">
        <img src="https://img.shields.io/github/stars/gmitch215/Drift?style=flat" alt="stars">
        <img src="https://img.shields.io/github/commit-activity/t/gmitch215/Drift?color=violet" alt="commit activity">
    </div>
</div>

---

Drift diagnoses a program that passes in one environment and fails in another. It records each
environment as a capsule, ranks what differs between a passing run and a failing one, proposes the
experiment that would remove the most uncertainty, runs it, and writes a certificate that a second
command can verify. It is written in Kotlin Multiplatform and runs as a command line tool, a desktop
app, a web app, an Android app and an iOS app from the same shared code.

---

## Table of Contents

- [What It Does](#what-it-does)
- [The Kotlin Portability Atlas](#the-kotlin-portability-atlas)
- [Install and Run](#install-and-run)
- [Try It](#try-it)
- [Architecture](#architecture)
- [Shared Code](#shared-code)
- [Status and Limits](#status-and-limits)
- [Documentation](#documentation)
- [License](#license)

---

## What It Does

1. **Capture.** `drift capture` fingerprints a machine as a capsule: operating system, toolchains,
   environment variables (secret values redacted), limits, and the results of small probes. The
   capsule is canonical JSON with a SHA-256 hash, so the same capsule gives the same bytes on every
   target.
2. **Diff and rank.** `drift diagnose` compares the capsules of a passing and a failing run and ranks
   the attributes that differ into explicit tiers. A set of changes the data cannot separate is
   reported as a bundle, and the tool says when no single cause is named.
3. **Plan.** The planner prices each possible experiment in expected bits and proposes the one that
   removes the most uncertainty. Experiments no hosted runner can run are marked as such.
4. **Run.** `drift solve` runs the experiment in containers under a decision rule fixed before the
   first trial. It needs Docker.
5. **Certify.** `drift pack` archives the case as a `.driftcase` file and `drift verify` re-derives
   every count, p-value and verdict from the archive alone. It does not re-run an experiment.

`drift reproduce` writes a `Dockerfile` and a run script that mirror a capsule as far as a container
can. The full command reference is in [ADVANCED_USAGE.md](./ADVANCED_USAGE.md).

---

## The Kotlin Portability Atlas

Kotlin code that compiles everywhere does not always print the same thing everywhere. The Atlas holds
38 probes of Kotlin behavior. Each probe is a small program with a recorded output per target.
Thirteen columns are recorded on Kotlin 2.4.20: the JVM on JDK 17, 21 and 25, Linux x64 and arm64
and macOS native, Wasm on Node, Chromium, Firefox and WebKit, Android and the iOS simulator. Five more
(JVM, macOS native, Wasm, Android and iOS) are recorded on Kotlin 2.5.0-Beta1.

23 probes differ between targets on 2.4.20, and 18 on 2.5.0-Beta1. Every probe carries a
classification from primary sources: 18 are `documented` (a Kotlin page states the behavior), 4 are
`platform-defined` (a JDK or ECMAScript page does) and 16 are `unclassified`, with the search that
found no explanation. A divergence is data, not a verdict that something is a bug.

Studio opens on the Atlas. The matrix groups columns by target family and adds a column for the
device it runs on, measured live. A predict-and-reveal mode asks for the output you expect on a
target, then shows the recorded one.

`drift atlas record`, `build`, `compare` and `show` do the same work from the command line.
`./gradlew atlasSite` builds the static site: Studio at the root, a page per probe and the dataset
as JSON. The `docs.yml` workflow publishes that site and the engine documentation to GitHub Pages
when a release is published or the workflow is run by hand.

---

## Install and Run

The first release has not been published. The download links and the `brew` and `choco` commands
below work once it is. Until then, build from source.

### Downloads

Assets are on the [releases page](https://github.com/gmitch215/Drift/releases). Every asset has a
`.sha256` file, and `SHA256SUMS` lists them all.

| Platform | Studio | Command line |
| --- | --- | --- |
| macOS, Apple silicon | `drift-1.0.0-macos-arm64.dmg`, `drift-1.0.0-macos-arm64.pkg` | `drift-1.0.0-macos-arm64.tar.gz` |
| Windows x64 | `drift-1.0.0-windows-x64.msi`, `drift-1.0.0-windows-x64.exe` | `drift-1.0.0-windows-x64.zip` |
| Linux x64 | `drift-1.0.0-linux-x64.AppImage`, `.deb`, `.rpm` | `drift-1.0.0-linux-x64.tar.gz` |
| Linux arm64 | | `drift-1.0.0-linux-arm64.tar.gz` |
| Android | `drift-1.0.0-android.apk` | |

There is no Intel macOS asset. The Linux executables are built for glibc and do not run on musl
systems such as Alpine. An archive holds `drift` (`drift.exe` on Windows), `LICENSE` and a short
`README.txt`.

```sh
shasum -a 256 -c --ignore-missing SHA256SUMS
```

The `.deb` and `.rpm` packages are desktop packages. Their install script needs the standard
desktop directories, so they do not install on a headless server. Make the AppImage executable with
`chmod +x` and run it, or pass `--appimage-extract-and-run` where FUSE is missing.

### Unsigned Apps

The macOS and Windows installers are not signed with a developer certificate, so the first launch is
blocked.

- **macOS:** Gatekeeper rejects the app. Open System Settings, Privacy and Security, and choose Open
  Anyway, or run `xattr -dr com.apple.quarantine /Applications/Drift.app`.
- **Windows:** SmartScreen shows "Windows protected your PC". Choose More info, then Run anyway.
- **Android:** the APK is signed with a debug key. Allow installs from your browser or file manager.
  An update over an older sideloaded build needs `adb uninstall dev.gmitch215.drift` first, because
  each build machine signs with its own key.

### Homebrew

```sh
brew install gmitch215/tap/drift
brew install --cask gmitch215/tap/drift-studio
```

Use the full tap path, because other taps carry a formula named `drift`. The cask is Apple silicon
only and clears the quarantine attribute after install.

### Chocolatey

```sh
choco install drift
choco install drift-studio
```

### Install Script

```sh
curl -fsSL https://raw.githubusercontent.com/gmitch215/Drift/main/install.sh | sh
```

On Windows, in PowerShell:

```powershell
irm https://raw.githubusercontent.com/gmitch215/Drift/main/install.ps1 | iex
```

Each script finds your OS and architecture, downloads the archive and its `.sha256` file from the
latest release, checks the digest, runs the executable's `--version` and hands over to `drift install`.
Flags go after `sh -s --` (`install.sh --dry-run` from a saved copy): `--user`, `--global`, `--dir`,
`--no-modify-path` and `--dry-run`. `DRIFT_VERSION=1.0.0` pins a release. `install.ps1` takes the same
options as `-User`, `-Global`, `-Dir`, `-NoModifyPath` and `-DryRun` when saved with
`irm ... -OutFile install.ps1`.

### Drift Install

`drift install` copies the executable you are running to a directory on your path and records what it
changed in a receipt. `drift uninstall` reverses exactly that.

```sh
drift install --dry-run
```

By default it installs for the current user, into `~/.local/bin` (`%LOCALAPPDATA%\Programs\Drift` on
Windows), and appends a marked block to the shell profiles that exist when the directory is not on
`PATH`. Windows edits the user `Path` value in the registry. `--global` installs into `/usr/local/bin`
(`%ProgramFiles%\Drift`), never calls `sudo`, and prints the command to run when the directory is not
writable. `--no-modify-path` or `DRIFT_NO_MODIFY_PATH=1` skips the path edit. The JVM build cannot
install itself. See [ADVANCED_USAGE.md](./ADVANCED_USAGE.md#install-and-uninstall) for the full
behavior.

### Build from Source

Clone the repository. Every target builds from the Gradle wrapper, and the build downloads the JDK 21
toolchain if one is missing. A cold build downloads toolchains and dependencies and takes minutes.

| Target | Needs | Command |
| --- | --- | --- |
| CLI on the JVM | JDK 21 | `./gradlew :cli:jvmRun --args=--version` |
| CLI, macOS arm64 | macOS, Xcode command line tools | `./gradlew :cli:linkReleaseExecutableMacosArm64` |
| CLI, Linux x64 and arm64 | any host Kotlin/Native supports | `./gradlew :cli:linkReleaseExecutableLinuxX64` and `...LinuxArm64` |
| CLI, Windows x64 | any host Kotlin/Native supports | `./gradlew :cli:linkReleaseExecutableMingwX64` |
| Studio on the desktop | JDK 21 | `./gradlew :studio:run` |
| Studio on the web | JDK 21, a browser | `./gradlew :cli:stageStudio`, then `drift serve` |
| Studio on Android | Android SDK with platform 37 | see [Android](#android) |
| Studio on iOS | macOS, Xcode with a simulator runtime | see [iOS Simulator](#ios-simulator) |

The installers are built by `release.yml` with `./gradlew :studio:packageDmg :studio:packagePkg` on
macOS, `:studio:packageDeb :studio:packageRpm :studio:packageAppImage` on Linux and
`:studio:packageMsi :studio:packageExe` on Windows (WiX Toolset). `drift solve` also needs Docker.
Everything else runs without it.

### Command Line

Executables land in `cli/build/bin/<target>/releaseExecutable/`. The macOS executable is `drift.kexe`
and the Windows one is `drift.exe`.

```sh
alias drift=cli/build/bin/macosArm64/releaseExecutable/drift.kexe
drift --version
```

```
drift version 1.0.0 (macos)
```

On the JVM, `./gradlew :cli:jvmRun --args=--version` prints `drift version 1.0.0 (jvm)`, and any other
command goes in `--args`. The macOS executable and the JVM build were run. The Linux arm64 and x64
executables ran inside Debian containers. The Windows executable ran under Wine, not on Windows.

### Studio on the Desktop

```sh
./gradlew :studio:run
```

A window opens on the Atlas, with the Investigation view on the second tab.

### Studio on the Web

`drift serve` serves the web build from a directory, read-only, on the loopback interface.
`:cli:stageStudio` builds the Wasm distribution and copies it to `cli/build/serve/studio`.

```sh
./gradlew :cli:stageStudio
drift serve --dir cli/build/serve/studio
```

Open `http://localhost:8080/`. The page title is "Drift Studio". `--port 0` takes a free port,
`--cert` and `--key` (or `--mkcert` with an existing mkcert CA) serve HTTPS on the JVM, macOS and
Linux builds, and `--domain` adds names such as `drift.local`. Any static server works too: serve
`studio/build/dist/wasmJs/productionExecutable` after `./gradlew :studio:wasmJsBrowserDistribution`.
Nothing hosts the site until `docs.yml` has run.

### Android

Android and iOS builds are off by default so that a fresh clone needs only a JDK. Pass
`-Pdrift.mobile=true` to turn them on. The Android build is a debug APK for an emulator or a device
with USB debugging.

```sh
./gradlew -Pdrift.mobile=true :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb shell am start -n dev.gmitch215.drift/dev.gmitch215.drift.android.MainActivity
```

On an arm64 emulator (Pixel 7, API 37) the cold build took 3m26s and the APK is about 10 MB.
`./gradlew -Pdrift.mobile=true :androidApp:packageApk` writes the release APK that the release
workflow attaches, `androidApp/build/distributions/drift-1.0.0-android.apk`. It is signed with the
debug key. Nothing is published to a store.

### iOS Simulator

```sh
xcodebuild -project iosApp/iosApp.xcodeproj -target iosApp -configuration Debug \
  -sdk iphonesimulator ARCHS=arm64 ONLY_ACTIVE_ARCH=YES SYMROOT=build/xcode build
xcrun simctl list devices available
xcrun simctl boot <device-udid>
xcrun simctl install <device-udid> build/xcode/Debug-iphonesimulator/iosApp.app
xcrun simctl launch <device-udid> dev.gmitch215.drift
```

The Xcode project calls `./gradlew -Pdrift.mobile=true :studio:embedAndSignAppleFrameworkForXcode`
in a build phase. Its `PATH` adds `/opt/homebrew/opt/openjdk/bin`, `/opt/homebrew/bin` and
`/usr/local/bin`; a JDK installed elsewhere needs the script phase edited. No signing or provisioning
is involved, and the build targets the simulator only.

On Android and iOS, Studio replays the embedded Atlas transcripts and measures the live this-device
probes. It does not sample the machine. The iOS host cannot start processes, so process probes are
unavailable there.

---

## Try It

The examples run from the repository root against the files in `fixtures/`. They assume the `drift`
alias from the command line section.

**Capture this machine.**

```sh
drift capture --label ci > ci.json
shasum -a 256 ci.json
```

**Diagnose a real failure.** `fixtures/drangler` holds capsules from a CI run that went from passing
to failing. `--detail` prints text instead of JSON, at three levels (`summary`, `detail`, `full`).

```sh
G=fixtures/drangler/capsules/36702683742.json
R=fixtures/drangler/capsules/36995781138.json
drift diagnose --detail summary $G $R
```

```
8 changes differ with the same support, and tool.node.version ranks first only by hand-set weights,
so the data does not name one cause.
```

Use `--detail detail` for the ranked candidates, the bundle and the proposed next experiment.

**Reproduce an environment.** This writes a `Dockerfile`, `run.sh` and `manifest.json` into `repro/`.
The manifest lists what a container cannot mirror.

```sh
drift reproduce fixtures/lab/node-alpine.json --run "npm test" --out repro
```

**Verify a packed case.** `fixtures/lab/cases` holds four solved cases. `verify` runs 14 checks on the
archive itself.

```sh
drift verify fixtures/lab/cases/locale-tz-date-2.driftcase | tail -3
```

```
verdict: CONFIRMED: this dimension causes the failure: env.TZ. Setting it to the failing values moved the failure count from 0 of 5 to 5 of 5 (exact one-sided p 0.003968) and back from 5 of 5 to 0 of 5 when the failing side was set to the passing values (p 0.003968)
experiments are not re-run; the recorded results are taken as given
verify: ok
```

**Install into a scratch directory.** `--dry-run` prints every change and makes none. Without it, the
same command installs, and `drift uninstall` removes what the receipt lists.

```sh
drift install --dir "$PWD/scratch-bin" --no-modify-path --dry-run
```

```
dry run: nothing is changed
scope: user
create directory /path/to/scratch-bin
write /path/to/scratch-bin/drift (mode 0755, 6742776 bytes)
create directory ~/.config
create directory ~/.config/drift
PATH is not modified
write receipt ~/.config/drift/install-receipt.json
```

**Serve Studio.** Stage the web build once, then serve it on a free port and fetch the page headers:

```sh
./gradlew :cli:stageStudio
drift serve --dir cli/build/serve/studio --port 0
```

```
drift serve 1.0.0: serving /path/to/studio
http://localhost:56990/
http://127.0.0.1:56990/
http://[::1]:56990/
press Ctrl+C to stop
```

```sh
curl -sI http://localhost:56990/
```

```
HTTP/1.1 200 OK
Content-Type: text/html; charset=utf-8
Cache-Control: no-cache
Content-Length: 508
Connection: close
X-Content-Type-Options: nosniff
Cross-Origin-Resource-Policy: same-origin
```

**Record the Atlas for this machine.** The transcript is byte-identical to the one in the repository
for the same target and Kotlin version.

```sh
drift atlas record --out tr
```

```
wrote 38 probes for macos on kotlin 2.4.20 to tr/macos.txt
```

---

## Architecture

| Module | Owns |
| --- | --- |
| `core` | Capsule model, canonical JSON, SHA-256, fixed-point math, diff, ranking, rules, planner, Lab. Common code only. |
| `host` | Operating system access, one implementation per target (JVM, Linux, macOS, Windows, Wasm, Android, iOS). |
| `scan` | Probes, toolchain scanners, the Atlas dataset builder and the probe classification. |
| `cli` | The `drift` command, including `install`, `uninstall` and `serve`. |
| `studio` | The Compose Multiplatform app: Atlas and Investigation views for desktop, web, Android and iOS, and the installer packaging. |
| `bench` | DriftBench, a seeded benchmark of container pairs with an injected cause. |
| `tools` | Fixture freezing, Atlas ladder tooling and the installer test scripts. |
| `androidApp`, `iosApp` | The Android and iOS app shells. They exist only under `-Pdrift.mobile=true`. |
| `packaging` | Homebrew formula and cask and Chocolatey package templates, rendered by `./gradlew renderPackaging` after a release. |
| `atlas` | Hand-written probe classification, column descriptions and repro drafts in YAML. The build embeds the classification and builds the static site from it. |
| `build-core` | Gradle convention plugins and build tasks: rules, Atlas site, packaging templates, Dokka. |
| `rules` | Hand-written rule files in YAML. The build generates the JSON that `core` reads. |
| `fixtures` | Recorded capsules, Atlas transcripts and solved cases. |
| `install.sh`, `install.ps1` | Download, verify and install a release. |

---

## Shared Code

Kotlin common code holds most of the program. `core` and `scan` have no platform source sets, and
`host` holds every `expect` and `actual` pair. `./gradlew sharedRatio` prints common and platform
lines per module and fails the build if a floor or budget is broken:

```
module     common  platform  shared
host          176       388   31.2%
core         9712         0  100.0%
scan         3542         0  100.0%
cli          2576      1549   62.4%
studio        822        67   92.5%
bench        1996       466   81.1%
total       18824      2470   88.4%
```

The platform lines of `cli` are the file, process and socket code behind `install` and `serve`. The
ratio counts the default targets: JVM, Linux, macOS, Windows and Wasm. `core` and the common code of
`bench` use no `Double` or `Float`, so their results are the same on every target.

---

## Status and Limits

The command line tool, Studio on desktop and web, the Atlas, the Lab, `verify`, `install` and `serve`
are implemented and tested. The Android and iOS apps build and launch on an emulator and a simulator.
No real Android or iOS device has been tried, and the iOS build is a simulator build.

What a result does and does not show:

- The ranking weights are hand-set. On the development benchmark the ranker beats a random order at the
  top only narrowly, and a `plausible` tier candidate is more often wrong than right.
- The Lab confirms a cause only under a rule fixed before the first trial, and `verify` checks the
  arithmetic of a case, not that the experiment would repeat.
- Some Atlas probes depend on hardware and on the build host. NaN bit patterns and the last digits of
  some math functions differ between CPUs, and a Linux executable linked on Ubuntu prints different
  digits for three math probes than one linked on macOS. Each column is one run.
- Coverage covers JVM code only. Kotlin/Native and Wasm have no coverage tool.

What has not been run:

- Windows. The Windows executable ran under Wine only, the `.msi` and `.exe` installers have not been
  built, and `install.ps1` ran under PowerShell 7 on Linux, not on Windows.
- Safari. Studio loads in Chromium, Firefox and WebKit through Playwright, and not in Safari.
- The workflows `build.yml`, `atlas.yml`, `docs.yml` and `release.yml`, so the badges above show no
  status and the Homebrew and Chocolatey packages have never been installed from a published release.

The measurements and their instruments are in the
[Evaluation and Limits](./TECHNICAL_REPORT.md#evaluation-and-limits) section of the report.

---

## Documentation

- [TECHNICAL_REPORT.md](./TECHNICAL_REPORT.md) describes the design, the evidence and the numbers.
- [ADVANCED_USAGE.md](./ADVANCED_USAGE.md) covers every command and flag, with output from real runs.
- Engine documentation for the modules is generated with Dokka by `./gradlew :dokkaGenerate` into
  `build/dokka/html`. It describes how the code is organized. Drift ships as an application, and the
  modules are not a public API or a published library.

---

## License

MIT. See [LICENSE](./LICENSE).
