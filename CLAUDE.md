# CLAUDE.md

Drift is a Kotlin Multiplatform application, version 1.0.0: the `drift` command line tool and the Studio app. Targets: jvm, linuxX64, linuxArm64, macosArm64, mingwX64, wasmJs (Node for tests, browser for Studio). `-Pdrift.mobile=true` adds android, iosArm64 and iosSimulatorArm64 for `core`, `host`, `scan` and `studio`, and includes `androidApp`. The default build does not need an Android SDK or Xcode.

## Commands

- `./gradlew build` runs tests for the targets the host can execute, spotless, the line-length check, the shared-ratio check, the coverage check and `:build-core:check`
- `./gradlew spotlessApply` formats; `./format.sh` and `./check-format.sh` wrap spotless
- `./gradlew checkLineLength` fails on any Kotlin line over the `.editorconfig` limit, with a tab counted to the next 4-column stop
- `./gradlew sharedRatio` prints common versus platform lines per module and enforces the floor, the `host` budget and a common-only `core`
- `./gradlew jvmJacocoAggregateReport verifyCoverage coverageSummary` writes `build/jacoco.xml`, `build/jacocoHtml` and `build/coverage/summary.txt`
- `./gradlew :cli:linkReleaseExecutable<Target>` links an executable; `:cli:jvmRun --args=--version` runs the JVM build
- `./gradlew :studio:run` runs Studio on the desktop; `:studio:wasmJsBrowserDistribution` builds the web bundle into `studio/build/dist/wasmJs/productionExecutable`
- `./gradlew -Pdrift.mobile=true :androidApp:assembleDebug` builds the debug APK; `iosApp/iosApp.xcodeproj` builds the simulator app with `xcodebuild` (see README.md)
- `./gradlew :dokkaGenerate` writes the engine documentation to `build/dokka/html`; it is not part of `build` or `check`
- `./gradlew atlasSite` writes the static Atlas site to `build/atlas-site`; `./gradlew :cli:stageStudio` stages the Studio web build for `drift serve --dir cli/build/serve/studio`
- `./gradlew renderPackaging -Pdrift.sums=<SHA256SUMS>` fills the Homebrew and Chocolatey templates in `packaging/` into `build/packaging`
- `.github/release/stage.sh archive <exe> <name>` and `stage.sh sums <dir>` build a CLI archive and `SHA256SUMS`; `sh tools/test-installers.sh <drift executable>` runs `drift install` and `uninstall` in a scratch home directory
- `drift install`, `drift uninstall` and `drift serve` are CLI commands, tested in `cli/src/*/test`; run the release workflow by hand with `dry-run` set to build every asset without attesting or publishing
- `node tools/browsers/run.mjs` records the browser Atlas columns with Playwright and `serve.mjs` loads a served URL in each engine (both need `npm ci` in `tools/browsers`)
- `tools/capture-transcripts.sh` regenerates `fixtures/transcripts` by running toolchain images in Docker

## Layout

- `build-core` is an included build holding the `drift.kmp`, `drift.layout`, `drift.coverage`, `drift.quality`, `drift.mobile`, `drift.docs`, `drift.docs-root`, `drift.atlas` and `drift.packaging` plugins and their task classes; its tests run through `:build-core:check`
- Modules: `core` and `scan` have no platform source sets; `host` holds every `expect`/`actual` pair; `cli` is the command; `studio` is the Compose app; `bench` is DriftBench; `tools` freezes fixtures, builds the Atlas ladder (`tools/ladder`) and drives the browser columns (`tools/browsers`); `androidApp` exists only with `-Pdrift.mobile=true`
- `iosApp/` is the Xcode project for the iOS simulator app; it calls Gradle with `-Pdrift.mobile=true`
- `fixtures/` holds recorded capsules, Atlas transcripts and solved cases that tests read; `assets/` holds the logo
- `atlas/` holds the hand-maintained probe classification (`classification.yml`), how each column was measured (`columns.yml`) and repro drafts; `fixtures/atlas` is the only transcript directory embedded in the dataset and Studio (`fixtures/atlas-extra` and `fixtures/atlas-variants` hold transcripts that stay out; the native Linux tests read the variants)
- `packaging/` holds the Homebrew formula and cask and the two Chocolatey packages as templates; `install.sh` and `install.ps1` at the root are the installers that download a release archive and run `drift install`; `.github/release/` holds the release scripts
- `TECHNICAL_REPORT.md` is the design and measurement reference and `ADVANCED_USAGE.md` is the full CLI guide; both quote real runs, so rerun a command before changing its output in them
- Sources sit at `src/<set>/main/<package path>` and `src/<set>/test/<package path>` with no `kotlin` directory; resources are `src/<set>/resources` and `src/<set>/test-resources`
- Platform files carry the set suffix, as in `Host.jvm.kt`
- `rules/` holds the hand-written rule files as `*.yml`; every scalar is a string and `schema` is the only integer. The `embedRules` task in `build-core` parses them with snakeyaml-engine (failsafe schema) and embeds the JSON that `core` reads, so common code never parses YAML. A stray `rules/*.json` fails the build. YAML is hand-maintained and JSON is generated; the same holds for `bench/scenarios/*.yml`, `bench/data` and `atlas/classification.yml`

## Conventions

- Tabs, 4 wide, 100 columns, LF. ktlint runs through spotless with `ktlint_standard_indent` disabled because ktlint 1.8.0 rewrites tabs to spaces
- ktlint counts a tab as one column, so `checkLineLength` is the 100-column gate
- Warnings are errors, with `extraWarnings` and `progressiveMode`
- Coverage measures JVM only. Native and wasm have no coverage tool in Kotlin 2.4.20: `konanc` ignores `-Xcoverage`
- No Maven publication or signing; Drift is an application. Dokka builds engine documentation only, and the modules are not a public API
- Workflows, none of which has run on GitHub: `build.yml` (lint, tests, coverage, browsers), `atlas.yml` (records Atlas transcripts per OS and builds the site), `docs.yml` (publishes the Dokka and Atlas sites), `release.yml` (archives, installers, APK, tap and Chocolatey push)
- The version is `version=` in `gradle.properties`; `core` embeds it for `--version` and the Studio and Android packages read it. The Xcode project keeps its own copy of the marketing version
- Secrets are referenced by name only: `CODECOV_TOKEN` in `build.yml`, `HOMEBREW_TAP_TOKEN` and `CHOCOLATEY_API_KEY` in `release.yml`
- Commit messages carry no AI trailers (no `Co-Authored-By` lines)
- Nothing is signed or notarized; the macOS app has an ad-hoc signature and the APK uses the build machine's debug key

## Hooks

`.githooks/pre-commit` runs spotless on staged Kotlin and prettier on staged JSON, YAML and Markdown when prettier is installed. Enable it once with `git config core.hooksPath .githooks`.
