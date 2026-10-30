# Install

The first release has not been published. The download links and the `brew` and `choco` commands
below work once it is. Until then, the install script builds from source (see
[No Release Yet](#no-release-yet-or-an-experimental-build)).

## Downloads

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

## Unsigned Apps

The macOS and Windows installers are not signed with a developer certificate, so the first launch is
blocked.

- **macOS:** Gatekeeper rejects the app. Open System Settings, Privacy and Security, and choose Open
  Anyway, or run `xattr -dr com.apple.quarantine /Applications/Drift.app`.
- **Windows:** SmartScreen shows "Windows protected your PC". Choose More info, then Run anyway.
- **Android:** the APK is signed with a debug key. Allow installs from your browser or file manager.
  An update over an older sideloaded build needs `adb uninstall dev.gmitch215.drift` first, because
  each build machine signs with its own key.

## Homebrew

```sh
brew install gmitch215/tap/drift
brew install --cask gmitch215/tap/drift-studio
```

Use the full tap path, because other taps carry a formula named `drift`. The cask is Apple silicon
only and clears the quarantine attribute after install.

## Chocolatey

```sh
choco install drift
choco install drift-studio
```

## Install Script

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

## No Release Yet, or an Experimental Build

When no release exists, the install script builds drift from source. It does this only when no
version is pinned. A pinned `DRIFT_VERSION` or `DRIFT_INSTALL_BASE_URL` whose download fails stops
the script with an error and never builds.

The build needs a JDK 17 or newer on `PATH` or in `JAVA_HOME`, `curl` or `wget`, and either `git`
or `tar`. The first build downloads toolchains and dependencies and takes several minutes.

```sh
curl -fsSL https://raw.githubusercontent.com/gmitch215/Drift/master/install.sh | sh
curl -fsSL https://raw.githubusercontent.com/gmitch215/Drift/master/install.sh | sh -s -- --from-source
curl -fsSL https://raw.githubusercontent.com/gmitch215/Drift/master/install.sh | sh -s -- --ref BRANCH
```

The first command builds the default branch when there is no release. `--from-source` builds even
when a release exists. `--ref` takes a branch, a tag or a commit, and implies `--from-source`; the
default is `master`. `DRIFT_FROM_SOURCE=1` and `DRIFT_REF=REF` do the same. `DRIFT_SOURCE_URL` sets
the git URL or local path to clone, which builds a fork. The other flags pass through to
`drift install`. `--dry-run` still builds, and then it installs nothing.

`install.ps1` takes `-FromSource` and `-Ref` with the same meaning. Without git it downloads the
source archive instead.

## Drift Install

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
install itself. See [the usage guide](../ADVANCED_USAGE.md#install-and-uninstall) for the full
behavior.

## Build from Source

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

The wasmJs target builds Studio for the browser (see [Studio on the Web](#studio-on-the-web);
`./gradlew :studio:wasmJsBrowserDistribution` builds the bundle without staging it) and runs the
common `core` tests under Node.

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
command goes in `--args`.

The default path runs offline: `drift capture` and `drift diagnose` complete under
`docker run --network none`.

## Studio on the Desktop

```sh
./gradlew :studio:run
```

A window opens on the Home tab, with the Atlas, Investigation, Upgrade, Contract and Bisect views
beside it.

## Studio on the Web

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
<https://drift.gmitch215.dev/atlas/#atlas>.

## Android

Android and iOS builds are off by default so that a fresh clone needs only a JDK. Pass
`-Pdrift.mobile=true` to turn them on. The Android build is a debug APK for an emulator or a device
with USB debugging.

```sh
./gradlew -Pdrift.mobile=true :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb shell am start -n dev.gmitch215.drift/dev.gmitch215.drift.android.MainActivity
```

`./gradlew -Pdrift.mobile=true :androidApp:packageApk` writes the release APK that the release
workflow attaches, `androidApp/build/distributions/drift-1.0.0-android.apk`. It is signed with the
debug key. Nothing is published to a store.

## iOS Simulator

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
unavailable there. See [Limits](limits.md) for what else differs by platform.
