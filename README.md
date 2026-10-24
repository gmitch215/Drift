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
    <p style="text-align: center;" data-shared-ratio>Shared code 93.0%: host 59.3%, core 100.0%, scan 100.0%, cli 76.5%, studio 95.0%, bench 81.7%</p>
</div>

---

Drift diagnoses a program that passes in one environment and fails in another. It records each
environment, ranks what differs between a passing run and a failing one, proposes the
experiment that would remove the most uncertainty, runs it, and writes a record of the result that a
second command can check. It is written in Kotlin Multiplatform (one Kotlin codebase built for
several platforms) and runs as a command line tool, a desktop app, a web app, an Android app and an
iOS app from the same shared code. The apps are called Studio.

---

## Terms

| Term | Meaning |
| --- | --- |
| Capsule | A file that records one environment: the operating system, tool versions, environment variables (secret values hidden) and the results of small checks. |
| Diagnose | Compare the capsule of a run that passed with the capsule of a run that failed, and rank what differs. |
| Solve | Run the failing command in containers that differ in one setting, under a rule written down before the first run, to see which difference causes the failure. This part of Drift is the Lab. |
| Certificate | The record of every run in a solve and its verdict, kept in a `.driftcase` file. |
| Verify | Recompute every count and verdict in a `.driftcase` file from the file itself. It does not run anything again. |
| Atlas | A table of what small Kotlin programs print on each target, a platform Kotlin builds for, such as the JVM, native code or WebAssembly. |
| Probe | One of those small programs. |

---

## Quick Start

The commands run from a clone of this repository, because they read the files in `fixtures/`.
Output below is from Linux x64.

### Command Line

