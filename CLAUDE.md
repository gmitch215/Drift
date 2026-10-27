# CLAUDE.md

Drift is a Kotlin Multiplatform application, version 1.0.0: the `drift` command line tool and the Studio app. Targets: jvm, linuxX64, linuxArm64, macosArm64, mingwX64, wasmJs (Node for tests, browser for Studio). `-Pdrift.mobile=true` adds android, iosArm64 and iosSimulatorArm64 for `core`, `host`, `scan` and `studio`, and includes `androidApp`. The default build does not need an Android SDK or Xcode.

## Commands

- `./gradlew build` runs tests for the targets the host can execute, spotless, the line-length check, the shared-ratio check, the coverage check and `:build-core:check`
- `./gradlew spotlessApply` formats; `./format.sh` and `./check-format.sh` wrap spotless
- `./gradlew checkLineLength` fails on any Kotlin line over the `.editorconfig` limit, with a tab counted to the next 4-column stop
- `./gradlew checkPrivacy` scans the files git tracks for named home paths, e-mail addresses, token shapes, private network addresses and capture attributes that carry a user or host name, and fails on any hit not listed in `build-core/privacy-allow.yml` (each entry needs a reason, and an entry that matches nothing fails); it writes a masked report to `build/reports/privacy.txt` and never edits files
- `./gradlew sharedRatio` prints common versus platform lines per module and enforces the floor, the `host` budget and a common-only `core`, and fails when the `data-shared-ratio` line in `docs/shared-code.md` is stale; `./gradlew sharedRatioReadme` rewrites that line
- `./gradlew jvmJacocoAggregateReport verifyCoverage coverageSummary` writes `build/jacoco.xml`, `build/jacocoHtml` and `build/coverage/summary.txt`
- `./gradlew :tools:buildCorpus`, `:tools:downloadCorpus` and `:tools:evalTriage` build, fetch by pinned SHA-256 and score the triage corpus (`-Pdrift.corpus=<file>` skips the download); none is part of `build`
- `./gradlew :tools:imageTags` fetches the Docker Hub tags that `ImageTable` composes and rewrites `core/.../lab/ImageTags.kt` (run by hand, never part of `build`)
- `./gradlew atlasLadderDataset` builds the 74-column Kotlin version ladder dataset into `build/atlas-work/ladder.json`
- `./gradlew :cli:linkReleaseExecutable<Target>` links an executable; `:cli:jvmRun --args=--version` runs the JVM build
- `./gradlew :studio:run` runs Studio on the desktop; `:studio:wasmJsBrowserDistribution` builds the web bundle into `studio/build/dist/wasmJs/productionExecutable`
- `./gradlew -Pdrift.mobile=true :androidApp:assembleDebug` builds the debug APK; `iosApp/iosApp.xcodeproj` builds the simulator app with `xcodebuild` (see docs/install.md)
- `./gradlew :dokkaGenerate` writes the engine documentation to `build/dokka/html`; it is not part of `build` or `check`
- `./gradlew docsSite` writes the whole documentation site to `build/site`: `README.md`, `MANUAL.md`, `ADVANCED_USAGE.md`, `TECHNICAL_REPORT.md` and every `docs/*.md` guide rendered into `assets/site-template.html` (`README.md` is `index.html`, `docs/install.md` is `install.html`, and so on), the Atlas site under `atlas/`, the Dokka site under `engine/`, the icons, `assets/docs-render.js` (draws the Mermaid and `math` fences with Mermaid and KaTeX from a pinned CDN version), `CNAME` and `.nojekyll`. It runs `dokkaGenerate` and `atlasSite` first and `checkSiteLinks` after, which fails on a relative link that does not resolve outside `engine/`; a link in those Markdown files to a repository path or heading that does not exist fails the render
- `./gradlew atlasSite` writes the static Atlas site to `build/atlas-site`; `./gradlew :cli:stageStudio` stages the Studio web build for `drift serve --dir cli/build/serve/studio`
- `./gradlew renderPackaging -Pdrift.sums=<SHA256SUMS>` fills the Homebrew and Chocolatey templates in `packaging/` into `build/packaging`
- `.github/release/stage.sh archive <exe> <name>` and `stage.sh sums <dir>` build a CLI archive and `SHA256SUMS`; `sh tools/test-installers.sh <drift executable>` runs `drift install` and `uninstall` in a scratch home directory
- `drift install`, `drift uninstall` and `drift serve` are CLI commands, tested in `cli/src/*/test`; run the release workflow by hand with `dry-run` set to build every asset without attesting or publishing
- `node tools/browsers/run.mjs` records the browser Atlas columns with Playwright `serve.mjs` loads a served URL in each engine and `layout.mjs` captures a page's computed layout into capsules (all need `npm ci` in `tools/browsers`)
- `tools/capture-transcripts.sh` regenerates `fixtures/transcripts` by running toolchain images in Docker
- `sh tools/perf/test.sh` tests the performance rig and `python3 tools/perf/summarize.py` writes `tools/perf/results/SUMMARY.md` from the recorded runs
- `.github/action/test-drift.sh <drift executable>`, `test-triage.sh` and `test-gitlab.sh` test the GitHub Action and the GitLab component with stubs for `gh` and the artifact API

## Layout