Install the executable, or build it from source if no release is published yet (see
[Install and Run](#install-and-run)), and check it runs:

```sh
curl -fsSL https://raw.githubusercontent.com/gmitch215/Drift/master/install.sh | sh
drift --version
```

```
drift version 1.0.0 (linux)
```

Diagnose a CI run (an automated test run on a server) that went from passing to failing.
`fixtures/drangler` holds the capsules of both runs:

```sh
G=fixtures/drangler/capsules/36702683742.json
R=fixtures/drangler/capsules/36995781138.json
drift diagnose --detail summary $G $R
```

```
9 changes differ with the same support, and tool.node.version ranks first only by hand-set weights,
so the data does not name one cause.
```

Nine attributes differ and the data cannot separate them, so Drift names no single cause.
`--detail detail` adds the ranked candidates and the next experiment to run. The Try It section
continues from here.

### Studio

Studio runs in a browser at <https://drift.gmitch215.dev/atlas/>, with nothing to install. To serve
the same build from your machine, see [Studio on the Web](#studio-on-the-web).

1. On the Home screen, choose **Show a Sample**. Studio compares the same two CI runs as the command
   above and shows the same result: Drift cannot name one cause, and nine differences tie for the
   top place.
2. Choose the **Atlas** tab. Its rows are probes, and its columns are targets.
3. Choose **Run on This Device**. The This Device panel counts how many probes behave like each
   target, and the Device column shows what your browser printed for each probe.

---

## What You Need

Every command except `drift solve` runs with the executable alone. `solve` needs Docker, because it
builds and runs containers, and `docker info` must succeed. `drift reproduce` writes a `Dockerfile`
and a `run.sh` without Docker; running `run.sh` needs it. `drift serve` needs a Studio web build
directory, which `./gradlew :cli:stageStudio` writes and the release archives do not hold. Building
from source needs JDK 21. Studio in a browser needs only the browser.

---

## Table of Contents

- [Terms](#terms)
- [Quick Start](#quick-start)
- [What You Need](#what-you-need)
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

In CI, `drift run -- COMMAND` runs a command and keeps its environment, exit code and log tail as a
capsule directory. `drift import` builds the same directory from a saved GitHub Actions or GitLab CI
log. `drift diagnose --against BASELINE` compares a saved passing run with the current one.
`drift diagnose --set` ranks the attribute values that split several passing runs from several
failing ones, and `drift bisect plan` and `drift bisect apply` narrow a green and red pair one test
at a time. None of them opens a network connection.

`drift capture --project` adds the dependency versions, imports and call sites of the working directory to a capsule. `drift upgrade-check --to php=8.3,drupal=11` lists what a move to other versions breaks, from one capsule and the rule catalog (426 rules). `drift triage` reads an issue body offline and says which environment facts and rules it names. `drift contract` writes what a program needs from an environment and checks other capsules against it. Studio has a screen for each of the last three.

The repository also holds a GitHub Action (`action.yml`) and a GitLab CI/CD component
(`templates/drift.yml`) that run these steps in a pipeline; see
[CI Integration](./ADVANCED_USAGE.md#ci-integration).

---

## The Kotlin Portability Atlas

Kotlin code that compiles everywhere does not always print the same thing everywhere. The Atlas holds
38 probes of Kotlin behavior. Each probe is a small program with a recorded output per target.
Sixteen columns are recorded on Kotlin 2.4.20: the JVM on JDK 17, 21 and 25, Linux x64 and arm64
and macOS native, Wasm on Node, Chromium, Firefox and WebKit, Kotlin/JS IR on Node, Android, the iOS
simulator and two Cloudflare Workers runtimes. Five more (JVM, macOS native, Wasm, Android and iOS)
are recorded on Kotlin 2.5.0-Beta1, and a ladder of 14 compiler versions from 1.3.72 to 2.4.21
covers the JVM, macOS, Linux and Kotlin/JS targets that have a standalone compiler.

32 probes differ between targets on 2.4.20, and 18 on 2.5.0-Beta1. Every probe carries a
classification from primary sources: 10 are `documented` (a Kotlin page states the behavior), 4 are
`platform-defined` (a JDK or ECMAScript page does) and 24 are `unclassified`, with the search that
found no explanation. A divergence is data, not a verdict that something is a bug. `drift atlas diff`
lists what changed between two compiler versions.

Studio's Atlas tab holds the matrix. It groups columns by target family and adds a column for the
device it runs on, measured live when the tab opens. A predict-and-reveal mode asks for the output
you expect on a target, then shows the recorded one.

`drift atlas record`, `build`, `compare` and `show` do the same work from the command line.
`./gradlew atlasSite` builds the static Atlas site: Studio, a page per probe and the dataset as
JSON. `./gradlew docsSite` assembles the documentation site around it: this README as the front
page, the two documents listed under Documentation as pages of their own, the Atlas site under
`atlas/` and the engine documentation under `engine/`. The `docs.yml` workflow builds that site on
every push and publishes it to GitHub Pages from `master`, with the custom domain
`drift.gmitch215.dev`.

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
curl -fsSL https://raw.githubusercontent.com/gmitch215/Drift/master/install.sh | sh
```

On Windows, in PowerShell:

```powershell
irm https://raw.githubusercontent.com/gmitch215/Drift/master/install.ps1 | iex
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
| Wasm tests on Node | JDK 21 (Gradle fetches Node.js) | `./gradlew :core:wasmJsNodeTest` |
| Studio on Android | Android SDK with platform 37 | see [Android](#android) |
| Studio on iOS | macOS, Xcode with a simulator runtime | see [iOS Simulator](#ios-simulator) |

The installers are built by `release.yml` with `./gradlew :studio:packageDmg :studio:packagePkg` on
macOS, `:studio:packageDeb :studio:packageRpm :studio:packageAppImage` on Linux and
`:studio:packageMsi :studio:packageExe` on Windows (WiX Toolset). `drift solve` also needs Docker.
Everything else runs without it.

The wasmJs target builds Studio for the browser (see Studio on the Web; `./gradlew
:studio:wasmJsBrowserDistribution` builds the bundle without staging it) and runs the common `core`
tests under Node.

### Command Line

Executables land in `cli/build/bin/<target>/releaseExecutable/`. The macOS executable is `drift.kexe`
and the Windows one is `drift.exe`. On Linux, link `linkReleaseExecutableLinuxX64` (or `LinuxArm64`) and
point the alias at `linuxX64/releaseExecutable/drift.kexe` instead.

```sh
./gradlew :cli:linkReleaseExecutableMacosArm64
alias drift=$PWD/cli/build/bin/macosArm64/releaseExecutable/drift.kexe
drift --version
```

```
drift version 1.0.0 (macos)
```

On the JVM, `./gradlew :cli:jvmRun --args=--version` prints `drift version 1.0.0 (jvm)`, and any other
command goes in `--args`. The macOS executable and the JVM build were run. The Linux arm64 and x64
executables ran inside Debian containers. The `mingwX64` tests run on GitHub's `windows-2025` runner; the
Windows commands quoted in the usage guide ran under Wine.

The default path runs offline: `drift capture` and `drift diagnose` complete under `docker run --network none`.

### Studio on the Desktop

```sh
./gradlew :studio:run
```

A window opens on the Home tab, with the Atlas, Investigation, Upgrade, Contract and Bisect views
beside it.

### Studio on the Web

`drift serve` serves the web build from a directory, read-only, on the loopback interface.
`:cli:stageStudio` builds the Wasm distribution and copies it to `cli/build/serve/studio`.

```sh
./gradlew :cli:stageStudio
drift serve --dir cli/build/serve/studio
```

Open `http://localhost:8080/`, or `http://localhost:8080/#atlas` to start on the Atlas tab. The page
title is "Drift Studio". `--port 0` takes a free port, `--cert` and `--key` (or `--mkcert` with an
existing mkcert CA) serve HTTPS on the JVM, macOS and Linux builds, and `--domain` adds names such
as `drift.local`. Any static server works too: serve `studio/build/dist/wasmJs/productionExecutable`
after `./gradlew :studio:wasmJsBrowserDistribution`. The documentation site serves the same build at
<https://drift.gmitch215.dev/atlas/#atlas>, published by `docs.yml`.

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

The examples run from the repository root against the files in `fixtures/`. They assume `drift` is
on your path, or the `drift` alias from the Command Line section. Each one needs only the
executable, except where it says otherwise. Output is from Linux x64.

**Capture this machine.** The capsule is JSON, so the digest changes with the machine.

```sh
drift capture --label ci > ci.json
sha256sum ci.json
```

On macOS, use `shasum -a 256` for the second command.

**Diagnose a real failure.** The Quick Start printed the one-line summary. `--detail` prints text
instead of JSON, at three levels (`summary`, `detail`, `full`). The first lines of `detail`:

```sh
G=fixtures/drangler/capsules/36702683742.json
R=fixtures/drangler/capsules/36995781138.json
drift diagnose --detail detail $G $R | head -9
```

```
9 changes differ with the same support, and tool.node.version ranks first only by hand-set weights,
so the data does not name one cause.

Candidates, best first. Scores come from hand-set weights (hand-set-1, uncalibrated); they order
  candidates and are not probabilities.
  1. tool.node.version (tier difference, basis inferred, in bundle-1)
      evidence: attribute tool.node.version; no run history behind it
  2. ci.provisioner.build-date (tier difference, basis inferred, in bundle-1)
      evidence: attribute ci.provisioner.build-date; no run history behind it
```

The full output also lists the bundle and the proposed next experiment.

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

**Reproduce an environment.** This writes a `Dockerfile`, `run.sh` and `manifest.json` into `repro/`
and does not run Docker. The manifest lists what a container cannot mirror.

```sh
drift reproduce fixtures/lab/node-alpine.json --run "npm test" --out repro
```

```
wrote 3 files to repro
23 attributes: 10 mirrored, 4 partially mirrored, 9 not mirrored; base image node:22.12.0-alpine3.20
This is not the original environment. Attributes that are not mirrored are listed with their reasons
  at --detail detail.
```

**Record the Atlas for this machine.** The transcript holds one section per probe. Where an
executable was linked changes three lines of `kotlin.math.transcendental-bits` (`tan1`, `asin-half`
and `acos-half`, in the last digit), and the Linux executable used here differs from
`fixtures/atlas/linux.txt` in exactly those lines.

```sh
drift atlas record --out tr
```

```
wrote 38 probes for linux on kotlin 2.4.20 to tr/linux.txt
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
write /path/to/scratch-bin/drift (mode 0755, 12102072 bytes)
create directory ~/.config
create directory ~/.config/drift
PATH is not modified
write receipt ~/.config/drift/install-receipt.json
```

**Serve Studio.** This needs a Studio web build. Stage it once, then serve it on a free port and fetch
the page headers:

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

**Flagship cases.** `fixtures/cases` holds the longer cases that the technical report describes.
Case B is one Kotlin program that prints different results on Native and Wasm; case C holds six
incidents, among them Lombok on JDK 21. Verifying them reads the packed certificate and starts no
container:

```sh
drift verify fixtures/cases/b/resume-offset.driftcase | tail -3
```

```
verdict: CONFIRMED: this dimension causes the failure: runtime.platform. Setting it to the failing values moved the failure count from 0 of 5 to 5 of 5 (exact one-sided p 0.003968) and back from 5 of 5 to 0 of 5 when the failing side was set to the passing values (p 0.003968)
experiments are not re-run; the recorded results are taken as given
verify: ok
```

```sh
drift verify fixtures/cases/c/INC-11.driftcase | tail -3
```

```
verdict: CONFIRMED EFFECT (bundle): the arms differ in 2 attributes, so the effect is the bundle's and not one member's: env.JAVA_VERSION, tool.java.version. Setting tool.java.version moved the failure count from 0 of 5 to 5 of 5 (exact one-sided p 0.003968) and back from 5 of 5 to 0 of 5 when the failing side was set to the passing values (p 0.003968). No bundle-splitting arm was possible: only tool.java.version can be set on its own; the other members travel with the base image.
experiments are not re-run; the recorded results are taken as given
verify: ok
```

---

## Architecture

| Module | Owns |
| --- | --- |
| `core` | Capsule model, canonical JSON, SHA-256, fixed-point math, diff, ranking, rules, planner, Lab. Common code only. |
| `host` | Operating system access, one implementation per target (JVM, Linux, macOS, Windows, Wasm, Android, iOS). |
| `scan` | Probes, toolchain scanners, the Atlas dataset builder and the probe classification. |
| `cli` | The `drift` command and its 21 subcommands, including `upgrade-check`, `triage`, `contract`, `install`, `uninstall` and `serve`. |
| `studio` | The Compose Multiplatform app for desktop, web, Android and iOS: Home, Case, Atlas, Investigation, Upgrade, Contract and Bisect screens, and the installer packaging. |
| `bench` | DriftBench, a seeded benchmark of container pairs with an injected cause. |
| `tools` | Fixture freezing, Atlas ladder tooling, the triage corpus builder and scorer, the performance rig and the installer test scripts. |
| `androidApp`, `iosApp` | The Android and iOS app shells. They exist only under `-Pdrift.mobile=true`. |
| `packaging` | Homebrew formula and cask and Chocolatey package templates, rendered by `./gradlew renderPackaging` after a release. |
| `atlas` | Hand-written probe classification, column descriptions and repro drafts in YAML. The build embeds the classification and builds the static site from it. |
| `build-core` | Gradle convention plugins and build tasks: rules, Atlas site, packaging templates, Dokka. |
| `rules` | Hand-written rule files in YAML. The build generates the JSON that `core` reads. |
| `triage` | The manifest of the issue corpus that `drift triage` is measured on, and the claim-check labels. |
| `fixtures` | Recorded capsules, Atlas transcripts, solved cases, flagship cases, project trees, issue bodies and upgrade-check captures. |
| `install.sh`, `install.ps1` | Download, verify and install a release. |

---

## Shared Code

Kotlin common code holds most of the program. `core` and `scan` have no platform source sets, and
`host` holds every `expect` and `actual` pair. `./gradlew sharedRatio` prints common and platform
lines per module and fails the build if a floor or budget is broken:

```
module     common  platform  shared
host          423       545   43.7%
core        13540         0  100.0%
scan         4928         0  100.0%
cli          3697      1431   72.1%
studio       2959       159   94.9%
bench        2079       562   78.7%
total       27626      2697   91.1%
```

`host` is about two fifths common code and its platform lines are the process runners (POSIX, Windows and JVM) and the file and environment access; its budget is 545 lines. The platform lines of `cli` are the file and socket code behind `install` and `serve`. The
ratio counts the default targets: JVM, Linux, macOS, Windows and Wasm. `core` and the common code of
`bench` use no `Double` or `Float`, so their results are the same on every target.

---

## Status and Limits

The command line tool, Studio on desktop and web, the Atlas, the Lab, `verify`, `install` and `serve`
are implemented and tested. The Android and iOS apps build and launch on an emulator and a simulator.
No real Android or iOS device has been tried, and the iOS build is a simulator build.

What a result does and does not show:

- The ranking weights are hand-set. On the development benchmark the ranker beats a random order at
  top-1 (46 of 105, P = 0.0012) and top-3 (67 of 105, P = 0.00002), and a `plausible` tier candidate
  is more often wrong than right (40 of 62).
- `upgrade-check` was scored once, on a PHP 7.4 to 8.3 move of one library: 0 true positives and 12
  false positives, and what did break had no rule.
- The `triage` claim check is experimental: it flags none of 67 labelled issue threads and fires once in
  222,707 corpus issues, and that hit is a false positive. The facts it extracts score a precision of 0.78 on
  form-labelled issues of large trackers.
- The Lab confirms a cause only under a rule fixed before the first trial, and `verify` checks the
  arithmetic of a case, not that the experiment would repeat.
- Some Atlas probes depend on hardware and on the build host. NaN bit patterns and the last digits of
  some math functions differ between CPUs, and a Linux executable linked on Ubuntu prints different
  digits for three math probes than one linked on macOS. Each column is one run.
- Coverage covers JVM code only. Kotlin/Native and Wasm have no coverage tool.
- The build uses Gradle 9.8.0 and AGP 9.4.1, newer than the Gradle 7.6.3 to 9.7.0 and AGP 8.5.2 to 9.3.1
  that the [Kotlin Multiplatform compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html) lists for Kotlin 2.4.20. The workflows
  build and test on it without a failure.

What has not been run:

- Windows installers. The JVM and `mingwX64` tests run on GitHub's `windows-2025` runner in
  `build.yml`, but the `.msi` and `.exe` installers have not been built, and `install.ps1` ran under
  PowerShell 7 on Linux, not on Windows.
- Safari. Studio loads in Chromium, Firefox and WebKit through Playwright, and not in Safari.
- `release.yml`. `build.yml`, `atlas.yml` and `docs.yml` run on every push, but no release has been
  published, so the Homebrew and Chocolatey packages have never been installed from one.

The measurements and their instruments are in the
[Evaluation and Limits](./TECHNICAL_REPORT.md#evaluation-and-limits) section of the report.

---

## Documentation

- [MANUAL.md](./MANUAL.md) walks through each Studio screen, with screenshots. Read it first if you
  will use Studio rather than the command line.
- [ADVANCED_USAGE.md](./ADVANCED_USAGE.md) covers every command and flag, with output from real
  runs. Read it when you want to use a command beyond the Quick Start, or need to know what a flag
  or exit code does.
- [TECHNICAL_REPORT.md](./TECHNICAL_REPORT.md) describes the design, the evidence and the numbers.
  Read it when you want to know how Drift reaches a result and how well it was measured.
- Engine documentation for the modules is generated with Dokka by `./gradlew :dokkaGenerate` into
  `build/dokka/html`, and `docsSite` copies it to `engine/`. It describes how the code is
  organized. Drift ships as an application, and the modules are not a public API or a published
  library.

---

## License

MIT. See [LICENSE](./LICENSE).