- `build-core` is an included build holding the `drift.kmp`, `drift.layout`, `drift.coverage`, `drift.quality`, `drift.mobile`, `drift.docs`, `drift.docs-root`, `drift.atlas` and `drift.packaging` plugins and their task classes; its tests run through `:build-core:check`
- Modules: `core` and `scan` have no platform source sets; `host` holds every `expect`/`actual` pair, with a `posix` source set shared by Linux and macOS; `cli` is the command; `studio` is the Compose app; `bench` is DriftBench; `tools` freezes fixtures, builds the Atlas ladder (`tools/ladder`), drives the browser columns (`tools/browsers`), builds and scores the triage corpus (`tools/src/jvm/main/.../corpus`) and holds the performance rig (`tools/perf`); `androidApp` exists only with `-Pdrift.mobile=true`
- `iosApp/` is the Xcode project for the iOS simulator app; it calls Gradle with `-Pdrift.mobile=true`
- `docs/` holds the user guides (`install`, `try-it`, `portability-atlas`, `architecture`, `shared-code`, `limits` and an index in `README.md`) that `docsSite` publishes as pages; `README.md` is a short front page that links them, and `docs/manual` and `docs/submission` hold the Studio screenshots and contest files
- `fixtures/` holds recorded capsules, Atlas transcripts and solved cases that tests read: `fixtures/cases` (flagship cases a to d), `fixtures/projects` (project trees for `--project`), `fixtures/triage` (issue bodies and expectations for `drift triage`), `fixtures/upgrade-check` (a real capture for `drift upgrade-check`) and `fixtures/lab` (packed cases) and `fixtures/sites/viewport` (a page with a known cross-engine break and its nine layout capsules); `assets/` holds the logo and the documentation site template
- `atlas/` holds the hand-maintained probe classification (`classification.yml`), how each column was measured (`columns.yml`) and repro drafts; `fixtures/atlas` is the only transcript directory embedded in the dataset and Studio (`fixtures/atlas-extra` and `fixtures/atlas-variants` hold transcripts that stay out; the native Linux tests read the variants)
- `triage/` holds `corpus.yml` (the repositories, issue-template policy and pinned corpus hash), `claim-labels.yml` and `claim-protocol.md`; the corpus file itself is a release asset and is not in the repository; `notices/` holds the third-party notices that every artifact ships
- `packaging/` holds the Homebrew formula and cask and the two Chocolatey packages as templates; `install.sh` and `install.ps1` at the root are the installers that download a release archive and run `drift install`; `.github/release/` holds the release scripts
- `TECHNICAL_REPORT.md` is the design and measurement reference and `ADVANCED_USAGE.md` is the full CLI guide; both quote real runs, so rerun a command before changing its output in them
- Sources sit at `src/<set>/main/<package path>` and `src/<set>/test/<package path>` with no `kotlin` directory; resources are `src/<set>/resources` and `src/<set>/test-resources`
- Platform files carry the set suffix, as in `Host.jvm.kt`
- `rules/` holds the hand-written rule files as `*.yml`, grouped by subject in folders (`rules/do-sqlite/`, `rules/gcc/`, `rules/jdk/`, ...); every scalar is a string and `schema` is the only integer. A rule id is its file name, so it must be unique across folders. The `embedRules` task in `build-core` parses them with snakeyaml-engine (failsafe schema) and embeds the JSON that `core` reads, so common code never parses YAML. A stray `rules/**/*.json` fails the build. YAML is hand-maintained and JSON is generated; the same holds for `bench/scenarios/*.yml`, `bench/data` and `atlas/classification.yml`

## Conventions

- Four spaces, no tabs, 100 columns, LF, following the Kotlin coding conventions. ktlint runs through spotless
- `checkLineLength` is the 100-column gate for Kotlin
- Warnings are errors, with `extraWarnings` and `progressiveMode`
- Toolchain: Kotlin 2.4.20, Gradle 9.8.0, AGP 9.4.1, JDK 21. Gradle and AGP are newer than the Kotlin Multiplatform compatibility guide's range for 2.4.20 (Gradle 7.6.3-9.7.0, AGP 8.5.2-9.3.1); this is documented in `docs/limits.md` and TECHNICAL_REPORT Architecture, so update both when a version changes
- Coverage measures JVM only. Native and wasm have no coverage tool in Kotlin 2.4.20: `konanc` ignores `-Xcoverage`
- No Maven publication or signing; Drift is an application. Dokka builds engine documentation only, and the modules are not a public API
- Workflows: `build.yml` (lint, tests, coverage, browsers), `atlas.yml` (records Atlas transcripts per OS and builds the site), `docs.yml` (publishes the documentation site to drift.gmitch215.dev), `gitlab.yml` (mirrors to gitlab.com/gmitch215/Drift), `bench.yml` (manual dispatch; runs DriftBench host arms natively on macOS, Windows and arm64 Linux) and `release.yml` (archives, installers, APK, tap and Chocolatey push); all but `bench.yml` and `release.yml` have run on GitHub
- The version is `version=` in `gradle.properties`; `core` embeds it for `--version` and the Studio and Android packages read it. The Xcode project keeps its own copy of the marketing version
- Secrets are referenced by name only: `CODECOV_TOKEN` in `build.yml`, `GITLAB_TOKEN` in `gitlab.yml`, `HOMEBREW_TAP_TOKEN` and `CHOCOLATEY_API_KEY` in `release.yml`
- Commit messages carry no AI trailers (no `Co-Authored-By` lines)
- Nothing is signed or notarized; the macOS app has an ad-hoc signature and the APK uses the build machine's debug key

## Hooks

`.githooks/pre-commit` runs spotless on staged Kotlin and prettier on staged JSON, YAML and Markdown when prettier is installed. Enable it once with `git config core.hooksPath .githooks`.
