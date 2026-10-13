# Technical Report: Drift

Drift is a Kotlin Multiplatform command line tool and desktop, web and mobile app, version 1.0.0. It records an environment as a capsule, compares two capsules, ranks what changed between a passing run and a failing one, proposes the experiments that would separate the candidates, and writes a container description that mirrors a capsule. Its Lab runs those experiments in containers under a rule fixed before the first trial, reports a verdict, and packs the evidence into an archive that a second command re-checks. It also works from one capsule or one issue text without a second environment: `drift upgrade-check` lists what a move to other versions breaks, `drift triage` reads an issue body offline, and `drift contract` writes what a program needs from an environment. It ships two measured data sets: the Kotlin Portability Atlas, a table of Kotlin behavior across targets and compiler versions, with a static site and a viewer in the app, and DriftBench, a seeded benchmark of container pairs with an injected cause. The release pipeline builds a CLI archive per platform, consumer installers for the app, a Homebrew formula and cask, and two Chocolatey packages.

This document is the engineering reference for what the working tree does today, what it costs, and what was measured. Each table names its instrument. The section Reproducing the Numbers lists the command or file behind every figure. Test counts, line counts, coverage, rule counts, Atlas hashes and command output describe the tree on 2026-10-13 and were read again from a clean build on that day on a Mac; they move with the tree. Figures from other machines or earlier days (the Docker and paisley-park runs, Wine, the installers, the browsers and the performance sweep) name where and when they were taken and were not repeated. Nothing described here has run on GitHub: every workflow is checked with `actionlint` and its steps were exercised by hand on a Mac and on a Linux host, and no Windows machine was available.

---

## Summary

| | measured | instrument |
| --- | --- | --- |
| Targets built | 6: JVM, macOS arm64, Linux x64, Linux arm64, Windows x64 (mingw), Wasm on Node; `-Pdrift.mobile=true` adds Android and the iOS simulator for the app | `drift.kmp` plugin |
| Test runs in the tree | JVM, macOS arm64 Native and Wasm on Node by Gradle on a Mac; Studio in Chrome by Gradle and in Firefox by Karma; Linux x64 and Linux arm64 Native in containers; mingw under Wine (not Windows) | `*/build/test-results/*/*.xml`, container logs (see Platform and CPU Findings) |
| Core tests, each of the 3 Gradle targets | 638 passed, 0 failed, on every target | `*/build/test-results/*/*.xml` |
| Shared-code ratio, all modules | 90.7% common lines (27,341 common, 2,798 platform); `core` and `scan` 100%, `cli` 69.9%, `host` 35.6% | `./gradlew sharedRatio` |
| JVM line coverage | 96.0% (20,448 of 21,293 lines); floor 95% | `build/coverage/summary.txt` |
| `core` and `bench` common code using `Double` or `Float` | 0 occurrences | `grep -rn` |
| macOS arm64 CLI, release executable | 9,866,776 bytes; 21 subcommands | `ls -l`, `drift --help` |
| Probes in the catalog | 71: 33 general, 38 Kotlin | `drift capture --probes` |
| Scanners | 23 | `Scanners.all` |
| Rules | 298 in 27 folders: 207 active and 91 `pending-scanner`; 913 fixtures (303 positive, 610 negative); 338 cited sources (210 distinct) | `rules/**/*.yml` |
| Atlas, Kotlin 2.4.20, 16 columns | 32 of 38 probes differ on at least one column; 6 are identical on all 16 | `drift atlas build fixtures/atlas/*.txt` |
| Atlas, Kotlin 2.5.0-Beta1, 5 columns | 18 of 38 differ | `drift atlas build` over the Beta fixtures |
| Atlas version ladder | 14 compiler versions, 1.3.72 to 2.4.21, plus 2.5.0-Beta1; 74 columns, 2,812 cells, 34 divergent probes | `drift atlas build` |
| Atlas probe classification | 10 documented, 3 platform-defined, 25 unclassified; among the 32 divergent probes on 2.4.20, 4, 3 and 25 | `drift atlas show` |
| Atlas site | 79 files, 18,548 KiB, 39 probe pages and `versions.html` | `./gradlew atlasSite` |
| DriftBench | 80 scenarios from 43 templates; 49 dev scenarios validated in containers, 31 sealed | `bench/data` |
| Ranker on the 45 dev scenarios that have a cause, with probes | top-1 21 of 45 (Wilson 95% interval 0.33 to 0.61); random order expects 16.3; exact P = 0.018. Top-3 29 of 45 against 25.9 expected, P = 0.009 | `evaluation.json` |
| `plausible` or `known` top candidate wrong | 10 of 49 dev scenarios (0.11 to 0.34); 14 of 25 `plausible` candidates | same |
| Lab, 45 dev scenarios, first run | 14 CONFIRMED (all correct), 9 CONFIRMED EFFECT (bundle), 1 NARROWED, 21 STUCK; 0 false CONFIRMED (the four migration scenarios added later: 2 CONFIRMED EFFECT (bundle), 1 NARROWED, 1 STUCK) | 45 case directories, `drift verify` |
| Lab, three more runs of the same 45 | 180 runs in all: 0 false CONFIRMED, exact one-sided 95% upper bound 1.65%; 2 of 45 scenarios changed verdict class, both intermittent | 135 more `certificate.json` files |
| `.driftcase` archives, JVM and macOS native | byte-identical; `verify` output identical; 4 archives in `fixtures/lab/cases` | `cmp` |
| Studio installers built here | macOS dmg 74,624,330 bytes and pkg 70,015,031; Linux deb, rpm and AppImage in a container; Android APK 7,203,057; Windows msi and exe not built | `studio/build/compose/binaries`, `androidApp/build/distributions` |
| CLI release archives | 2.5 to 2.8 MB each for macOS arm64, Linux x64, Linux arm64 and Windows x64 | `.github/release/stage.sh` |
| Drangler transition, ranked | 9 candidates, 1 bundle, no single cause named | `drift diagnose` |
| Flagship case D, migration PHP 7.4 to 8.3 | `upgrade-check` frozen prediction 0 true positives, 12 false positives; the suite passed on 8.3 | `fixtures/cases/d` |
| Triage on 70 fixture bodies | precision 0.989, recall 0.979, Brier 0.0851; claim check: 0 of 67 labelled threads flagged | `TriageTest`, `triage/claim-labels.yml` |
| Native CLI, Mac, 15 runs | `version` 7.5 ms, `capture` 24.3 ms, `diagnose` 9.0 ms, 13.0 MB peak RSS (recorded 2026-10-12) | `tools/perf/results/SUMMARY.md` |

Six results shape how to read the rest. The fit of ranking weights on the 49 dev scenarios did not beat the hand-set weights out of fold, so the weights are hand-set and every ranking says so. On the benchmark the ranked order beats a random order at the top: 21 of 45 at top-1 against 16.3 expected (P = 0.018) and 29 of 45 at top-3 against 25.9 (P = 0.009). An earlier measurement on 41 scenarios could not tell the top-3 order from chance (P = 0.37); the regenerated data refutes that. The `plausible` tier, which a differing probe that reads the candidate's attribute or a matched rule earns, is not reliable on this benchmark: 14 of its 25 candidates are wrong, because the probe agrees with decoys that really changed, and probes move the order of two scenarios, both for the worse. The probability the tool prints is mostly a count of rivals, "one of n", and it exists only for rankings that rest on attribute changes alone. In the migration case, Drift's predictions lost: the frozen `upgrade-check` output named 12 breaks and none happened (0 true positives, 12 false positives), because every rule fired on the version crossing alone and the real obstacles, a locked package with a PHP upper bound and a tentative return type, have no rule. And the claim check in `triage` is weak: it flags none of the 67 labelled threads and no clear true positive among its 6 hits in 222,707 corpus bodies.

What does hold up under test is the machinery around the ranking. Output is byte-identical on three targets and pinned by hashes, including the recorded certificates of the flagship cases. A bundle of changes the data cannot separate is reported as a bundle. A missing probe is missing evidence and never a pass. The planner prices each experiment in expected bits before anything is run, and says which experiments no one can run on a hosted runner. The Lab fixes its decision rule before the first trial and gave no false CONFIRMED verdict in 180 runs of the 45 dev scenarios (synthetic scenarios, one container configuration, and a bound that holds per run, not per kind of failure). `verify` re-derives every count, p-value, posterior and verdict from an archive alone. It does not re-run an experiment.

Platform findings sit beside those. A Kotlin probe can print different bytes on x86-64 and arm64 hardware, on different JDKs, and on a Native executable linked on a different build host, so an Atlas column describes a machine as much as a target. The deployed Cloudflare Workers matched the local workerd runs except for the sign bit of NaN results. Safari was not measured, Windows was not run, and no workflow has run on GitHub.

---

## Problem and Scope

A test passes on one machine and fails on another, or passes on Tuesday's CI run and fails on Wednesday's. The environments differ in many attributes at once: runner image, toolchain versions, locale, resource limits, dependency pins. A diff lists all of them and ranks none. Deciding which difference caused the failure needs evidence about each one, and that evidence is an experiment.

Drift covers six steps of that work.

1. **Record.** `drift capture` writes an environment as a capsule: a canonical JSON document with a SHA-256 hash. A capsule holds attributes (operating system, kernel, toolchain versions, environment variables, resource limits) and probe results (short programs whose printed output records how the environment behaves).
2. **Rank.** `drift diagnose` diffs a passing and a failing capsule, assigns each change to one of 14 dimensions, and orders the candidates with explicit weights. Given a run history, it also uses which runs passed and failed.
3. **Plan.** The planner turns the candidates into experiments, each with a predicted information gain, a cost and exact instructions, and says which ones Drift cannot run.
4. **Mirror.** `drift reproduce` writes a Dockerfile, `run.sh` and a manifest that mirror a capsule and list, attribute by attribute, what a container cannot reproduce.
5. **Run.** `drift solve` runs counterfactual arms in containers under a preregistered decision rule and ends in one of four verdicts. `drift ingest` adds results that someone else produced, under the same rule.
6. **Verify.** `drift pack` writes the case as a `.driftcase` archive, `drift report` renders its static `report.html`, and `drift verify` re-derives what the archive records from its own data.

The Atlas applies the same capture idea to the Kotlin standard library: it records what 38 short Kotlin programs print on each target and compares a device against that table.

Further commands work without a second environment or without running anything: `drift run`, `import`, `bisect` and `contract` keep a CI run as a capsule, narrow a red and green pair and write what a program needs; `drift upgrade-check` (see Upgrade Check) lists what a move to other versions breaks from one capsule; `drift triage` (see Triage and the Corpus) reads an issue body offline; `drift open` shows a `.driftcase` in Studio. Three further commands deal with delivery and are described under Serving Studio Locally and Distribution. `drift serve` serves the Studio web build on the loopback interface. `drift install` and `drift uninstall` copy the executable onto the user's PATH and reverse that from a receipt.

What Drift does not do here: `diagnose` runs no experiment, `reproduce` never runs Docker, `atlas show` files nothing and contacts no one, and `verify` never re-runs an experiment. `serve` answers `GET` and `HEAD` on loopback only. The ranking tiers stop at `known` (a documented rule matched), and the text that `diagnose`, `next` and `case show` print never says "confirmed". Only `solve`, after its own experiments, can say CONFIRMED. `solve` needs Docker and runs experiments on the local machine; it has no CI mode.

---

## Design Principles

**Determinism.** Everything in `core` and in the common code of `bench` is integer arithmetic. `FixedPoint` holds a value in micro-units (1,000,000 is 1.0) and implements `ln` and `exp` with integer series; `IntSqrt` takes integer square roots; probabilities are normalized by largest remainder so a belief always sums to exactly 1,000,000. A search of `core/src/common/main` and `bench/src/common/main` finds no `Double` and no `Float`. The fixed-point results agree with the platform math library to the micro-unit on the values `GoldenTest` pins:

| expression | fixed point (micro-units) | math library |
| --- | --- | --- |
| ln(3) | 1,098,612 | 1.0986123 |
| exp(-2.5) | 82,085 | 0.0820850 |
| ln(123.456789) | 4,815,891 | 4.8158912 |
| exp(7.25) | 1,408,104,848 | 1408.1048482 |

**Byte-identical output.** Golden tests assert exact bytes and a SHA-256 in common test code, so the same assertion runs on the JVM, macOS arm64 Native and Wasm on Node. The `.driftcase` archive follows the same rule (see The Lab). `GoldenTest` pins the canonical bytes of a capsule with non-ASCII text, a control character and a surrogate pair (hash `9a69c086...edf1`), the hash of 1,000 `a` characters (`41edece4...7ea3`, checked here against Python's `hashlib`), and a canonical rule. Ranking, plan, case and rendering goldens follow the same pattern. The macOS release CLI printing `drift diagnose` for a real pair produced the same hash, `4b0dd023...ff8a`, as the golden that `DriftCommandTest` asserts on all three targets.

**Canonical JSON.** The encoder sorts object keys at every depth, writes no whitespace, accepts integers only, escapes strings with a fixed table and rejects a lone surrogate. A capsule's hash is the SHA-256 of that encoding. For `fixtures/lab/node-alpine.json` the hash printed in the `reproduce` header, `540e5c22...0531`, equals `sha256` of the file without its trailing newline.

**Unknown beats incorrect.** An absent tool writes no attribute. A probe that needs a capability the host lacks reports `unavailable`, and a diff treats that as missing evidence. A rule whose attributes are absent returns `UNKNOWN` and never matches. `ci.runner.label` and `runtime.platform` values such as `workerd-local` appear only when the workflow declares them in `DRIFT_RUNNER_LABEL` or `DRIFT_RUNTIME_PLATFORM`. A ranking whose candidates carry probe, rule or spectrum evidence prints no probability: no dev scenario carries rule or history evidence, and on the 13 that carry a probe the hand-set probe weight read worse than "one of n" (see Evaluation and Limits).

**Labelled weights.** Every ranking and plan carries its weight set (`hand-set-1`, `plan-hand-set-1`) and `"calibrated": false`. The renderer calls scores hand-set and says they order candidates and are not probabilities.

**Test seams.** Tests run against a fake host, recorded tool transcripts under `fixtures/transcripts`, a fake HTTP fetcher for the rule-source verifier, a fake `docker` script for the benchmark runner, a fake `Executor` that the `solve` loop runs against, a fake `InstallSystem` for `install` and a fake `ServeSystem` for `serve`. One scan test runs real commands on the host and compares the result with the same parse of recorded output. A few tests touch the real file system or a loopback socket inside a scratch directory: `NativeInstallTest`, `MacosInstallTest`, `JvmServeTest` and `PosixServeTest`.

**Gates.** The build fails on a spotless (ktlint) violation, a Kotlin line over 100 columns, a warning (all warnings are errors), a shared-code ratio under 80%, a `core` that contains platform code, a `host` over its platform-line budget, or JVM line coverage under 95%.

---

## Architecture

| module | owns |
| --- | --- |
| `core` | Common code only. Capsule model, canonical JSON, SHA-256, fixed-point math, version parsing, diff, rank, rules, history, planner, case directories, symptom extraction, redaction and anonymization, spectrum scoring and delta debugging, the container synthesis behind `reproduce`, and the Lab (`lab/`): interventions, arms, the `solve` loop, `ingest`, the USTAR archive, `report.html` and `verify`. |
| `host` | One `expect`/`actual` per target behind the `Host` interface: platform, environment, file read, process run. The JVM actual also offers hardware facts through OSHI 7.7.0; no CLI option calls them. Wasm has no filesystem and no process access. iOS declares files and environment only. |
| `scan` | `Capture`, the probe catalog, the scanners and kit, and the Atlas (record, build, compare, show), including the typed probe classification. |
| `cli` | The `drift` command on Clikt 5.1.0, with 21 subcommands: `capture`, `run`, `import`, `bisect`, `diagnose`, `next`, `case`, `reproduce`, `atlas`, `probe`, `solve`, `ingest`, `pack`, `verify`, `report`, `open`, `install`, `uninstall`, `serve`, `contract`, `triage` and `upgrade-check`. Writes files and exact stdout bytes through a small `expect fun` per target, holds the Docker executor behind `solve`, the installer, and the web server behind `serve`. |
| `studio` | Compose Multiplatform 1.12.1 app for JVM Desktop and for the browser as Wasm, plus the Android and iOS builds when `-Pdrift.mobile=true` is set. |
| `androidApp` | The Android application module (AGP 9.4.1), present only with `-Pdrift.mobile=true`. |
| `iosApp` | The Xcode project for the iOS simulator app; it calls Gradle with `-Pdrift.mobile=true`. |
| `tools` | JVM only. Freezes CI logs into sanitized fixtures, builds capsules from them, verifies rule source URLs, drives the Kotlin version ladder (`tools/ladder`), builds the triage corpus and scores the extractor on it (`buildCorpus`, `downloadCorpus`, `evalTriage`), and holds the performance rig (`tools/perf`). Also holds the Playwright scripts (`tools/browsers`: `run.mjs` records the browser columns, `serve.mjs` loads a served URL in each engine) and the installer test scripts. |
| `bench` | DriftBench: scenario DSL, seeded generator, container runner, ingest, calibration. |
| `build-core` | Included build with the `drift.kmp`, `drift.layout`, `drift.coverage`, `drift.quality`, `drift.mobile`, `drift.docs`, `drift.docs-root`, `drift.atlas` and `drift.packaging` plugins and the tasks they register: `embedFixtures`, `embedRules`, `embedClassification`, `embedDriftVersion`, `embedKotlinVersion`, `sharedRatio`, `checkLineLength`, the coverage tasks, `atlasSite`, `docsSite`, `checkSiteLinks` and `renderPackaging`. |

The remaining directories are data and delivery, not modules: `rules` (298 YAML rule files in 27 folders), `triage` (the corpus manifest and the claim-check labels), `notices` (third-party notices that ship in every artifact), `atlas` (the hand-maintained classification of every Atlas probe), `fixtures` (recorded capsules, Atlas transcripts, solved cases, project trees, issue bodies and upgrade-check captures), `packaging` (templates for the Homebrew and Chocolatey packages), `assets` (the icon), and `install.sh` and `install.ps1` at the root. `.github/release` holds the scripts the release workflow runs. The version is `version=1.0.0` in `gradle.properties`; `core` embeds it for `--version`, the Studio and Android packages read it, and the Xcode project carries its own copy of the marketing version.

Sources sit at `src/<set>/main` and `src/<set>/test`. The shared-code ratio counts non-comment lines under `src/common/main` against every other source set's `main`.

| module | common lines | platform lines | shared |
| --- | --- | --- | --- |
| `host` | 344 | 622 | 35.6% |
| `core` | 13,526 | 0 | 100.0% |
| `scan` | 4,920 | 0 | 100.0% |
| `cli` | 3,605 | 1,555 | 69.9% |
| `studio` | 2,950 | 155 | 95.0% |
| `bench` | 1,996 | 466 | 81.1% |
| total | 27,341 | 2,798 | 90.7% |

`host` is where the platform code lives, at 622 of its 630-line budget, and its own shared share is 35.6%. The budget was 400 until the process runners were rewritten: the POSIX runner (`fork` and `execvp` with a drained pipe, shared by Linux and macOS in one intermediate source set), the Windows `CreateProcessW` runner with batch-file escaping, and the JVM child wrapper account for the 230 added lines, which fixed a hang in which a grandchild holding the output pipe blocked the run forever. `cli` holds the per-target file, binary file and stdout writers and the installer and server actuals: the registry and process code for Windows, the profile edits on Unix, the sockets of `serve` for POSIX and Winsock, and the OpenSSL loader; its share is 69.9%. The total is 90.7% and the floor is 80%. `bench` keeps its platform lines in JVM-only code: YAML reading, scenario preparation, result ingest and an exact binomial test with `BigInteger`.

JVM line coverage comes from JaCoCo. No coverage tool exists for Kotlin/Native or Wasm in Kotlin 2.4.20, so no coverage figure exists for those targets. The same common tests run on them.

| module | covered | missed | lines |
| --- | --- | --- | --- |
| `bench` | 1,714 | 75 | 95.8% |
| `cli` | 2,302 | 100 | 95.8% |
| `core` | 9,102 | 384 | 96.0% |
| `host` | 302 | 5 | 98.4% |
| `scan` | 3,080 | 39 | 98.7% |
| `studio` | 2,022 | 74 | 96.5% |
| `tools` | 1,203 | 147 | 89.1% |
| total | 20,448 | 845 | 96.0% |

`build/coverage/summary.txt` was written by `coverageSummary` on 2026-10-13 and `verifyCoverage` passed in the same run. The total, 96.0%, sits one point above the 95% floor, and `tools` is below it alone (89.1%) because the floor applies to the total.

Targets and what ran:

| target | executable | tests with a recorded run |
| --- | --- | --- |
| JVM | jar; `./gradlew :cli:jvmRun` | yes, Gradle on a Mac |
| macOS arm64 | `drift.kexe`, 9,866,776 bytes (release) | yes, Gradle on a Mac |
| Wasm on Node | none | yes, Gradle on a Mac |
| Linux x64 | `drift.kexe`, 10,411,416 bytes, cross-linked on the Mac | test binaries run in a Debian container on a Linux host |
| Linux arm64 | `drift.kexe`, 9,009,176 bytes, cross-linked on the Mac | test binaries run in an arm64 Debian container; a build on an arm64 Linux host was not run |
| Windows x64 (mingw) | `drift.exe`, 10,120,704 bytes, cross-linked on the Mac | under Wine only, which is not Windows |
| Android, iOS simulator | APK, simulator app (only with `-Pdrift.mobile=true`) | emulator and simulator runs; see Platform and CPU Findings |

**Workflows.** `.github/workflows` holds four files, and none has a run recorded in this report. `actionlint` exits 0 on all four.

- `build.yml` runs on push and pull request for `master`, `ver/*`, `feat/*` and `renovate/*`, and by hand. A lint job runs `spotlessCheck`, `checkLineLength`, `sharedRatio` and `:build-core:check`, then compiles the JVM, Linux x64, Linux arm64, mingw and Wasm targets. A test matrix of three runners (Linux x64, macOS arm64, Windows x64) runs each target's tests, links the release executable and runs it with `--version`; a separate pair of jobs links the Linux arm64 tests on x64 and runs them on an arm64 runner. Only the Linux x64 job collects coverage, checks the 95% floor and uploads to Codecov. A `browsers` job builds the Studio web bundle, runs the Playwright smoke test in Chromium, Firefox and WebKit, and runs the Karma tests in Firefox.
- `release.yml` runs by hand with a `dry-run` input and a `suffix` input. It builds the four CLI archives, checks the arm64 archive on an arm64 runner, builds the Studio packages on three operating systems and the APK, then aggregates `SHA256SUMS`, publishes the release, and, when `suffix` is empty, updates the Homebrew tap and pushes the two Chocolatey packages. A dry run builds and checks every asset, renders the packages, and skips attestation and every push.
- `atlas.yml` runs on every push to `master`, `ver/*`, `feat/*` and `renovate/*` and by hand, and a newer push to a branch other than `master` cancels the older run: it records the Atlas transcripts on Linux x64, Linux arm64, macOS arm64 and Windows, records the JVM columns, records the three browser columns with Playwright, and builds the dataset and the static site.
- `docs.yml` runs `./gradlew dokkaGenerate atlasSite docsSite` on the same pushes, or by hand, checks that the files of the site are present, and pushes `build/site` to a `gh-pages` branch from `master` only, with a `CNAME` file for `drift.gmitch215.dev`.

**KDoc site.** `./gradlew :dokkaGenerate` aggregates the KDoc of `core`, `host`, `scan`, `tools`, `bench`, `cli` and `studio` into one Dokka 2.2.0 site, `build/dokka/html`. It is reference documentation of the engine for people reading the source. Drift ships as an application, the modules are not published artifacts, and the site is published only as `engine/` of the documentation site, by `docs.yml`, which has not run. The copy built on 2026-10-13 has 3,827 HTML pages, and `checkSiteLinks` reports 132 relative links inside `engine/` that point at pages Dokka does not write (it does not count them as failures, and the causes were not examined). An earlier Dokka failure on the `cli` module (two source sets sharing the POSIX source root, a known Dokka limitation, issue 3701) is worked around in `build-core/src/main/kotlin/drift.docs.gradle.kts` by dropping those roots from the Linux source set only.

**Fixtures and assets.** `fixtures/drangler/README.md` documents the sanitizer that produced the real CI data: the layout of the logs, run summaries, diffs and capsules, and a table of what each removal rule deletes with its count. `fixtures/atlas/README.md` says how each Atlas column was recorded, and `fixtures/atlas-extra` holds three measured columns that are left out of the dataset with the reason for each. `assets/` holds the Drift icon as a 512 by 512 PNG, a 128 by 128 PNG and an ICO; the Studio packaging reads its own icon files under `studio/src/jvm/packaging`.

---

## The Capsule and the Probe Catalog

A capsule has a schema number, a label, a list of attributes and a list of probe results.

An attribute is a path, a value, a source and a stability. Stability is `static`, `volatile` or `noisy`; a diff reports volatile and noisy changes separately and never ranks them. A capsule excerpt from a real CI run (`fixtures/drangler/capsules/36995781138.json`):

```sh
head -c 330 fixtures/drangler/capsules/36995781138.json
```

```
{"attributes":[{"path":"ci.provisioner.build-date","source":"log","stability":"static","value":"2026-09-01T19:56:44Z"},{"path":"ci.provisioner.name","source":"log","stability":"static","value":"Hosted Compute Agent"},{"path":"ci.provisioner.version","source":"log","stability":"static","value":"20260901.588"},{"path":"ci.runner.i
```

A probe result is an id, a status (`ok` or `unavailable`), the transcript the probe printed, and the SHA-256 of that transcript. Two capsules differ on a probe when the transcript hashes differ.

`Capture.run` records, without options: `drift.platform`, `os.name`, `os.arch`, `kotlin.version` (the Kotlin version the build was compiled with), `kotlin.target`, `runtime.platform`, every environment variable as `env.<name>` (`PWD`, `OLDPWD`, `SHLVL`, `_`, `RANDOM`, `SECONDS` and `LINENO` are volatile), `kernel.release` and `cpu.count` from `uname` and `getconf` (or `ver` and `NUMBER_OF_PROCESSORS` on Windows), `os.release.ID`, `VERSION_ID` and `PRETTY_NAME` from `/etc/os-release`, and the cgroup CPU and memory limits when the files exist. `--tools` adds the scanner attributes, `--probes` runs the catalog and `--project` reads the project files of the working directory (see Project and Source Facts). Four system facts are always captured when the host can read them: `cgroup.version`, `tz.system-zone`, `runtime.jvm.options` (the `JAVA_TOOL_OPTIONS` and `JDK_JAVA_OPTIONS` values) and `java.net.preferIPv6Addresses`. A capsule records `drift.platform` and no Drift version, so no recorded capsule, hash or fixture changes when the version does. On Windows the environment is read from the process's Win32 environment block with `GetEnvironmentStringsW` (`host/src/mingw/main/.../SystemHost.mingw.kt`, parsed by `EnvBlock.kt`), not from a `cmd /c set` child process, so it does not depend on a shell being found (see Platform and CPU Findings).

On the macOS development machine used for this report:

| command | attributes | of which volatile | probes |
| --- | --- | --- | --- |
| `drift capture` | 90 (81 are `env.*`) | 4 | 0 |
| `drift capture --tools` | 117 | 4 | 0 |
| `drift capture --probes --tools` | 117 | 4 | 71, all `ok` |

The probe catalog has 71 probes, each a Kotlin function that prints labelled lines. Thirty-three are general: 7 numeric, 8 text, 3 collection, 2 time, 9 process and 4 resource probes (cgroup limits, locale variables, a missing file). They map to dimensions through `Dimension.ofProbe`. Thirty-eight are the Kotlin probes of the Atlas, described below.

Capture masks secrets and removes identifiers by default. `Redactor` replaces the value of any `env.*` whose name contains `KEY`, `TOKEN`, `SECRET`, `PASSWORD`, `PASSWD`, `CREDENTIAL`, `AUTH` or `COOKIE`, and any value matching a GitHub, GitLab or npm token, AWS access key id, Slack token, `sk-` key, JWT, PEM private key header, bearer token or `user:password@` URL. It also masks IPv4 and IPv6 addresses in environment values (except version-shaped names such as `VERSION` and `v`-prefixed values), session identifiers (`XDG_SESSION_ID`, `TMUX`, `WINDOWID`, `INVOCATION_ID`, `JOURNAL_STREAM`, `guid=`) and rewrites `/run/user/<n>` to `/run/user/user`. `Anonymizer` then rewrites the account name (`USER`, `LOGNAME`, `USERNAME` and the last component of `HOME`) to `user`, the host name (`HOSTNAME`, `COMPUTERNAME`) to `host` and the exact `HOME` directory to `~`, and turns any other `/Users/<name>`, `/home/<name>` or `C:\Users\<name>` path into `.../user`. Names are replaced as whole words and only when they have three or more characters. An attribute that changed gets ` (anonymized)` appended to its `source`, which diff, rank and reproduce never read. `--keep-identifiers` skips this step and prints `warning: the capsule keeps your account, host name and home path` on standard error; it is the escape hatch for a capsule that stays on the machine.

On the capture above, 13 of the 81 `env.*` attributes were masked. With `--keep-identifiers`, 22 `env.*` attributes held the account name and 20 held the home directory path. By default no attribute of any family holds either, and 22 attributes carry the marker. Three limits remain. The identities come from the `env.*` of the capturing host, so a host name that is not in the environment is not known to the step (`HOSTNAME` is unset in zsh). Probe transcripts and labels are not rewritten; the transcripts of this Mac hold no occurrence. The redactor has no rule for emails or account ids. `reproduce` uses the same `Anonymizer` with its own allowlist (see Reproduce).

**Exact bytes.** Standard output carries the capsule, a ranking's canonical JSON and an Atlas transcript exactly as encoded. An earlier build sent them through Clikt's `echo`, which rewrote U+0085 and U+2028 to a line feed and expanded each TAB to spaces. `drift capture --probes` on the macOS release executable then printed raw line breaks inside the JSON string of `kotlin.string.split-replace-lines`, and a strict parser rejected the output at column 35,430. That was a bug, and `capture`, `diagnose`, `atlas show`, `atlas compare` and `atlas record` without `--out` now write UTF-8 bytes through one `expect fun` per target (`System.out.write` on the JVM, `fwrite` on native, `process.stdout.write` on Node). The text of `case`, `next`, `reproduce`, `solve`, `ingest`, `pack` and `report` still goes through `echo`. `DriftCommandTest.captureBytesSurviveEveryLineSeparatorAndALongLine` pins U+0085, U+2028, U+2029, CRLF, TAB, NUL, U+FFFD and a 1 MiB line on all three targets. With the macOS release executable, `drift capture --probes --tools` parses with a strict JSON parser and its output holds one raw U+0085 and one raw U+2028.

---

## Scanners and the Kit

A scanner runs one command and parses its first matching line with plain character scanning. `Scanners.all` lists 23.

| tool | command | attributes under `tool.<name>.` |
| --- | --- | --- |
| `java` | `java -version` | `implementation`, `version` |
| `cc` | `gcc --version` | `family` (gcc or clang), `version`, `vendor` (`apple` or `llvm`, clang only) |
| `go` | `go version` | `version`, `os`, `arch` |
| `php` | `php -v` | `version`, `sapi`, `thread-safety` |
| `node` | `node --version` | `version` |
| `npm` | `npm --version` | `version` |
| `python` | `python3 --version` | `version` |
| `rust` | `rustc --version` | `version` |
| `git` | `git --version` | `version` |
| `coreutils` | `ls --version` | `flavor` (gnu, busybox, other), `version` |
| `libc` | `ldd --version` | `family` (glibc, musl), `version` |
| `visualstudio` | `vswhere -latest -property installationVersion` | `version` |
| `python-managed` (group `python`) | `python3 -c` checking for `EXTERNALLY-MANAGED` | `externally-managed` |
| `python-gil` (group `python`) | `python3 -c` reading the GIL state | `gil-disabled` |
| `openssl` | `openssl version` | `version`, `family` (a LibreSSL gives `libressl` and `libressl-version`) |
| `openssl-providers` (namespace `runtime.openssl.`) | `openssl list -providers` | `legacy-provider` (`true` or `false`, OpenSSL 3 only) |
| `ssh` | `ssh -V` | `version`, `release` |
| `composer` | `composer --version` | `version` |
| `sudo` | `sudo --version` | `version`, `release`, `flavor` (`ws` or `rs`) |
| `ruby` | `ruby --version` | `version` |
| `named` | `named -v` | `version` |
| `iptables` | `iptables --version` | `version`, `backend` (`nft` or `legacy`, only when the line names it) |
| `curl` | `curl --version` | `version` |

A missing tool (exit 127) writes nothing. Output that matches no parser writes `tool.<name>.version = unparsed` and a `raw` attribute holding the first line cut to 120 characters and redacted. The grouped scanner never writes that fallback, so a failed marker check cannot overwrite `tool.python.version`.

The kit is the same scanner list as a script. `Kit.sh()` and `Kit.ps1()` emit a script that runs every scanner command and wraps each output in `@@ <tool> exit=<code>` markers; `Kit.parse` and `Scanners.fromTranscripts` turn the captured text into the same attributes. An environment without the `drift` binary, such as a minimal container, can run the script and have the result parsed elsewhere. `fixtures/transcripts` holds 77 recorded outputs across 23 tool folders, captured in Docker on an arm64 Mac (`tools/capture-transcripts.sh` has the commands for the first set; the macOS transcripts of the later scanners were taken by hand on the Mac). The two Visual Studio files are synthetic, since `vswhere` cannot run in Docker; everything else is real tool output. A rule that reads a scanner's attribute for an absent tool sees no value and does not match, except for rules that name `present: 'false'` with an explicit value on the other side.

---

## Project and Source Facts

`drift capture --project`, `drift run --project` and `drift contract verify --capture` (for assertions under `project.`) read the project files of the working directory and add `project.<ecosystem>.<name>` attributes. The prefix maps to the `build` dimension. The ecosystems are Composer, npm (with pnpm, Yarn classic, Yarn Berry and Bun lockfiles), Gradle (version catalog, settings, wrapper, build scripts and the daemon JVM properties), Maven, Python (uv, Poetry and requirements files) and Go. A value is a resolved version string or a constraint. The scanner never runs a tool and never uses the network; a file that is missing or does not parse adds nothing, and on the Wasm build, which has no file access, nothing is added. At most 200 package keys per ecosystem are kept: packages that some rule names come first, then direct dependencies, then the rest by name, and `project.<ecosystem>.omitted` records how many were left out. The cap ordering matters because a Drupal capture holds hundreds of packages and the rule for Drupal 11 reads `drupal/core`. `fixtures/projects` holds 13 project trees (Composer, five npm lockfile forms, Gradle in Kotlin and Groovy, Maven, three Python forms and Go) that the scanner tests read.

The same switch turns on a source scan. It lists the source files with `find` through the host and matches imports and a fixed table of call sites by regular expression. The keys are `project.<ecosystem>.imports.<name>` (Python, Java and Kotlin, PHP and JavaScript; each dotted prefix of an import is a key) and `project.<ecosystem>.calls.<name>` for the call shapes that some rule names, plus `project.gradle.calls.*` for build-script calls and `project.npm.lockfile-version`. The scan reads at most 500 files per ecosystem in sorted order, 200,000 characters per file and 16,000,000 characters in all, and skips `node_modules`, `vendor`, `build`, `.gradle`, virtual environments, `target`, `dist` and `.git`. The rules that read these keys describe code, so they are the only ones that can say that a project uses the thing a version removed.

The limits are the limits of text matching. An import inside a comment or a string counts. A Gradle version held in a Kotlin variable is read only for `libs.versions.*` and `kotlin_version`, and a plugin key means that the plugin is declared, not that it is applied. Windows and Wasm give no source facts, since neither has a listing command here. Running the scan on the Drift tree itself found a false positive (a `baseName` assignment inside a Kotlin/Native `binaries.executable` block was read as an archive task); it was fixed and `ProjectScannerTest` pins it. That test class has 33 tests on each Gradle target, and one of them reverses the order of the file listing and requires equal attributes. Real runs covered the Gradle, Kotlin and fixture trees; the Python, PHP and JavaScript signals were checked on fixture text only.

---

## Diff and Dimensions

`CapsuleDiff.diff(green, red)` compares two capsules and returns three lists: `changes` (static attributes that were added, removed or changed), `ignored` (volatile and noisy attributes, with the less stable side deciding), and one entry per probe id with status `same`, `different` or `unavailable`.

Two details matter for correctness.

- **Order.** For a changed value, `order` says whether the version went up, stayed equal or went down. Values are parsed as versions (the JAVA scheme under `tool.java.`, so `1.8.0_504` and `8.0.504+1` parse as the same core). A changed value whose core compares equal, such as two build timestamps, reports `unordered`, never `equal`. An earlier version of the diff reported `equal` for a changed build date; `DiffTest.aChangedValueNeverReportsEqualOrder` pins the fix.
- **Operating system names.** Every host now records `os.name` in one vocabulary: `linux`, `macos`, `windows`, `android`, `ios`, `freebsd` or `unknown`. Before that change the JVM wrote `mac` and a native Mac build wrote `macosx` on the same machine. `normalizeOs` in `host` maps a platform name to the vocabulary (`mac*` and `darwin` to `macos`, `windows*` and `mingw*` to `windows`), and `core` keeps a second function for the legacy spellings in older capsules (`mac`, `macosx`, `mac os x`) so recorded capsules and diffs do not move; `OsNameTest` asserts that the two agree. `unknown`, which Wasm reports, is not mapped, so a Wasm capture against a Mac shows an `os` change.

Each changed path maps to one of 14 dimensions by longest matching prefix: `os`, `kernel`, `cpu`, `cpu-limit`, `memory`, `runtime`, `compiler`, `build`, `userland`, `env`, `locale`, `limits`, `network` and `browser`. For example `tool.node.` is `runtime`, `tool.npm.` and `deps.` and `ci.` are `build`, `env.TZ` is `locale`, and `env.HTTPS_PROXY` is `network`. A path no prefix claims has no dimension; it is listed under `changes` and never ranked. [ADVANCED_USAGE.md](./ADVANCED_USAGE.md#attribute-prefixes) holds the full prefix table. Probe ids map the same way, so a probe that differs in a dimension can support a candidate in that dimension.

---

## Rules and Provenance

A rule records a documented difference between two environments that can break software. The catalog has 298 rules in `rules/**/*.yml`, one folder per subject (27 folders), each with a mechanism, one or more symptoms, a matcher, fixtures and cited sources. 207 are `active`. The other 91 are `pending-scanner`: they read an attribute that no scanner emits yet (a database server version, the TLS versions a peer offers, a browser name, a source-level key), they are compiled and checked like the rest, and they never match a capture that lacks the attribute. `RuleCatalogTest` prints the keys they are waiting for.

A rule file has `schema: 1`, an `id` equal to the file name, a `title`, `applies` (dimension ids), `difference`, `symptoms`, `mechanism` (one sentence), `detect`, `severity` (`low`, `medium`, `high`), `provenance`, `fixtures` and `status` (`active` or `pending-scanner`). A symptom has a `basis`: `verified` when the cited source states the symptom, `inferred` when it only states the mechanism. The matcher is a small language over a passing side `a` and a failing side `b`: `eq`, `ne`, `in`, `has`, `starts`, `ver` (version ranges), `present`, `changed` (with a trailing `*`), and `all`, `any`, `not`, to a depth of 8. This is a rule from the catalog:

```yaml
id: node-localhost-ipv6-first
applies:
  - runtime
  - network
detect:
  all:
    - b: tool.node.version
      ver: '>=17.0.0,<18.18.0'
    - a: tool.node.version
      ver:
        - '<17.0.0'
        - '>=18.18.0'
severity: medium
```

Rule files are YAML because people maintain them by hand. snakeyaml-engine 3.2 parses them at build time under the failsafe schema, so every scalar is a string (`1.20`, `no`, `0o17` and `~` stay strings), and `embedRules` writes the JSON that `core` reads. Common code never parses YAML. A stray `rules/**/*.json` fails the build, and the embedded JSON was byte-identical to what the earlier JSON catalog produced when the format changed (32 of 32 rules at that time).

The checker, `Constraints.check`, runs in the `core` test suite, so on every build. It validates each rule (id shape, non-empty fields, one-sentence mechanism, dimension ids, provenance, fixtures), requires unique ids, requires every positive fixture to match and every negative fixture not to match, and lists every `pending-scanner` rule (91 today). A scan-side test (`RuleCatalogTest`) checks that every attribute path an active rule reads is one that capture or a scanner emits, and that a pending rule reads at least one that none emits.

| property | count |
| --- | --- |
| rules, active / pending-scanner | 298: 207 / 91 |
| severity high / medium / low | 85 / 166 / 47 |
| fixtures, positive / negative | 303 / 610 |
| cited sources | 338 entries, 210 distinct locations |
| source kinds: measurement / vendor-doc / release-note / spec | 14 / 138 / 145 / 41 |
| symptoms, `verified` / `inferred` | 86 / 233 |
| rules with at least one `inferred` symptom | 230 |

A matched rule gives a candidate the `known` tier and a weight by severity, with its mechanism text attached. A rule whose matcher touches no changed attribute is listed as unattached and never dropped.

The rule sources are cited and not reproduced. The fixtures are synthetic pairs written to exercise the matcher. They show that the rule fires on the stated versions and stays quiet on others; they do not show that the failure occurs. The 335 provenance entries name 208 distinct locations: 207 web URLs and 1 `fixture:` reference, which the verifier skips. `tools` includes a verifier that fetches each distinct web URL and records the HTTP status. Its unit tests use a fake fetcher. A run against the network covered 3 URLs when it was written (all 3 returned HTTP 200). A run over all 207 web URLs is not recorded. An HTTP 200 shows the page exists, not that the quoted section still does. Most symptoms are `inferred` (232 of 316): the cited page states the mechanism, and the symptom is what that mechanism implies.

**Catalog by subject.**

| folder | rules | active | pending | folder | rules | active | pending |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `browser` | 2 | 1 | 1 | `libc` | 6 | 3 | 3 |
| `clang` | 5 | 4 | 1 | `network` | 4 | 3 | 1 |
| `composer` | 3 | 3 | 0 | `node` | 18 | 17 | 1 |
| `database` | 13 | 0 | 13 | `os-image` | 25 | 17 | 8 |
| `do-sqlite` | 6 | 6 | 0 | `php` | 35 | 33 | 2 |
| `docker` | 7 | 0 | 7 | `python` | 29 | 25 | 4 |
| `drupal` | 21 | 8 | 13 | `runner` | 7 | 4 | 3 |
| `filesystem` | 4 | 0 | 4 | `spring` | 3 | 2 | 1 |
| `gcc` | 8 | 7 | 1 | `symfony` | 17 | 7 | 10 |
| `git` | 1 | 1 | 0 | `teavm` | 2 | 2 | 0 |
| `go` | 3 | 1 | 2 | `tls` | 4 | 2 | 2 |
| `gradle` | 12 | 11 | 1 | `tz` | 5 | 0 | 5 |
| `jdk` | 35 | 27 | 8 | `workerd` | 3 | 3 | 0 |
| `kotlin` | 20 | 20 | 0 |  | | | |
| total | 298 | 207 | 91 | | | | |

Three shapes of rule exist. Version-boundary rules read only version keys (`ver` on `tool.php.version` and the like) and say that a documented change lies between two versions. Fact rules also read a project or system fact, such as `project.composer.require.<name>`, `runtime.openssl.legacy-provider` or `tool.sudo.flavor`, and match only when the fact is present. Added-API rules (36 of them: PHP 12, Python 12, JDK 8, Node 4) read a call site or import under `project.<eco>.calls.*` on the failing side and an older runtime there; they describe code that a newer runtime accepts and an older one rejects, so they fire for a CI runner older than the developer machine and not for an upgrade (see Upgrade Check). The 15 older rules with a single negative fixture were left as written; the compiler allows one.

---

## History and Vacuous Green

A diff of two capsules compares two points. A run history adds which runs passed. `History` orders the runs of one workflow and job (as given, or by numeric run id) and classifies it over its decisive runs, those that passed or failed; cancelled and unknown runs are skipped without breaking adjacency.

| state | meaning |
| --- | --- |
| `EMPTY`, `SINGLE` | no decisive run, or one |
| `ALL_GREEN`, `NEVER_GREEN` | no red run, or no green run |
| `REGRESSION` | one flip, green first |
| `RECOVERED` | one flip, red first |
| `FLAKY` | two or more flips |

The transition is the last green before the first red. In a flaky history it is the first transition only, and `state` tells a consumer not to trust it.

`GreenToRed` combines the capsule diff with step changes. Its kind is `NO_CAPSULE`, `UNEXPLAINED`, `SINGLE` or `BUNDLE`. A bundle counts changed static attributes, differing probes and added or removed steps, and a bundle is never reported as one cause. Volatile differences go to `ignored`.

A passing run is only evidence if it executed the step that failed. `VacuousGreen.assess` returns `EXECUTED` when the matching step succeeded or failed in that run, `VACUOUS` when the step list lacks the failing step or the step was skipped, `UNKNOWN` when step data is missing, and `NOT_PASS` for a run that did not pass. A vacuous or step-less green is excluded from the spectrum evidence and listed under `excluded`, so it never changes a ranking.

`Run` is the record a history is made of: id, outcome, ordered steps with conclusion and duration, extracted symptoms, and an optional capsule. `SymptomExtractor` reads GitHub Actions logs and returns symptoms of kind `SIGNATURE`, `EXIT`, `SIGNAL`, `STEP`, `DURATION` and `TRANSPORT`. A signature is the error message after `Signature.normalize`: secrets masked, temporary paths, timestamps, UUIDs, hex runs, durations, counters and ports replaced with placeholders, whitespace collapsed, cut at 200 characters. Normalization is character scanning over ASCII classes, with no regular expressions, so it is identical on every target. The extractor never throws; the tests feed it truncated logs, a 50-million-character line and lone surrogates.

History mode runs through the library and its tests. `drift diagnose` takes two capsules.

---

## Ranking and Bundles

`Ranker.rank` turns a diff into candidates. There is one candidate per changed attribute that has a dimension. Its score is a sum of log-odds in fixed point: each evidence item is a likelihood ratio in micro-units and the combiner adds `ln(ratio)`.

| evidence | ratio | note |
| --- | --- | --- |
| dimension prior | 1.5x to 3x | runtime and compiler 3x; build and userland 2.5x; os, kernel, cpu-limit and memory 2x; the other six 1.5x |
| a probe that differs reads the candidate's attribute | 6x | the first such probe only; further probes are listed at weight 0 |
| a probe in the same dimension differs but does not read the attribute | 1x | listed, no log-odds; no ratio beat 1 on the dev data |
| rule match | 6x, 12x, 24x | by severity low, medium, high |
| spectrum (Ochiai) | up to 4x | `1 + 3 * score * coverage`, needs at least 2 included passes and 1 failure |

The spectrum score is Ochiai, `ef / sqrt((ef + nf) * (ef + ep))`, computed in integers as `isqrt(ef^2 * 10^12 / d)`, where `ef` is the number of failing runs that contain the changed value, `nf` the failing runs that do not, and `ep` the passing runs that do. Coverage discounts sparse histories: it is the lowest share of the history's attribute universe present in any included run.

A candidate's tier is the strongest evidence it has.

| tier | requires |
| --- | --- |
| `difference` | the attribute changed |
| `correlated` | spectrum score of at least 0.5, or a probe in the candidate's dimension that differs but does not read its attribute |
| `plausible` | a probe that differs and reads the candidate's attribute, or a matched rule with any inferred symptom |
| `known` | a matched rule whose symptoms are all verified |

There is no stronger tier. A candidate with no probe or rule evidence never exceeds `correlated`; a property test over 60 random histories checks it. The policy is written once, in the KDoc of `Ranker`.

**Probe links.** `ProbeLinks` maps each of the 33 general probes to the attribute path prefixes its output depends on: the numeric, text, collection and duration probes run inside the Drift process and read `runtime.platform` and `tool.java.`; the shell probes read `tool.coreutils.`, `os.name` and `os.release.`; `process.timezone` reads `env.TZ`; `resources.locale-env` reads `env.LANG`, `env.LC_` and `env.TZ`; the two cgroup probes read `cgroup.cpu.` and `cgroup.memory.`; two probes read nothing. The prefix lists are a reading of the probe sources, not a measurement, and `tool.java.` for the in-process probes assumes the `java` on `PATH` is the JVM that runs Drift. A probe in the candidate's dimension that does not read its attribute no longer counts for the candidate, which removes a rule that supported every changed attribute of a dimension whether or not the probe could have seen it. On the 45 dev scenarios and 32 rules of that time the change moved nothing: every ranking, tier and rate was identical before and after (see Calibration on the Dev Split, which says that this was not repeated on the current catalog). The benefit is by construction, tested with a property test over random capsule pairs, and is not measured.

**Bundles.** Candidates with the same tier, the same probe and rule evidence and the same Ochiai counts cannot be told apart by the data. They share a bundle id, and no member of a bundle is reported as the cause on its own. Order inside a bundle comes from the dimension prior alone and means nothing.

**Probability.** The ranking carries a probability only when every candidate rests on attribute evidence alone. It is the candidate's share of the softmax over all scores plus a fitted weight for "none of the listed candidates is the cause" (0.25). The shares of a ranking add up to less than one. Where any candidate has probe, rule or spectrum evidence, every probability is null.

Pair mode, the real drangler failure, a passing run against a failing one:

```sh
drift diagnose fixtures/drangler/capsules/36702683742.json fixtures/drangler/capsules/36995781138.json --detail summary --case case
```

```
wrote 11 files to case
9 changes differ with the same support, and tool.node.version ranks first only by hand-set weights,
so the data does not name one cause.
```

At `--detail full` the nine candidates are all tier `difference`: `tool.node.version` scores 1.098612 (runtime, 3x) with probability 0.129032, and the other eight score 0.916291 (build or userland, 2.5x) with probability 0.107527 each. `tool.yarn.version` is the ninth: it has no scanner dimension of its own and reaches `userland` through the general `tool.` prefix, so it was unranked before that mapping existed. The nine probabilities sum to 0.989, which is the fitted "none" weight showing; the real case is outside the distribution that weight was fitted on (see Evaluation and Limits).

History mode over the same case, from `RankHistoryTest.theDranglerTransitionIsOneBundleNoCandidateIsTheCause` (5 passing runs, 3 failing runs, job `Docker E2E`): the transition is a `BUNDLE` with three added steps (`Set up Node`, `Post Set up Node`, `Dump Host State on Failure`); all 9 candidates are tier `correlated`, basis `inferred`, in one bundle `bundle-1`; every candidate has spectrum counts `ef 3, nf 0, ep 0`, score 1.000000 and coverage 0.862068 (25 of 29 attributes present in each passing run). The data separates nothing: every changed value is present in all three failing runs and in none of the five passing ones. The catalog fires no rule on this pair. The ranking's order within the bundle is the dimension prior.

---

## The Experiment Planner

The planner takes a ranking and proposes experiments. Each experiment sets some attributes of the passing environment to the failing side's values and compares failures against an unchanged control.

**Hypotheses and prior.** Each candidate is a hypothesis. Candidates that are one change seen twice can be coupled (`ci.provisioner.build-date` with `ci.provisioner.version`), and an attribute that comes from a step can couple to that step. The prior is `exp(score - max)` over the ranking scores, normalized by largest remainder, with a fixed 25% reserved for `unknown`. In pair mode on the drangler capsules there are 8 hypotheses plus `unknown`, and the prior entropy is 3.059 bits.

**Controllability.** `Controls.standard` is a hand-set table of 16 prefixes saying where an attribute can be set. `deps.`, `tool.`, `runtime.`, `env.` and `browser.` can be set locally and in CI; `limits.`, `cgroup.`, `memory.`, `network.` and `os.release.` locally; `step:` only in CI; `ci.`, `os.`, `kernel.` and `cpu.` in neither, since the runner provider or the host owns them. An experiment is `automatic-ci`, `automatic-local` or `manual`.

**Experiment pool.** For each controllable hypothesis as the single culprit, delta debugging (`Ddmin`) runs over the controllable set with the test "fails exactly when the culprit is in the subset". The union of the tested subsets is the pool: the full set, halves, quarters and singletons. Manual experiments for the uncontrollable hypotheses are added with instructions.

**Outcome model.** An experiment is `reproduced` when the treatment arm fails significantly more often than the control. The probability of that outcome is the achieved power of the trial plan when the culprit is in the flipped set, and `alpha` (0.05) when it is not; both are clamped to a 1% floor. The unknown mass behaves like a non-culprit, so the planner never claims to separate "none of these" from something it cannot flip.

**Exact test and trials.** The significance test is a one-sided Fisher exact test computed in `Long` arithmetic (`Fisher.oneSided`, total trials capped at 60 so `C(60,30) * 30` fits). The failure rate is Laplace-smoothed from the failures and runs observed on the red side. The planner picks the smallest trials-per-arm count, at least the Fisher minimum, whose chance of reaching alpha against a control that never fails is at least 0.8, and refuses an experiment that no count up to 30 can reach (status `STUCK`, reason `budget`).

**Information gain and cost.** Expected information gain (EIG) is one-step: prior entropy minus the expected posterior entropy over the two outcomes, in micro-bits (`log2` is exact on powers of two, and every entropy term rounds to the nearest micro-bit). Cost is `1.0` per waiting minute, `2.0` per runner minute and `0.5` per trial, plus 5 queue minutes in CI and 120 minutes for a manual experiment. Experiments rank by EIG per cost point, then EIG, then cost, then id.

Next experiment for the drangler pair (defaults `--failures 1 --runs 1 --trial-minutes 5`, written into `plan.json`):

```sh
drift next case --detail detail
```

```
Next experiment (a proposal, nothing has been run):
  e11: drangler-36702683742 with deps.@napi-rs/keyring.version, deps.prettier-plugin-sh.version,
    deps.wrangler.version, tool.node.version, tool.npm.version, tool.yarn.version set to
    drangler-36995781138 values
    automatic-ci; 10 min, 70 runner min, 14 trials
    why: highest information gain per cost among the experiments that can run
    expected information gain 0.499 bits from the hand-set prior (a prediction, not a measurement)
    ...
    outcomes: reproduced 0.490; not-reproduced 0.510
```

The plan has 11 automatic CI experiments and 2 manual ones. All 11 automatic experiments cost the same (157 points: 10 minutes, 70 runner minutes, 14 trials at 7 per arm), so EIG alone orders them. Seven trials per arm with a clean control and a threshold of 4 failures: the exact one-sided p for 4 of 7 against 0 of 7 is 35/1001 = 0.0350, and for 3 of 7 it is 0.0962. The power at the smoothed rate of 2/3 is 1808/2187 = 0.8267. Both figures reproduce by hand from the formulas above, and so does the EIG: recomputing it from the prior masses, the power and alpha gives 0.49854 bits and P(reproduced) 0.490447 (the plan stores 0.498530 and 0.490, after rounding each entropy term to a micro-bit).

The first experiment flips all six controllable hypotheses together. Part of the mass cannot be flipped at all (unknown 25%, runner image and provisioner about 18%), so asking whether the controllable set reproduces the failure at all gains more than splitting it: 0.499 bits against 0.455 for the best split of three (`e10`) and 0.350 for a split of two (`e7`). Over 8 equally likely controllable members, a half split goes first (`PlanTest.theFirstExperimentOnEightEquallyLikelyMembersSplitsTheBundleInHalf`).

The plan also reports what no experiment separates: here `{ci.provisioner.version, ci.runner.image.version, unknown}`, because the runner provider owns the image and the provisioner. If the cause is there, the best an automatic experiment can reach is "none of the controllable ones", with the mass moving to that set. Given the red run's steps, the planner drops a step that ran after the failing step: `Dump Host State on Failure` was added in the drangler window, follows the failing step, and cannot be a cause. The planner proposes experiments and predicted information. It records no result.

The cost weights, queue and manual minutes, the 25% unknown prior, alpha and power are guesses with a written reason each in `PlanWeights`, and none is calibrated.

---

## Case Directories

`drift diagnose --case DIR` writes a case: canonical JSON files, LF line endings, no timestamps, with a manifest and a hash chain over the observations.

```
case.json                   schema, name, frame (sha or environment), observation count, chain head
manifest.json               every other file with its sha256, sorted
environments/passing.json   the two capsules
environments/failing.json
observations/0001.json      hash-chained records
observations/0002.json
hypotheses/ranking.json     the ranking: tiers, bundles, weights note, unattached, excluded runs
hypotheses/prior.json       hypotheses and the prior belief
experiments/plan.json       the full plan: gains, costs, per-outcome posteriors, instructions
results/index.json          {"results":[]}
eliminations/excluded.json  candidates and runs left out, with reasons
```

An observation record holds its content plus `seq`, `prev` and `hash`, where `hash = sha256(prev + "\n" + canonical(record without prev and hash))` and the first `prev` is 64 zeros. `case.json` stores the head. The record in `observations/0002.json` for the failing capsule shows the shape:

```
{"capsule":"40380c70...","file":"environments/failing.json","hash":"51c3d18e...","kind":"capsule","label":"drangler-36995781138","prev":"e495b6a8...","role":"failing","seq":2}
```

The reader never throws. A case reads back as `Loaded` or `Failed` with a typed problem: `MissingFile`, `MalformedFile` (truncated or garbage), `UnsupportedSchema` (any schema other than 1), and unsafe manifest paths. `CaseFile.check()` compares every file with the manifest and walks the chain. After replacing `drangler-36995781138` with `drangler-X` in `observations/0002.json`:

```sh
drift case show case
```

```
case: drangler-36702683742 to drangler-36995781138
frame: sha, passing drangler-36702683742, failing drangler-36995781138
observations: 2, chain head 51c3d18ef57180a4ee97f29a1afd87d1584bc5f95e8239e0664786ab85621c3b
integrity: 2 problem(s)
  observations/0002.json was changed: sha256 is 99750ce6..., the manifest has 06ad04b2...
  observation chain broken at entry 1 (observations/0002.json): content does not match its hash
```

The command exits 1, and `drift next` refuses the case. Without the edit it prints `integrity: ok, 10 files match the manifest and the chain holds` and a line `rule sha256: 8bf205db...` with the hash of the preregistered decision rule, the value that `ingest` expects in each result file as `ruleSha256`; `drift next --detail detail` prints the same hash after the experiments. In a `diagnose` case the chain covers `observations/` only and `results/` is an empty index; `solve` fills `results/` with records that continue the chain (see The Lab).

Rendering has three levels, `summary`, `detail` and `full`, in plain text wrapped at 100 columns and ASCII only. A live result and a case read back go through the same renderer. The text says "a proposal, nothing has been run" for experiments, "a prediction, not a measurement" for gains, and "none is the cause on its own" for a bundle. The 11 files the macOS release executable wrote for the example above have the sha256 values that `CaseCommandTest.CASE_SHAS` pins, and that test asserts them on the JVM, macOS Native and Wasm.

---

## Reproduce

`drift reproduce <capsule> --run <command> --out <dir>` writes `Dockerfile`, `run.sh` and `manifest.json`. It does not run Docker and does not read the network. The three files are a function of the capsule and the command: two runs produce identical bytes.

```sh
drift reproduce fixtures/lab/node-alpine.json --run "npm test" --out repro
```

```
wrote 3 files to repro
23 attributes: 10 mirrored, 4 partially mirrored, 9 not mirrored; base image node:22.12.0-alpine3.20
This is not the original environment. Attributes that are not mirrored are listed with their reasons
  at --detail detail.
```

```
# drift reproduce: capsule 540e5c22...0531; manifest.json says what is not mirrored
FROM node:22.12.0-alpine3.20
RUN apk add --no-cache tzdata
RUN npm install -g npm@10.9.2
WORKDIR /work
```

`run.sh` then builds the image, tagged `drift-reproduce:<first 12 hex of the Dockerfile hash>`, and runs it with `--platform linux/arm64`, `--user "$(id -u):$(id -g)"`, `--volume "$PWD:/work"`, `HOME=/tmp`, `--memory 512m`, `--cpus 1.2`, `--ulimit nofile=2048:2048` and the mirrored `LANG` and `TZ`.

**What is mirrored.** One base image per container: `node:`, `python:`, `golang:`, `php:`, `rust:`, `gcc:` or `eclipse-temurin:` from the toolchain attribute (chosen by the first word of `--run` that names a toolchain), else `ubuntu`, `debian` or `alpine` from the OS attributes, else `debian:12-slim`. Debian codenames come from `os.release`. `--digests` pins tags to digests. `cgroup.memory.max` becomes `--memory`, `cgroup.cpu.max` becomes `--cpus`, `limits.<name>` becomes `--ulimit`, `os.arch` becomes `--platform`, and `tool.npm.version` becomes an `npm install -g` pin on a Node base.

**What is not.** The manifest gives one status per attribute (`mirrored`, `partial`, `not-mirrored`) and a reason. For the example above the nine not-mirrored attributes include `cpu.count` ("a container runs on the Docker host's CPU"), `kernel.release`, `deps.vitest.version` (installed from the workspace lockfile), `tool.libc.version` and `tool.git.version` (come with the base image), `env.HOME` (host identity), two redacted variables, and `env.NODE_ENV` ("not allowlisted"). Never mirrored in any capsule: `kernel.*`, `cpu.*`, `hw.*`, `ci.*` (the runner provider owns the image and provisioner), `deps.*`, and anything describing the capturing binary (`drift.*`, `kotlin.*`, `runtime.*`).

**Identifiers.** By default only `LANG`, `LC_*` and `TZ` are copied from `env.*`. `--env NAME` adds one variable, and a name the capsule lacks is refused. `--env-all` adds all of them and prints a warning, because values may hold identifiers and secrets. Home paths (`/Users/<name>`, `/home/<name>`, `C:\Users\<name>`) and the capsule's own account and host names are replaced by `user` and `host` in anything written. A redacted or secret-looking value is never written, and the label passes through `Redactor` too. The redactor has no rule for emails or account ids, so under `--env-all` those are copied as written.

**Overwriting.** `--out` refuses a directory that already holds a reproduction and names the three files. `--force` replaces those three files and nothing else, and only when each one was written by `drift reproduce` (its header line, its manifest keys); a user's own `Dockerfile` makes it refuse. Nothing is deleted. Every command's `--help` marks required options and shows defaults.

On the real red drangler capsule with `--run "npm test"`: 30 attributes, 3 mirrored (`tool.node.version`, `tool.npm.version`, `env.REQUIRE_DOCKER`) and 27 not, base image `node:24.21.0-bookworm-slim`. The 7 `ci.*` attributes, which are what the data points at, are exactly the ones a container cannot mirror.

Container builds from generated files were run by hand on a Mac and on a Linux host (pinned Node and npm versions, locale, timezone, `ulimit`, `memory.max` and `cpu.max` read back inside the container). No test in the tree builds an image. The tests pin the sha256 of the three files for four fixtures on all three targets.

---

## The Lab

The planner proposes experiments. The Lab runs them. `drift solve <green> <red>` takes the capsules of a passing and a failing run, the failing command (`--run`) and a failure test (`--fail-when`, either `exit` for a nonzero exit code or a pattern that the output of a failing trial matches), then runs counterfactual arms in Docker on the local machine. `--budget trials=N,minutes=M` caps the whole run (default `trials=300,minutes=45`), `--pilot` sets the trials per baseline (default 8), and `--timeout` cuts one trial off (default 120 seconds). `--exit-status` makes the exit code carry the verdict (0 CONFIRMED, 10 CONFIRMED EFFECT (bundle), 11 NARROWED, 12 STUCK); without it a finished run exits 0, and 1 and 2 stay errors and usage. `reproduce` supplies every container, so the arms are evidence about containers built from the capsules and not about the original host. The certificate says so.

**Interventions and controllability.** An intervention is one typed edit of a run configuration: set or unset an environment variable, set the locale, the timezone, a CPU or memory limit, a `ulimit`, a toolchain version or an operating system release, declare the platform a Kotlin program runs on, set a flag, write a file, toggle a workflow step or pin a dependency (14 types in `Intervention.kt`). One that cannot apply returns a typed problem (invalid, not applicable in this environment, no effect, or not mirrored into the container) and never runs. `Controllability.classify` sorts each candidate into automatic-local, automatic-CI or manual. It starts from the planner's `Controls` table, so a dimension the planner calls manual is never promoted, then demotes what the `reproduce` manifest says a container cannot mirror. A member that only travels with a base image (`tool.libc.`, `tool.coreutils.`, `tool.git.`) is manual, because nothing sets it alone. The loop re-plans without any hypothesis its arms cannot set, and never runs an experiment it knows cannot isolate its target.

**The bundle rule.** An experiment is two arms, a control and a treatment, each a full run configuration with its own Dockerfile, `run.sh` and manifest. After the arms run, Drift captures a capsule inside each container and diffs the two. If exactly one non-volatile attribute differs and it is the one the experiment set, the effect is isolated. If several differ, or the one that differs is not the target, the verdict is a bundle: every member is listed, with the ones that were not isolated. A swap of the Node image, for example, also changes glibc, coreutils and the OS release, and the diff shows it. If nothing differs, no effect can be attributed. A bundle is never reported as one cause.

**Trial plans and the preregistered rule.** After the pilot, `TrialSizing` takes the failure rate seen in the failing baseline and the planner's exact test to pick the smallest trials-per-arm count that reaches power 0.8 against a control that never fails, inside the remaining budget. If no count up to 30 per arm reaches it, or the budget is too small, the run ends `STUCK` with reason `budget` and names the shortfall. Before any trial of an experiment, the loop writes `experiments/<id>.json`: the arms, the trials per arm, and the decision rule (a one-sided Fisher exact test at alpha 0.05 with the threshold count), with the SHA-256 of the rule. The result file records that hash. A result is `SUPPORTED` when the treatment arm fails significantly more often than the control, `REFUTED` when a treatment arm that failed at the observed baseline rate would show as few failures as this one with probability at most alpha (a binomial lower tail), and `INCONCLUSIVE` otherwise. The belief over hypotheses then moves through the planner's outcome table; every posterior is in the certificate.

**The closed loop.**

1. The pilot runs a passing baseline and a failing baseline. No failure in the failing baseline ends the run as `STUCK` (`not-reproduced`). A passing baseline that fails as often as the failing one ends it as `STUCK`, `uncontrollable` when the planner lists manual candidates and `not-reproduced` otherwise. The pilot is not evidence; it sizes the arms and stops early.
2. Each round takes the best executable experiment that was not tried, writes its preregistered spec and runs control and treatment trials alternately. A docker error or a timeout is an infrastructure problem, retried once and then `STUCK` (`executor`).
3. The first `SUPPORTED` experiment starts the minimal-set search. Delta debugging runs over the flipped hypotheses, and each tested subset is a real preregistered experiment. A subset that is `INCONCLUSIVE`, or one that the budget stops, is unresolved.
4. A reverse arm takes the failing capsule and moves the minimal set back to the passing values; the unchanged failing arm must fail more. When the reverse arms cannot be built, a replication of the forward arms takes their place.
5. The measured arm diff and the bundle rule give the verdict.

The loop assumes a single cause: the first supported experiment ends it.

| verdict | meaning |
| --- | --- |
| CONFIRMED | a minimal set of attributes, all set directly, reproduces the failure forward and removes it in reverse, with no unresolved subset |
| CONFIRMED EFFECT (bundle) | the same, but the arms differ in attributes nobody set; every member is named and the unisolated ones are listed |
| NARROWED | at least one experiment was supported or stayed inconclusive, and the conditions of the two verdicts above are not met |
| STUCK | no experiment was supported; the reason is `not-reproduced`, `uncontrollable`, `no-candidates`, `budget` or `executor` |

The mechanism field comes from re-ranking the two captured arms. A rule match or a differing probe on a member records it, and otherwise the certificate says `unexplained`.

**Ingest.** `drift ingest <case> <results>` adds result files that someone else produced, for example from a CI workflow variant that `drift next` described. Every file must name a known experiment, carry that experiment's rule hash and the preregistered trial count, and hold consistent counts; otherwise it is refused with a typed reason, and a batch with one bad file applies nothing. A result narrows the case or rules a hypothesis out. `ingest` cannot confirm, because confirmation needs the minimal-set and reverse experiments, which are separate preregistered runs. `ingest <case> <dir> --template` writes one skeleton result file per open experiment, each carrying the rule hash and the trial count, with the failure counts left empty; an unfilled file is refused and an existing file is never overwritten. After an `ingest`, `drift next` names the experiments that already have a result and proposes the best of the others. It does not re-plan from the updated beliefs, and its output says so; `--exit-status` works on `ingest` the same way as on `solve`.

**The case directory.** A solved case holds the files of a `diagnose` case plus `experiments/<id>.json`, `arms/<id>-control` and `-treatment` (Dockerfile, `run.sh`, `manifest.json` and the captured `capsule.json`), `results/NNNN.json` and `certificate.json`. The result records continue the hash chain of the observations, each with every trial's exit code and last line of output. The certificate holds the verdict and its conditions, the posteriors, the counts and exact p of each experiment, the arm diffs, the rules with their hashes, the chain heads, the list of what the containers do not mirror and five fixed sentences of honesty, one of which says that `verify` does not re-run experiments.

**The `.driftcase` archive.** `drift pack <case-dir> --out FILE` writes a USTAR tar: names sorted, mode 0644, uid and gid 0, modification time 0, empty owner names, no compression. `arms/` becomes `reproduce/`, `confirmed/minimal-set.json` is added for the two CONFIRMED verdicts, and `report.html` and a rebuilt `manifest.json` go in. The reader returns a typed problem for a truncated archive, a bad checksum or magic, an unsafe path (`..`, absolute, backslash, control or non-ASCII byte), a duplicate name, an entry over 16 MiB, a total over 64 MiB, more than 4096 entries, a non-regular file and text that is not UTF-8. After parsing, the reader writes the entries back and requires the same bytes, so it accepts only what the writer produces. `pack` refuses a case with no certificate or one that fails its own integrity check, and `verify` and `report` refuse a case directory and say to pack it first. System `tar` reads the result:

```sh
tar -tvf fixtures/lab/cases/locale-tz-date-2.driftcase | head -4
```

```
-rw-r--r--  0 0      0         212 Dec 31  1969 case.json
-rw-r--r--  0 0      0        5895 Dec 31  1969 certificate.json
-rw-r--r--  0 0      0         436 Dec 31  1969 confirmed/minimal-set.json
-rw-r--r--  0 0      0          27 Dec 31  1969 eliminations/excluded.json
```

**The report.** `drift report FILE --out report.html` renders the page that `pack` stored. It is static: the archive's copy has no script, link, image or external resource, only ASCII, no timestamp, escaped text, and eight `<details>` elements, two of them open. For the CONFIRMED fixture it is 7,052 bytes. `verify` regenerates it from the certificate and compares.

**Verify.** `drift verify FILE` runs 14 checks in a fixed order, and a failing check never stops the later ones.

| check | re-derives |
| --- | --- |
| `tar` | the archive is canonical (archives only) |
| `manifest` | every listed file is present with its SHA-256, and no file is unlisted |
| `observation-chain`, `results-chain` | both hash chains, and the certificate's chain heads |
| `capsules` | each capsule file hashes to what its observation records |
| `counts` | each arm's failures, passes and errors equal the tally of its recorded trials and the certificate |
| `fisher` | every exact p, as a fraction, from those counts |
| `rules` | each preregistered spec and rule hashes to the recorded hash, alpha and threshold match the plan, each arm ran the planned trials |
| `decision` | each outcome follows from the counts under the rule |
| `posteriors` | each belief, by Bayes over the recorded outcomes from the prior in the case |
| `ranking`, `plan` | the ranking and the plan, from the two capsules (pair cases only) |
| `verdict` | the verdict follows from the recorded experiments, and its statement quotes the recorded p |
| `report` | `report.html` and `confirmed/minimal-set.json` equal what the certificate generates |

The output is one `PASS`, `FAIL` or `SKIP` line per check, the verdict, the sentence `experiments are not re-run; the recorded results are taken as given`, and `verify: ok` or `verify: failed at <first failure>`. The exit code is 1 when any check fails, and `--json` prints the same as canonical JSON. `SKIP` appears only for `report` on a plain directory, `ranking` and `plan` on history cases, and `posteriors` when an ingested result is present.

```sh
drift verify fixtures/lab/cases/runtime-node-api-1.driftcase | tail -4 | cut -c1-200
```

```
PASS report: report.html and the confirmed record are what the certificate generates
verdict: CONFIRMED EFFECT (bundle): the arms differ in 2 attributes, so the effect is the bundle's and not one member's: env.NODE_VERSION, tool.node.version. Setting tool.node.version moved the failur
experiments are not re-run; the recorded results are taken as given
verify: ok
```

`verify` shows that an archive agrees with itself. It does not show that the experiments happened. Someone who rewrites every file, both chains, the hashes, the certificate and the manifest consistently produces an archive that verifies. Preregistration is bound by hash, not by time. The rules that the `verdict` check applies are the closed loop's own conditions read back from the record, so a verdict that the recorded experiments cannot produce fails, and one they can produce passes.

**Evidence.** Flipping one trial's recorded status in a fixture breaks it at the first check that sees it. The one-line edit replaces the first `"status":"fail"` of the archive, which sits in `results/0001.json`, with `"status":"pass"` (same length, so the tar stays canonical):

```sh
python3 -c "
d = open('fixtures/lab/cases/locale-tz-date-2.driftcase', 'rb').read()
open('bt.driftcase', 'wb').write(d.replace(b'\"status\":\"fail\"', b'\"status\":\"pass\"', 1))"
drift verify bt.driftcase 2>/dev/null | grep '^FAIL\|^verify' | cut -c1-110
```

```
FAIL manifest: results/0001.json was changed; expected 5f6997c647d89b96cd77b038f0ce6679a32ae6f68a4cdeceaae5320
FAIL results-chain: results chain broken at entry 0 (results/0001.json): content does not match its hash; expe
FAIL counts: experiment p0 treatment failures; expected 7, recorded 8
FAIL fisher: experiment p0 exact one-sided p; expected 8/11440 (699), recorded 1/12870 (77)
verify: failed at manifest: results/0001.json was changed; expected 5f6997c647d89b96cd77b038f0ce6679a32ae6f68a
```

The command exits 1, with identical standard output from the JVM and the macOS executable. A second edit, made with a script that is not in the repository, flipped one trial in `results/0002.json` and also resealed the results chain, the certificate's chain head and the manifest. It still failed at `counts` and `fisher` (and at `verdict` and `report`), because the tally of the recorded trials no longer equals the recorded count; again the JVM and native outputs were byte-identical.

The four archives in `fixtures/lab/cases` are real certificates from the dev evaluation below, one per verdict class (CONFIRMED, CONFIRMED EFFECT (bundle), NARROWED and STUCK). For each, the archive that `pack` writes from the extracted directory is byte-identical to the committed file, `report` writes the same `report.html`, and `verify` prints the same output with exit 0, on the JVM and on the macOS release executable.

| case | verdict | bytes | archive sha256 (first 16) | `report.html` sha256 (first 16) |
| --- | --- | --- | --- | --- |
| `locale-tz-date-2` | CONFIRMED | 50,176 | `8d4aaf9f238896a8` | `29dfc13b8f4884e3` |
| `runtime-node-api-1` | CONFIRMED EFFECT (bundle) | 58,368 | `467bc38c27507118` | `12ed7c1a194b82fa` |
| `env-retries-flaky-plain-1` | NARROWED | 43,008 | `b32e0ccfb7a677f3` | `16668d3caa233db3` |
| `limits-nofile-1` | STUCK | 31,744 | `6563ac778d368520` | `56eb1a79bd35ab2b` |

`tar -xf` extracts the 19 entries of `runtime-node-api-1`, and the SHA-256 of each of the 18 files that `manifest.json` lists equals the manifest value. The 45 case directories of the dev evaluation all pack and verify with the macOS release executable (45 of 45, exit 0).

The repeated runs described under The Lab on the Dev Split give 180 more archives. They were packed on Linux x64 and each verified with the Linux x64, Linux arm64 (in an arm64 container) and macOS arm64 executables: 180 of 180 `verify: ok` on each. The 45 original cases packed by the Linux arm64 executable are byte-identical to the ones the Linux x64 executable packed (45 of 45).

The four fixtures hold no `reproduce/` directory: the `arms/` files of those four were not copied from the Linux host when the fixtures were made. The 45 case directories of the evaluation do have them: packed, `locale-tz-date-2` is a 43-entry archive with 24 entries under `reproduce/`. The core tests cover the `arms/` to `reproduce/` move with a fake executor.

---

## CI Commands, Contracts and the Action

`drift run [--tools] [--project] -- COMMAND` runs a command, keeps the environment around it as a capsule, writes the exit code and the tail of the log into a capsule directory, and passes the exit code through. `drift import` builds the same directory offline from a saved GitHub Actions log (and the JSON of `gh run view`) or a GitLab trace with its pipeline and job JSON. `drift diagnose --against BASELINE` compares a saved passing run with the current one, and `drift bisect plan` and `drift bisect apply` narrow a green and red pair to the attributes that matter, one test at a time. None of them opens a network connection. `drift contract create` writes what a program needs from an environment: the values that every known passing capsule shares, reduced by delta debugging against the failing capsules to a smallest set that separates them (on the drangler runs, 1 assertion separates 5 green capsules from 3 red ones), and `drift contract verify` checks another capsule against it and exits 1 on a break. The contract file is canonical JSON, because common code does not parse YAML. `ContractTest` (20 tests) and `ContractCommandTest` (14) run on the JVM, macOS and Wasm, and the golden contract hashes the same on all three and on the release executable.

Run-identity variables (`GITHUB_RUN_ID`, `GITHUB_SHA`, `CI_JOB_ID` and the like, listed in `CiVolatile`) differ on every run of a job. They stay in the capsule, and the diff, the history evidence and the matcher skip them. `Contract.constants` did not, and a contract made from greens of one commit named `env.GITHUB_SHA` as the value that separates them from a red run of another commit; it now skips them, with `ContractTest.aRunIdentityVariableNeverSeparatesARedFromTheGreens`, which failed before the change. The same check was made for the Lab's arm diff (`Arms.kt`): it reads only the manifest entries that change with a base image (operating system release, libc, coreutils), which are never run-identity variables, so it needs no filter.

The GitHub Action (`action.yml`) and the GitLab component (`templates/drift.yml`) run these steps in a pipeline with the modes `run`, `diagnose`, `capture` and `triage`. The baseline comes from a run of the default branch pushed by a trusted event, and an artifact uploaded by a fork is refused (the fork case has a test). The `triage` mode comments on a new issue, patches its own comment when the issue is edited, treats a hostile body as data, and has an optional Slack webhook step whose URL comes from a secret. The shell tests (`.github/action/test-drift.sh`, `test-triage.sh`, `test-gitlab.sh`) pass with stubs for `gh` and the artifact API, and `actionlint` and `shellcheck` are clean. A real GitHub run, the Windows branch of the Action's script and a run on gitlab.com have not happened.

---

## Upgrade Check

`drift upgrade-check [CAPSULE] --to php=8.3,drupal=11` asks what a move to other versions breaks, from one capsule and no second environment. It copies the capsule, sets the target keys on the copy, and evaluates every rule against that synthetic pair with the same engine that `diagnose` uses. A target name is one of 19 aliases for an attribute path (`agp`, `composer`, `docker`, `drupal`, `git`, `go`, `gradle`, `java`, `jdk`, `kotlin`, `node`, `npm`, `openssl`, `php`, `python`, `ruby`, `symfony`, `twig`, `tzdata`), or a dotted path. Without a capsule the command captures this machine with `--tools --project`.

The result has four groups, taken from the three-valued verdicts of the matcher and not from a new score. **Will break** is a rule that matches on a fact beyond the target version keys, such as a package in the lockfile or a call in the source; the finding prints that fact on an `evidence:` line. **Version boundary crossed** is a rule that matches on the target versions alone: a documented change lies between the two versions, and nothing in the capsule shows that the project meets it. **May break** is a rule whose verdict is unknown because another key it reads is missing from the capsule or is one no scanner emits yet, and the output names the key (and says when the rule is `pending-scanner`). **Unknown** is a target whose current value the capsule lacks. A rule is listed only when its verdict for the target differs from its verdict for the capsule alone. The order inside a group is severity, verified symptoms before inferred ones, then rule id, and the JSON form is canonical, schema 2.

On the real capture `fixtures/upgrade-check/drupal-10-php-8-1.json` (the Composer fixture inside `php:8.1-cli-bookworm`: PHP 8.1.34, Drupal 10.3.6, Symfony 6.4.12), `--to php=8.3,drupal=11,symfony=7.1` gives 0 will break, 7 may break, 2 version boundary and 0 unknown, and 73 of the 298 rules read a target key. The output is byte-identical on the JVM and macOS builds, and `UpgradeCheckTest` (19 tests) and `UpgradeCheckCommandTest` (9) run on all three Gradle targets.

**What it got wrong.** The first version put every version-only match under Will break. Case D (below) ran it before a PHP 7.4 to 8.3 move: 11 will-break findings and 1 may-break, and none of them was a failure in the project's own test suite (0 true positives, 12 false positives). The suite passed on PHP 8.3. What did break the move was not predicted: a locked package with a PHP upper bound stopped the install, and a locked package that lacks a PHP 8.1 tentative return type died with a fatal error. No rule covers either. The group split and the evidence requirement are the response, and the same capture now gives 0 will break, 1 may break, 11 version boundary and 0 unknown. That is a change of wording, not a better prediction: the 11 are still not failures, and the frozen prediction in `fixtures/cases/d` is unchanged.

**A second check.** On the four migration scenarios of DriftBench, moving the green arm to the red arm's version put a will-break finding on the injected version key in 3 of 4 scenarios, and in 2 of 4 in the natural direction (the red arm to the new version). Both counts are weak: any finding on the version key counts, and at that time none named the injected API (0 of 4). Added-API rules (36, one per call such as `json_validate`, `Array.fromAsync` or `List.getFirst`) then raised the API-level hits to 4 of 4, but only in the direction where side b has the older runtime, which is the CI-older-than-local shape; an upgrade runs the other way, and those rules do not fire for it.

**Limits.** A quiet result does not show that a move is safe: the catalog holds documented changes, 91 of its 298 rules wait for a key no scanner emits, and a project that uses a removed API without a rule for it is invisible. The direction of a rule is the direction of its text (`a` is the passing side), and a rule written for a downgrade can match an upgrade only by accident. `--learned DIR` adds the rules published by `drift triage` (see Triage and the Corpus).

---

## Triage and the Corpus

`drift triage ISSUE_FILE` reads the text of an issue offline, with no model, and says what it can about the reporter's environment: facts as Drift attribute keys, each with a confidence, the rules that apply, statements that contradict what Drift knows, and a draft rule when the report suggests one. The same text gives the same bytes on the JVM, Native and Wasm builds, since the arithmetic is integer and the scanning has no regular expressions. A fact has a side (`passing` or `failing`) taken from the verdict word nearest the value, a path that says how it was found, and a confidence. The paths start at `section` 0.950 (the first line under an issue-form heading), `tool-output` 0.900, `pattern` 0.700 (0.800 under an environment heading) and `key-value` 0.490, and the agreement of two spans raises a value while a conflict or a hedge lowers it. Rules that match are listed with the facts they read. A draft rule needs a symptom signature, one version key stated on both sides with the passing version below the failing one, and no rule that already holds on that key. It is published, not only drafted, when `--publish-threshold` is set, its confidence reaches it, `--date` names the day the report was read, and the rule passes the rule compiler and its own fixtures. Published drafts go into a learned-rule store (`--learned DIR`, validated by the rule compiler on every read, never a shipped id) that `diagnose`, `upgrade-check` and `triage` read. Secrets are masked first with the capture redactor, and an `@login` mention becomes a hash.

**The corpus.** `triage/corpus.yml` lists 43 repositories on GitHub and GitLab, 15 large and 28 small (50 to 1,000 stars), each with its issue-template policy, and pins the corpus file by SHA-256 and size. `./gradlew :tools:buildCorpus` pages each repository newest first through `gh` or `glab` (at most 25,000 issues per repository, none newer than the manifest cutoff), redacts titles and bodies, replaces every login with `u` and twelve hex digits of an HMAC-SHA256, sorts by repository and number, and writes one JSON file and its `zstd -19` copy. The build gave 222,707 issues, 595,230,604 bytes as JSON and 78,765,569 as `zstd`, SHA-256 `987d2c9b...5687`. The file is not in the repository: the manifest points at a release asset that does not exist until it is uploaded, and `./gradlew :tools:downloadCorpus` refuses a file that does not match the pinned hash. The build ran on the Mac with the logged-in `gh` and `glab`; no GitHub credential was copied to another machine.

**Scoring.** The truth comes from issue forms: a body that starts with `### Label` sections states its environment under labelled headings, those facts are the truth, the sections that gave them are cut out, and the extractor runs again on what is left. Only failing-side facts on keys the form labelled are scored, and `8.1` agrees with `8.1.0`. Issues split into a dev half and a held-out half by a seeded hash; the path confidences were set on the dev half and are reported on the held-out half. `./gradlew :tools:evalTriage` is not part of `build`. It was run on the final tree for this report (6 minutes, 3 GB heap):

| held-out half | facts | precision | Brier | note |
| --- | --- | --- | --- | --- |
| all | 1,376 | 0.783 | 0.1486 | mean confidence 0.726, recall 0.154 |
| `pattern` path | 1,323 | 0.782 | 0.1488 | |
| `tool-output` path | 36 | 0.889 | 0.1137 | |
| `key-value` path | 9 | 0.444 | 0.2350 | too few to read |
| `section` path | 8 | 0.750 | 0.1753 | |
| forms with blank issues allowed | 512 | 0.854 | 0.1301 | |
| forms only | 864 | 0.741 | 0.1595 | |
| large repositories | 1,367 | 0.786 | 0.1480 | |
| small repositories | 9 | 0.333 | 0.2374 | too few to read |

Recall is low by construction (15%): it counts form values found again outside their own sections. Reliability on the held-out half, in bins of confidence: 0.4 holds 165 facts at accuracy 0.412, 0.5 holds 177 at 0.661, 0.6 holds 47 at 0.936, 0.7 holds 528 at 0.799, 0.8 holds 51 at 0.941 and 0.9 holds 404 at 0.933, so the 0.5 and 0.6 bins are underconfident and the 0.9 bin slightly overconfident. A fact was found in 75% of the issues of large form-only repositories, 63% of large form repositories with blank issues allowed, 60% and 40% of large Markdown-template repositories (strict, blank), 36% and 49% of small form repositories and 21% of small repositories with no template. Small repositories are worse on every measure that has a number, and 9 scored small facts support no precision figure. The scored sample is large trackers, mostly PHP, Node, .NET and Python.

Earlier in the work the `key-value` path read `PHP Fatal error: ... on line 23` as PHP version 23 (123 of its 142 held-out misses) and the claim check fired on any sentence that mentioned a library without a negation. The first is fixed: a version must sit next to the tool name or its label. Before that fix the held-out precision was 0.731 and the Brier score 0.1645 over 1,500 facts; after it, the table above. The fix changed the fact set, so the two figures are not a paired comparison.

**Fixtures.** `fixtures/triage` holds 70 invented issue bodies with expectation files (strict forms, forms with blank issues, free text, sparse or wrong beginner reports, mixed phrasing, 18 for false positives and claim wording). `TriageTest` scores the extractor on them: 189 facts, 187 true positives, 2 false positives, 4 false negatives, precision 0.989, recall 0.979, Brier 0.0851, and the test fails below 0.95, 0.93 or above 0.12. These bodies were written to exercise the extractor, so the scores are an upper bound for real issues. `drift triage calibrate --corpus FILE --out TABLE` repeats the dev-half recalibration on a corpus of the user's own issues (it reads the whole file into memory, and a `.json.zst` through the user's `zstd`), and `--confidence TABLE` applies it. On the real corpus (222,707 issues, 595 MB of JSON) the macOS executable took 7.5 minutes at a peak resident size of 6.09 GB; keeping only the form bodies instead of every parsed issue brought it to 6.0 minutes and 3.20 GB with an identical table (`pattern` 700 to 760, held-out Brier 0.1450 to 0.1408 over 1,434 facts). The remaining memory is the decompressed text itself.

**The claim check is weak.** It flags a sentence that contradicts something Drift knows: nine statements, each tied to a rule or to two Atlas columns that print different results (for example, that the Nashorn engine is available in JDK 21, which the rules say JEP 372 removed). It needs the facts and a sentence that affirms the claim inside one clause; questions and "removed in" sentences are rejected. A labelled set of 67 maintainer-correction threads from 13 repositories (`triage/claim-labels.yml`: 6 corrections, 55 not corrections, 6 unclear) was built to measure it, and the search heuristic that found the candidates has a precision of 6 of 61 (0.098; Wilson 0.05 to 0.20). The engine flags none of the 6 corrections. The 67 threads were split by `sha256("<repo>#<issue>")` (first four bytes, odd is held-out) before the second fix: 30 held-out (3 corrections, 23 not corrections, 4 unclear) and 37 dev. The engine flags 0 of the 30 held-out threads, so its precision there is undefined and its recall is 0 of 3 (Wilson 0 to 0.56). On the whole corpus the fix pass (assertion shape in the first pass; hedge, deprecation and imperative cues in the second) took it from 12 hits to 6 to 1 in 222,707 bodies; the one left is `distutils` in a parenthetical in a CPython issue and is not a claim. By the rule fixed for this check (held-out precision below 0.5 removes the feature from the headline claims), the claim check is documented as experimental in `README.md` and `ADVANCED_USAGE.md`. The claim list covers nine Kotlin, JDK and Python statements, none of which the 13 repositories of the labelled set discuss, so a larger set would need claims written for it. The claim-check set also holds too few corrections (6) for a recall figure.

---

## Studio Screens

Studio is one Compose Multiplatform codebase for the desktop, the browser and the mobile builds. Its Home tab offers `Choose a Case File`, `Show a Sample`, `Open the Atlas`, and one entry each for the Upgrade, Contract and Bisect screens, each with a one-line description. A `.driftcase` opens from the file picker (desktop and web), from the launch argument, from a macOS open-file event, and from the operating system through a file association for `application/x-driftcase`; the Case tab shows the sentence summary and the verdict, and for a tampered archive it shows the check that failed. `drift open` starts Studio for a file, or writes `report.html` beside it when Studio is not installed. The Atlas and Investigation tabs described below remain. The Upgrade screen takes a capsule and `name=version` pairs and shows the groups of Upgrade Check as one sentence per group count, then each finding with its rule, boundary and source; the Contract screen takes a contract and a capsule and lists requirements that break, could not be checked and hold; the Bisect screen takes a passing and a failing capsule and shows the settings that differ, the first test in words and the `drift bisect` commands to run, and runs nothing itself. Summaries of a certificate and a ranking use fixed wording per verdict and tier (tested on all four archives in `fixtures/lab/cases`), technical output sits behind `Show Technical Details`, a term is glossed once where it first appears, and controls are at least 44 dp. The palette is a muted purple (accent text `#6b3fd4` on light and `#b99cff` on dark, flat, no gradients) shared with the documentation site template, defined once in `StudioTheme.kt`; `StudioThemeTest` asserts contrast of at least 4.59 to 1 for text and 3.41 to 1 for marks. The screens were checked at 390 px wide in the web build (`scrollWidth` equals `innerWidth` on the Upgrade and Bisect screens). Studio has 114 JVM tests and 69 browser tests. The Wasm build bundles JetBrains Mono (OFL) so that the monospace panels render the same everywhere. The wording rules follow brand-voice conventions; no measured effect on comprehension is claimed.

---

## The Kotlin Portability Atlas

The Atlas answers one question per probe: does this Kotlin program print the same thing on every target? It is a set of 38 probes in the `kotlin` family, each a short function with a stated question, its source text, a classification and notes. The areas are `char`, `collections`, `double`, `exceptions`, `float`, `int`, `long`, `math`, `numbers`, `random`, `regex`, `string` and `time`.

**Commands.**

| command | does |
| --- | --- |
| `atlas record --out DIR [--target LABEL]` | runs the probes on this target and writes `DIR/<target>.txt` |
| `atlas build FILE... --out FILE` | aggregates transcripts into a canonical JSON dataset |
| `atlas compare DATASET TRANSCRIPT [--detail ...]` | shows where a device matches each recorded column |
| `atlas show DATASET [--probe ID]` | prints the matrix, or a repro draft for one probe |

A transcript starts with `# kotlin.version = ...` and `# kotlin.target = ...`, then one `@@ <probe id>` section per probe. A cell of the dataset is a (target, Kotlin version) pair, and divergence is judged inside one version only, so a version column never merges into another. `build` refuses a transcript that lacks a probe of the catalog, names an unknown probe, or duplicates a column.

`atlas show --probe` prints a draft that begins `GENERATED DRAFT, NOT REVIEWED`, says a person must read it, rerun the source and rewrite it, and that Drift does not file, post or contact anyone. It then lists the classification and its basis, the quoted references, the linked issues with the state read on the research date, what was searched, the path of the repro draft, the columns measured, the source, the differing lines per version group and a checklist.

**Classification.** The classification lives in `atlas/classification.yml`, one hand-maintained entry per probe, and the build embeds it as JSON so the dataset, `atlas show` and Studio read one source. A probe is `documented` (pages that the author fetched and read in full state the behavior, for every differing line), `platform-defined` (a platform specification delegates it: a JDK Javadoc page, an ECMAScript section or the Unicode version a Character page names), or `unclassified` (no explanation found; this does not call the behavior a defect). Each entry records its references with a short quote, the KT issue ids with the state read on the research date, the cause classes, and for an `unclassified` probe what was searched. Of 38 probes, 10 are documented, 3 platform-defined and 25 unclassified. Among the 32 divergent probes on the 16 columns of 2.4.20, 4 of 10 documented, 3 of 3 platform-defined and 25 of 25 unclassified diverge. Thirteen entries were downgraded to `unclassified` (twelve from `documented`, one from `platform-defined`) when the page read did not cover every differing line.

The method has limits. The tracker was read through YouTrack's REST API, read only: the entries list their queries, and where an entry says so only the first 8 results of each query were read, so a relevant issue ranked lower was not seen. Anything not named in an entry's references was not read. Quoted passages are short and not reproduced. The classification says what the sources named explain and does not say why a target differs. `drift atlas show --probe` prints these fields and a draft for human review that begins `GENERATED DRAFT, NOT REVIEWED`; the draft files under `atlas/repros/` (one per divergent unclassified probe, 25) are never sent anywhere.

**Results, Kotlin 2.4.20** (16 columns: JVM on JDK 21 on a Mac and on JDK 17, 21 and 25 on x86-64 Linux, Native on macOS arm64, Linux x64 and Linux arm64, Wasm on Node and in three browsers, Kotlin/JS IR on Node, Android, iOS, and two Cloudflare Workers columns, Wasm in workerd and a JVM build through TeaVM):

| quantity | value |
| --- | --- |
| probes identical on all 16 columns | 6 |
| probes that differ on at least one | 32 |
| probe output lines per column | 734 (703 on `js-ir`, where 1 probe does not compile; 641 on `cloudflare-jvm`, where 4 probes are unavailable) |
| lines that differ between JVM, macOS and Wasm on Node | 107 |
| lines that differ between any of the 16 columns | 340 |
| dataset, 16 columns, canonical JSON | sha256 `03146e65...4aef` |
| dataset, 21 columns (adds the 5 at 2.5.0-Beta1), schema 2 | 658,855 bytes on disk, sha256 `2d7e84b9...d3af` of the body |

Adding the ten columns beyond JVM 21, macOS and Wasm on Node made no identical probe divergent: the divergent count stayed at 23. The two Cloudflare columns then made six more divergent (`char.case-special`, `int.division-modulo`, `int.overflow`, `numbers.type-checks`, `regex.anchors-lines`, `regex.groups-replace`), all on `cloudflare-jvm`, which runs TeaVM's class library; `cloudflare-wasm` is byte-identical to the `wasm` column apart from the target line. The `js-ir` column (the standalone Kotlin/JS IR compiler, run on Node, 37 of 38 probes) made three more divergent, `collections.equality-hashing`, `double.equality-order` and `string.hashcode`, which gives 32. It is the column furthest from the others: 11 of 38 probes agree with macOS. For example, `list-add` inside a loop over the same list throws `ConcurrentModificationException` on every other column and returns `[1, 2, 3, 4]` on `js-ir`. The two Cloudflare Workers columns were also deployed once on a free account with `drift probe cloudflare --deploy`: the deployed output matched the local `wrangler dev` columns except for the sign bit of NaN results (`0/0-raw` printed `fff8000000000000` deployed against `7ff8000000000000` locally on both Workers, and `sqrt-neg-raw` the same on the JVM Worker), which is the x86-64 default NaN that the CPU-dependent probe below describes; the deployed CPU was not checked, so that cause is inferred. Both Workers were deleted afterwards and the account listing matched its baseline.

A fresh `drift atlas record` on the macOS release executable wrote a transcript byte-identical to the committed `fixtures/atlas/macos.txt`. Compared against the 21-column dataset, this Mac matches its own column on 38 of 38 probes, the iOS column on 38, the Linux arm64 column on 37, Linux x64 on 36, JDK 21 on 21, the Wasm column on 25 and the `js-ir` column on 11.

The divergences are specific and small. Six lines of a 14-line probe, with the `js-ir` column the one that differs most:

```sh
drift atlas show atlas.json --probe kotlin.collections.iterator-modification | sed -n '/^kotlin 2.4.20/,/^controls/p'
```

```
kotlin 2.4.20: 4 different results
differing lines: 6 of 14
  line 1
    android, cloudflare-jvm: list-add = err:ConcurrentModificationException
    cloudflare-wasm, ios, linux, linux-arm64, macos, wasm, wasm-chromium, wasm-firefox, wasm-webkit: list-add = err:ConcurrentModificationException
    js-ir: list-add = ok:[1, 2, 3, 4]
    jvm, jvm-17, jvm-21, jvm-25: list-add = err:ConcurrentModificationException
  line 2
    android, cloudflare-jvm: list-remove-first = err:ConcurrentModificationException
    cloudflare-wasm, ios, linux, linux-arm64, macos, wasm, wasm-chromium, wasm-firefox, wasm-webkit: list-remove-first = err:ConcurrentModificationException
    js-ir: list-remove-first = ok:[2, 3]
    jvm, jvm-17, jvm-21, jvm-25: list-remove-first = err:ConcurrentModificationException
  line 3
    android, cloudflare-jvm: list-remove-second = err:ConcurrentModificationException
    cloudflare-wasm, ios, linux, linux-arm64, macos, wasm, wasm-chromium, wasm-firefox, wasm-webkit: list-remove-second = ok:[1, 3]
    js-ir: list-remove-second = ok:[1, 3]
    jvm, jvm-17, jvm-21, jvm-25: list-remove-second = ok:[1, 3]
  line 4
    android, cloudflare-jvm: list-remove-last = ok:[1, 2]
    cloudflare-wasm, ios, linux, linux-arm64, macos, wasm, wasm-chromium, wasm-firefox, wasm-webkit: list-remove-last = ok:[1, 2]
    js-ir: list-remove-last = ok:[1, 2]
    jvm, jvm-17, jvm-21, jvm-25: list-remove-last = err:ConcurrentModificationException
  line 8
    android, cloudflare-jvm: sublist-after-add = err:ConcurrentModificationException
    cloudflare-wasm, ios, linux, linux-arm64, macos, wasm, wasm-chromium, wasm-firefox, wasm-webkit: sublist-after-add = err:ConcurrentModificationException
    js-ir: sublist-after-add = ok:[1, 2]
    jvm, jvm-17, jvm-21, jvm-25: sublist-after-add = err:ConcurrentModificationException
  line 9
    android, cloudflare-jvm: sublist-size-after-add = err:ConcurrentModificationException
    cloudflare-wasm, ios, linux, linux-arm64, macos, wasm, wasm-chromium, wasm-firefox, wasm-webkit: sublist-size-after-add = err:ConcurrentModificationException
    js-ir: sublist-size-after-add = ok:2
    jvm, jvm-17, jvm-21, jvm-25: sublist-size-after-add = err:ConcurrentModificationException
controls: 8 lines are the same on every target
```

Others, each in `fixtures/atlas/*.txt`:

- `Double.toString` on Wasm (2.4.20) prints `1e7` as `10000000.0`, `1e21` as `1e+21` and `9.999e-4` as `0.0009999`; JVM and Native print `1.0E7`, `1.0E21` and `9.999E-4`. Seven of 11 lines of `kotlin.double.tostring-boundaries` differ. KT-88414 is the matching tracker issue.
- `1.0.pow(Double.NaN)` is `1.0` on Native (macOS, Linux and iOS) and on Android, and `NaN` on the JVM and Wasm (KT-89072, open, reports the Native result).
- `Double.roundToInt()` and `roundToLong()` give `1` for 0.49999999999999994 on Native and Wasm and `0` on the JVM and Android (KT-89074, open).
- `String.compareTo` ignoring case returns `108` for the sharp s case on the JVM and Android and `1` on Native and Wasm. Only the sign is portable.
- A `HashSet` of the same integers iterates in a different order on the JVM and Android than on Native and Wasm.

**Kotlin 2.5.0-Beta1.** The same 38 probes were recorded on five targets (JVM, macOS arm64 Native, Wasm on Node, an Android emulator and an iOS simulator) with the compiler pinned to 2.5.0-Beta1 in a copy of the repository, once each. Divergent probes fall from 23 (the same five targets at 2.4.20) to 18. Five probes became identical on all five targets: `double.extremes`, `double.parse`, `double.tostring-boundaries`, `float.tostring` and `numbers.float-conversions`, all by the Wasm column moving to match the others (and, for the last, the macOS and iOS columns too). Five more changed without becoming identical, all in the Wasm column: `collections.tostring-forms`, `double.shortest-digits` (three groups to two; Android still differs from the rest), `exceptions.messages`, `math.pow-special-cases` and `math.rounding`. The probe notes cite KT-88414 for the Double and Float formatting; the tracker lists it as Fixed, resolved 2026-08-30. The data shows what changed. It does not show why, and the compiler changes behind it were not read. The `kotlin.version` header of a Beta transcript keeps the qualifier, because the scan module embeds the version from the build's version catalog (`embedKotlinVersion`).

Against the 21-column dataset, this Mac matches `macos@2.4.20` on 38 probes and `macos@2.5.0-Beta1` on 37.

**Version ladder.** To see how the same probes behave on older compilers, `tools/ladder` compiles each probe's displayed source with a standalone compiler per version and per target. Probe files that do not compile are dropped and recorded as `UNAVAILABLE` with the first compiler error. The transcripts are in `fixtures/atlas-ladder/<version>/`. The cells below are the number of the 38 probes that ran, with `-` where no run exists.

| version | jvm | macos | linux | js-legacy | js-ir | wasm |
| --- | --- | --- | --- | --- | --- | --- |
| 1.3.72 | 17 | - | 15 | 16 | - | - |
| 1.4.32 | 26 | - | 23 | 25 | 25 | - |
| 1.5.32 | 31 | - | 29 | 30 | 30 | - |
| 1.6.21 | 33 | 31 | 31 | 32 | 32 | - |
| 1.7.22 | 35 | 34 | 34 | 35 | 35 | - |
| 1.8.22 | 37 | 35 | 35 | 36 | 36 | - |
| 1.9.25 | 38 | 37 | 37 | - | 37 | - |
| 2.0.21 | 38 | 37 | 37 | - | 37 | - |
| 2.1.21 | 38 | 38 | 38 | - | 37 | - |
| 2.2.21 | 38 | 38 | 38 | - | 37 | - |
| 2.3.21 | 38 | 38 | 38 | - | 37 | - |
| 2.4.0 | 38 | 38 | - | - | 37 | - |
| 2.4.20 | 38 | 38 | 38 | - | 37 | 38 |
| 2.4.21 | 38 | 38 | - | - | 37 | - |
| 2.5.0-Beta1 | 38 | 38 | - | - | 37 | 38 |

The 2.5.0-Beta1 row also has `android` and `ios` columns (38 probes each), left out of the table, and 2.4.20 has the 16 columns described above. `atlas build` over the 16 columns of `fixtures/atlas`, the 5 of `fixtures/atlas/2.5.0-beta1` and the ladder transcripts gives 74 columns, 2,812 cells, 34 divergent probes and 4 identical (sha256 `f238d8e4...f2f6`). The ladder's own `2.4.20/linux.txt` and `2.4.20/js-ir.txt` are left out of that build because `fixtures/atlas/linux.txt` and `fixtures/atlas/js-ir.txt` are the same columns (Linux Native and Kotlin/JS IR at 2.4.20); with the divergent probes of each version, 2.4.20 has 16 columns and 32 divergent probes and 2.5.0-Beta1 has 6 columns and 30. A probe counts as divergent when its output or its availability differs between columns of one version, so these counts include APIs that do not exist yet in old versions. Value changes, as opposed to appearing APIs, are fewer. On the JVM, no probe changes value anywhere from 1.3.72 to 2.4.21, apart from one class name printed by `kotlin.exceptions.tostring-cause`, which differs because the ladder compiles the displayed source and the committed 2.4.20 column ran the real code. Changes on the other targets:

- Native `String.hashCode` aligned with the JVM between 1.4.32 and 1.5.32 on Linux: `"hello"` hashes to 604317365 in 1.4.32 and 99162322 from 1.5.32. KT-44746 describes the mismatch (state Fixed, resolved 2021-02-09). The first compiler in the ladder that shows the aligned value is 1.5.32; the issue text does not name a fix version.
- `Boolean.hashCode()` of `true` changes from 1 to 1231 at 1.9.25 on Linux, macOS and JS IR. KT-61028 lists the change for Native, JS and Wasm.
- JS IR printed `Long` values through a double until 1.5.32 and prints them exactly from 1.6.21: `Long.MIN_VALUE / -1` prints `-9223372036854776000` in 1.5.32 and `-9223372036854775808` in 1.6.21. No tracker issue was read for this one.
- JS IR `Long / 0` threw `Exception` with the message `division by zero` on every JS IR compiler from 1.5.32 to 2.4.21 (`long-div-zero` of `kotlin.exceptions.messages`) and throws `ArithmeticException` with `/ by zero` on 2.5.0-Beta1, the text the JVM prints. The standalone 2.5.0-Beta1 JS IR build is the only one that changes on that column from 2.4.21; no tracker issue was read for it.

**Version diff.** `drift atlas diff DATASET --from X --to Y [--target T]` compares two versions of one dataset on the targets recorded for both and lists each probe whose transcript differs, with the differing lines; a probe that stopped compiling or started to is reported as unavailable or available instead of as a value change. `./gradlew atlasLadderDataset` builds the 74-column dataset, and `atlasSite` writes it to `data/ladder.json` and writes `versions.html`, the change between each pair of consecutive versions per target. The diff from 2.4.20 to 2.5.0-Beta1 reproduces the Beta findings above: 10 of 38 probes change in all, 10 on `wasm`, 1 on `macos` (`kotlin.numbers.float-conversions`, `ulong-max-double`), 1 on `ios`, 1 on `js-ir` (`kotlin.exceptions.messages`, the `Long` division by zero message) and none on `jvm` or `android`. The tests read the fixtures a second way (`AtlasTranscript.parse` per probe) and compare that with the command's output.

What did not run, and why:

- Native 1.3.72 and 1.4.32 on Linux need `libtinfo.so.5`, and 1.4.32 fails in `konanc` on JDK 21; a Temurin 11 image with `libtinfo5` ran all three Linux builds up to 1.5.32. macOS Native 1.5.32 does not run on an arm64 JDK 21 or under Rosetta; 1.3.72 and 1.4.32 have only an x86_64 macOS bundle.
- On Native up to 1.8.22, `kotlin.collections.iterator-modification` ends the process with a segmentation fault (exit 139), and on Linux 1.3.72 and 1.4.32 `kotlin.int.overflow` exits with SIGFPE (136). Both are recorded as `UNAVAILABLE` with the exit code, and every other probe ran in a per-probe rerun. The cause of either was not isolated.
- Native 1.9.25 and 2.0.21 throw `ArrayIndexOutOfBoundsException` out of the `kotlin.double.parse` body; 2.1.21 fixes it. The triggering input was not isolated.
- JS legacy stops at 1.8.22 (1.9.25 refuses the legacy compiler). JS IR from 2.1 loses one probe, `kotlin.collections.tostring-forms`, which needs a reflection API Kotlin/JS does not support.
- Wasm has no standalone compiler route (`kotlinc-js -Xwasm` on 2.0.21 fails with `jsFrontEndResult has not been initialized`), so Wasm columns exist for 2.4.20 and 2.5.0-Beta1 only, both from Gradle builds.

**Studio.** The Atlas is the first tab of Drift Studio. It shows the matrix of 16 columns at 2.4.20 and 5 at 2.5.0-Beta1 with a version selector, the columns grouped by family (JVM, Native, Wasm, JS, Cloudflare, Mobile, Device) under one horizontal scroll, a divergent-only filter, three detail levels, a column for the device the app runs on, and a predict-then-reveal mode that shows the question and the source, asks which output a target gives, and keeps the score in memory. A tap on a row shows the source, each target's full output, the basis and quoted references, the issues with the state read, what was searched, and the notes. A probe with no explanation reads "unclassified: no explanation found", and the footer states the limits of the data. The app has an Investigation tab that runs `StudioState.of` over the drangler capsules (funnel, candidates, drill-in, three detail levels). Studio builds for JVM Desktop and for the browser as Wasm; the Android and iOS builds replay the embedded transcripts and add the live probes of the device. The JVM tests (114) and the browser tests (69, in Chrome through `wasmJsBrowserTest`) pass; the Studio screens beyond the Atlas are described in Studio Screens above.

**The site.** `./gradlew atlasSite` writes a static site to `build/atlas-site`: the Studio web build at the root, `data/dataset.json` (658,855 bytes), `data/classification.json`, `data/ladder.json` (1,961,042 bytes), the 17 transcript files, `404.html`, `versions.html` and one page per probe under `probe/` (38 pages and an index). It has 79 files and 18,548 KiB, of which the two Wasm files are about 12 MB. The probe pages are static HTML with no script and no external resource, and the text is escaped. A unit test builds the site twice from two temporary directories and compares the bytes; a second full build and a hash of every file was done on 2026-10-07 (78 files then) and not repeated. Every page under `probe/` links to `../../` and the 404 page to `./../`, the directory above the site root, which is the front page of the documentation site. `docsSite` places this site under `atlas/` of the documentation site and the Dokka site under `engine/`. The deploy steps ran against a local bare repository before `docsSite` existed (one commit of 2,907 files, then "No changes to deploy"); the steps now copy `build/site`, and that run was not repeated. No GitHub run exists, and the sticky name column and horizontal scroll of the matrix were not looked at in a browser.

**The documentation site.** `./gradlew docsSite` renders `README.md`, `ADVANCED_USAGE.md` and `TECHNICAL_REPORT.md` into `assets/site-template.html` with commonmark-java (tables, heading ids and autolinks) and writes them with the Atlas site and the Dokka site to `build/site`: 3,969 files, 68,944 KiB (the same build gave identical hashes on a second run on 2026-10-07, not repeated). The render fails on a relative link to a path that is not in the repository and on a link to a heading that does not exist; the three documents pass with every anchor resolving. `checkSiteLinks` scanned 95,013 relative `href` and `src` values outside `engine/` and found none unresolved, and 132 unresolved inside `engine/`, the Dokka links to pages it does not write. The front page and the report page were loaded at 390 by 844 with Chrome device emulation: one column, `scrollWidth` equal to `innerWidth`, and no table wider than the viewport outside a scroll container. Chromium through Playwright loaded the front page, both documents, `atlas/`, a probe page and `engine/` from a local server with no console error, no failed request and no HTTP error status, started Studio under `atlas/`, and followed every nav item and back. GitHub Pages was not involved, and no other browser loaded the site.

**What the Atlas does not cover.** There is no Safari column. The `mingw-wine` transcript in `fixtures/atlas-extra` comes from Wine and is not a Windows column, and no Windows JVM column exists. Android is an emulator on API 37 and iOS is a simulator; no device ran. There is no macOS x64 column, and the `js-ir` column comes from the standalone compiler on Node and not from a Gradle build. `.github/workflows/atlas.yml` defines the jobs that would record Linux x64, Linux arm64, macOS, Windows, three JDKs, and the browsers; it has not been run. Every cell is a single run, and the hardware and build host change some cells (see Platform and CPU Findings and The build host).

---

## Platform and CPU Findings

The same Kotlin code printed different bytes depending on the machine around it. Each finding below names the probe, the columns and what was and was not isolated.

**Linux, mingw and mobile runs.** The Linux x64 and Linux arm64 test binaries were cross-linked on the Mac and run natively, x64 in a Debian container on a Linux host and arm64 in an arm64 Debian container on the Mac's Docker; each ran 730 tests with 0 failures (core 492, scan 80, host 8, cli 74, bench 76, the counts of the tree at that run, before the installer, server and Atlas additions). A later x64 run in a Temurin 21 container (`--memory 10g --cpus 8`, 0 failures) passed core 492, scan 94, host 14, cli 220 and bench 76. A Kotlin/Native build on an arm64 Linux host was not run. The linuxArm64 test binaries ran on arm64 Linux in a Debian container on the Mac's Docker (native, no emulation) on a tree some hours older than the final build: `scan` 116 of 116, `cli` 276 of 276, `host` 31 of 34, `core` 556 of 560 and `bench` 73 of 76. Every failing test name failed identically on macOS at the same snapshot, so none was an arm64 divergence, and all five modules pass in the current Gradle runs on three targets; the arm64 binaries were not rerun on the final tree. The mingw test binaries ran under Wine 9.0 on Linux. Wine is not Windows, so these runs show that the binaries start and the logic runs and show nothing about Windows behavior. In the first run 79 of 80 `scan` tests passed: `CaptureTest.selfDiffOnTheRealHostIsEmpty` failed with "no env captured on mingw", because `NativeHost.env()` ran `cmd /c set` through `_popen`, and under Wine `_popen` failed with errno 2 for every command string tried; `getenv("COMSPEC")` and `getenv("PATH")` were both null in the test process, so the C runtime could not find a shell. Real Windows always sets both, so this cause is inferred not to apply there, and that inference was not run. The fix reads the Win32 environment block with `GetEnvironmentStringsW` (`EnvBlock.kt` and the mingw `SystemHost`, six parser tests and one real-host test). After it, `scan` passed 80 of 80 and `host` 15 of 15 under Wine, and `capture` under Wine recorded 21 `env.*` attributes and `cpu.count`. Under Wine every other `run()` call still returns null, so facts that come from commands stay absent. A later cli run under Wine passed 205 of 206 tests; the failure was `NativeInstallTest.filesAndDirectoriesBehaveLikeTheFakeDoes`. The mingw test binary of the current tree passes under Wine 9.0 in six runs, 263 of 263 tests each (`NativeInstallTest` 4 of 4; one Unicode file-name test is skipped under a POSIX locale and runs under `LC_ALL=C.UTF-8`), so that failure did not reproduce and its cause stays unknown: the binary that failed was not kept. Wine aborts with `free(): invalid pointer` when `TMPDIR`, `TEMP` or `TMP` is set to `/tmp`, which was not investigated. On Android, the three `host` device tests passed on the emulator; on iOS, `host`, `core` and `scan` simulator tests passed at the counts of that run (11, 484 and 79). No device ran either.

**Browsers.** `tools/browsers/run.mjs` loads the Studio web build in Playwright 1.63.0 and writes the this-device transcript the page produces. On one x86-64 Linux host:

| engine | version | first contentful paint, 4 runs | this-device probes, 4 runs |
| --- | --- | --- | --- |
| Chromium (V8) | 153.0.8010.12 | 288 to 332 ms | 40 to 42 ms |
| Firefox (SpiderMonkey) | 155.0 | 314 to 372 ms | 41 to 45 ms |
| WebKit (JavaScriptCore) | 26.6, Playwright's Linux build | 343 to 372 ms | 44 to 46 ms |

Four full runs per engine wrote byte-identical transcripts, which are the committed `wasm-chromium.txt`, `wasm-firefox.txt` and `wasm-webkit.txt`. The three engines agree on all 38 probes. Karma ran `studio:wasmJsBrowserTest` in Firefox ESR 153.4.0 in a container: 26 tests, 0 failures at that run (the Chrome run now has 69). Safari was not measured: its automation needs "Allow remote automation" in Safari's Developer settings, which was off and was not changed. Playwright's WebKit is a Linux build and says nothing about Safari. Two engine findings: headless Firefox in the container could not create the WebGL context that Compose needs, so the runs use headed Firefox under `xvfb`; and WebKit under `xvfb` with no locale reported `navigator.language` as `C`, and Compose threw `RangeError: invalid language tag`, so the runner sets the locale to `en-US`. Studio does not survive a browser that reports an invalid language tag.

**A CPU-dependent probe.** `kotlin.double.nan-bits` records the raw bits of `0.0 / 0.0`, `sqrt(-1.0)` and `inf - inf`. On x86-64 `0/0` has the sign bit set (`fff8000000000000`) and on Apple Silicon and arm64 Linux it does not (`7ff8000000000000`). Every browser column differs from `wasm` (Node on a Mac) on that probe only, and the three browsers agree with each other. A hand-assembled WebAssembly module doing `f64.div 0 0` and reinterpreting the result printed `7ff8...` on Node 26.8.2 on arm64 and `fff8...` on Node 22.23.3 on x64, so in this data the cell follows the CPU and not the engine. That script is not in the repository. The Node column was never recorded on x86-64, so the `wasm` column and the browser columns stay confounded. The same probe splits the JVM columns by CPU: `jvm` (JDK 21, Mac) and `jvm-21` (JDK 21, x86-64 Linux) differ on `double.nan-bits`, `math.pow-special-cases` and `math.transcendental-bits`, and a JDK 21 run on arm64 Linux is byte-identical to `jvm`. Linux x64 against Linux arm64 differs on `double.nan-bits` only.

**The JDK.** With the same classes (compiled for Java 17) on one x86-64 host, the JVM column changes with the JDK. JDK 17 against 21 differs in 6 probes: `char.bmp-case-mapping` and `char.bmp-categories` (the Character Javadocs of 17, 21 and 25 name Unicode 13.0, 15.0 and 16.0, and the probe's letter counts differ: 48,909, 48,965 and 48,973), `double.shortest-digits` (17 prints `2e23` as `1.9999999999999998E23`, 21 prints `2.0E23`; the JDK 19 release note JDK-4511638 describes the `Double.toString` change), `exceptions.messages`, `numbers.float-conversions` and `regex.unicode-matching`. JDK 21 against 25 differs in the two Character probes. One line of the 17 column is a harness effect: `removeFirst` on an empty list reports `NoSuchMethodError` because the probe was compiled against the JDK 21 API. The classification entries cite those pages; which Unicode release each table corresponds to was not checked beyond what the Javadocs name.

**R8 on Android.** Turning on `isMinifyEnabled` with `proguard-android-optimize.txt` shrank the release APK from 7,203,057 to 1,435,574 bytes and the app still launched, but the on-device Atlas summary changed from "38 of 38 probes match" to "35 of 38" and "matches no column: 3". R8 changed the measured result of 3 probes, which defeats the app, so minification stays off in `androidApp/build.gradle.kts`. The three probes were not identified, because a release APK cannot be read with `run-as`. This was measured once during packaging and not repeated for this report.

**The build host.** Eight `cli` executables were built from the same sources (x64 and arm64, linked on macOS or on Ubuntu, release or debug) and run in Debian containers. Only the macOS-linked release build prints `...a5` for `tan1` and `...7365` for `asin-half` and `acos-half` in `kotlin.math.transcendental-bits`; the other seven print `...a6` and `...7366`. The macOS-linked release transcripts are the committed `linux` and `linux-arm64` columns (sha256 equal), and `fixtures/atlas-variants` holds the Ubuntu-linked ones. A throwaway program (linuxX64, release, linked on macOS) printed `tan(1.0)`, `asin(0.5)` and `acos(0.5)` as `...a5` and `...7365` when written as constants and as `...a6` and `...7366` when read from the command line, so one executable disagreed with itself: the constants are Apple's `libm` values and the run-time values are glibc's. Linked on Ubuntu, or built as debug on macOS, it printed the glibc values for both. Ubuntu 24.04.5 and 26.04.1 builds gave identical transcripts. A run of one executable on glibc 2.36 and on glibc 2.39 printed identical output on all 38 probes (`fixtures/atlas-extra/linux-ubuntu`), so the runtime library does not explain it. The ladder's 2.4.20 Linux transcript, compiled with the standalone Kotlin/Native compiler on x86-64 Linux, prints the `...a6` and `...7366` values (`diff` of `fixtures/atlas-ladder/2.4.20/linux.txt` and `fixtures/atlas/linux.txt` shows those three lines and the two class-name lines). Gradle's native test binaries are debug builds, so no test binary can reproduce the committed `linux` column; `KotlinProbeTest` and `AtlasCommandTest` compare a Linux test binary with the variant transcript instead. The mechanism inside the compiler is an inference: that an optimizing build folds `tan`, `asin` and `acos` on constants with the `libm` of the machine that runs the compiler, and that a debug build does not. Nothing inspected the compiler. The same throwaway program for linuxArm64, linked on macOS, did not show the fold, though the real `cli` arm64 executable does; that is unexplained, so the arm64 evidence is the four builds, not the throwaway. The committed Linux columns are the macOS-linked release builds, and a run of `atlas.yml`, which links on Ubuntu runners, would record the `...a6` and `...7366` values. A Wine run of the mingw executable differs from Linux x64 on `math.transcendental-bits` only, so its NaN bits follow the x86-64 host and its last-bit results follow the C library it uses.

---

## Serving Studio Locally

`drift serve` serves the Studio web build to a browser on the same machine, so the Atlas viewer needs no hosting step. Its options are `--dir`, `--port`, `--tls` with `--cert` and `--key`, `--mkcert`, `--domain` (repeatable), `--mdns`, `--open`, `--unsafe-bind-all` and `--quiet`. It answers `GET` and `HEAD` for files under one directory and nothing else. The server core, request parser and static file rules are common code; the sockets are a JVM actual, a POSIX actual for macOS and Linux and a Winsock actual for mingw.

The loopback, Host, path, header and exit checks below were run on the macOS release executable of 2026-10-07 (port 18771). They were not repeated on the build of this report, and the serve tests in `cli` run on every build. Further variants named in them were tried earlier on the same executable.

- **Loopback only.** `lsof` shows listeners on `127.0.0.1` and `[::1]` and nothing else. `--unsafe-bind-all` listens on every interface, prints a warning, and still answers only loopback, IP and `--domain` names.
- **Host check.** `Host: evil.com` and `Host: 10.0.0.5` get 403, a request to `localhost` gets 200. The check is the defence against DNS rebinding. `localhost.evil.com` also gets 403 and a request over `[::1]` gets 200.
- **Paths and methods.** `/../../etc/passwd` and `/%2e%2e/` get 400 (`..%2f`, `..%5c`, `%00` and `/a/..;/../` also get 400), `/etc/passwd` gets 404, a `POST` with a body gets 400 and `PUT` 405. A request with `Accept: text/html` for an unknown route returns `index.html`; an unknown asset returns 404. Hidden files are not served and a symlink out of the root is refused.
- **Headers.** `.wasm` is `application/wasm` with `Cache-Control: public, max-age=31536000, immutable` for hashed names, other files are `no-cache`, and every response has `X-Content-Type-Options: nosniff` and `Cross-Origin-Resource-Policy: same-origin`. No CORS header is sent, even for a foreign `Origin`. `Cross-Origin-Opener-Policy` and `Cross-Origin-Embedder-Policy` are not sent: `crossOriginIsolated` is false in all three browser engines and Studio still loads and computes.
- **Exit.** SIGINT prints `stopped` and exits 0 on the native executable. The JVM build prints the same and exits 130.
- **Limits.** A request body is refused, the head is capped at 16 KiB, there are at most 128 connections, a connection idles for 5 s and a head must arrive in 10 s. One request is served per connection and each file is read whole into memory; range requests and compression are not implemented. A symlink swapped after the path check, slow-loris variants beyond the head timeout and Windows alternate data streams were not tested.

**Where the build comes from.** `--dir` names a directory with an `index.html`. Without it `drift serve` looks for `studio` beside the executable, then for `../share/drift/studio`, and exits 1 with both paths if neither exists. `./gradlew :cli:stageStudio` runs the web distribution and copies it, without source maps, to `cli/build/serve/studio` (12 MB). `drift install` does not copy it. Embedding the build in the executable was measured and rejected: a gzip of the 12.4 MB build stored as a base64 Kotlin string grew a trivial macOS arm64 executable from 493,368 to 12,588,056 bytes (+12.1 MB), and the raw build as base64 grew it to 33,913,192 bytes (+33.4 MB), because Kotlin/Native stores strings as UTF-16. Drift is 6.7 MB without the build. That closes one mechanism. Embedding the bytes without string overhead (a linker section, or a C array through cinterop) was not tried and would be per-operating-system work; no size is claimed for it.

**TLS.** Browsers treat `http://localhost` as a secure context and Studio loads over it in Chromium, Firefox and WebKit, so HTTPS is optional. The JVM build uses `javax.net.ssl.SSLContext` with an unencrypted PKCS#8 PEM key. The macOS and Linux native builds `dlopen` the system OpenSSL at run time (3 or 1.1; macOS's own LibreSSL is not used) through 17 `libssl` and `libcrypto` symbols, with TLS 1.2 as the minimum and no link-time dependency, so nothing is bundled and the executable does not grow. A host with no usable OpenSSL gets "this build cannot serve https" and exit 1, never a silent downgrade to HTTP; that path is covered by a test with a fake system, not by running without OpenSSL. The Windows build has no TLS library, refuses `--tls` and `--cert` with exit 1 (checked under Wine), and refuses `--mdns` with exit 2. Ktor's server on Kotlin/Native cannot do TLS without a reverse proxy according to its documentation (read through a fetch summary, not in full), so it was not built or added.

With a scratch certificate authority, `curl --cacert` returned 200 with verification passing on the JVM, on macOS Native (OpenSSL from Homebrew) and on Linux x64 Native (Ubuntu 24.04, OpenSSL 3.0.13); without the CA curl failed, a TLS 1.1-only client failed, TLS 1.2 passed, and plain HTTP to the TLS port failed. In Playwright on Linux, Chromium and WebKit loaded `https://localhost` with the CA in the system store and no ignore flag; Firefox trusted it only through a profile `cert9.db` holding the CA, not through the system store or a `policies.json`. Chromium and WebKit also loaded Studio over `https://drift.studio:8443`, resolved by a container's `/etc/hosts`.

**mkcert and names.** `--mkcert` runs `mkcert -CAROOT` and continues only if that directory already holds a CA. It then writes a leaf certificate for `localhost`, `127.0.0.1`, `::1` and each `--domain` into Drift's configuration directory (key mode 0600, directory 0700). It never runs `mkcert -install`, because that edits the system trust store; with no CA installed it prints the commands and serves plain HTTP. mkcert 1.4.4 exits without a certificate when `JAVA_HOME` points at a JDK without `lib/security/cacerts`, so on Unix Drift starts it through `env -u JAVA_HOME`. The path with the CA installed was not run on a real machine; a test covers it with a fake mkcert. `--domain` prints, for each name, whether it resolves to loopback, the exact `/etc/hosts` line and the mkcert command, and never edits `/etc/hosts`. `--mdns` starts `dns-sd` (macOS) or `avahi-publish` for the first `.local` name: on the Mac `dns-sd -G v4 drift.local` returned 127.0.0.1 and `curl` got 200, and after SIGINT no process and no record remained. While it runs, other devices on the network can see a `drift.local` record that points at their own loopback.

**Linux test runs.** Running the Linux native tests in a container found two bugs in the native serve code, both fixed. `kill(pid, 0)` succeeds for a zombie, so a child whose program failed to exec, under a PID 1 that never reaps, counted as alive and `stop` waited the full 2 s; `PosixServe.kt` now also reads the state in `/proc/<pid>/stat` (`processAlive`). And when an untrusted client hung up during the handshake, the write to the closed socket raised SIGPIPE and killed the test process (exit 141); `listen` now ignores SIGPIPE, which only the CLI command did before. The https test runs for real when a libssl, `openssl` and `curl` are present (it ran with OpenSSL 3.0.13 and 3.5.5), and prints its reason and returns when one is absent. Not verified: that the GitHub runner image ships `libssl.so.3` by name; its readme lists OpenSSL 3.0.13 (read through a fetch summary), and a 24.04.5 container with that OpenSSL passed.

**Tests and gaps.** The serve tests (test classes with `Serve` in their name) number 51 on the JVM, 50 on macOS Native and 37 on Wasm. Under Wine, `drift.exe serve --dir` served `/` and the Wasm file with the right MIME type, refused traversal and a bad Host, and printed `stopped` on SIGINT. Not run: real Windows, Safari, `--open` with a real browser launch (a fake runner only), the Linux arm64 and macOS x64 executables, and a Linux or macOS host with no OpenSSL.

---

## Distribution

**CLI archives.** `.github/release/stage.sh archive` packs an executable with `LICENSE`, `THIRD-PARTY-NOTICES.txt` and a `README.txt` into `drift-<version>-<os>-<arch>.tar.gz` (`.zip` for Windows), flat, with a `.sha256` sidecar; `stage.sh sums` checks every sidecar and writes `SHA256SUMS`. Run on the current executables for this report:

| archive | bytes |
| --- | --- |
| `drift-1.0.0-macos-arm64.tar.gz` | 2,645,407 |
| `drift-1.0.0-linux-x64.tar.gz` | 2,793,616 |
| `drift-1.0.0-linux-arm64.tar.gz` | 2,458,436 |
| `drift-1.0.0-windows-x64.zip` | 2,781,268 |

`shasum -a 256 -c SHA256SUMS` passed for all four, and the macOS archive extracted and printed `drift version 1.0.0 (macos)`. There is no macOS x64 archive: the build has no `macosX64` target.

**`drift install` and `drift uninstall`.** `drift install [--user | --global] [--dir PATH] [--no-modify-path] [--dry-run]` copies the running executable (native builds only; the JVM and Wasm builds exit 2) and records what it changed in a receipt, `install-receipt.json`. The default scope is `--user` unless the process is privileged. User directories are `$XDG_BIN_HOME` (only if absolute) or `~/.local/bin`, and `%LOCALAPPDATA%\Programs\Drift` on Windows; global directories are `/usr/local/bin` and `%ProgramFiles%\Drift`. `--global` never calls `sudo`: when the target is not writable it exits 1 and prints the exact `sudo` command. The binary is written to `<target>.new` and renamed, and identical bytes are not rewritten. On Unix, a user-scope install whose directory is not on `PATH` appends a marked `# >>> drift >>>` block to the profile files that exist (`.profile`, `.bash_profile`, `.bashrc`, a zsh file, or a fish `conf.d` file); on Windows it appends the directory to the `Path` value in `HKCU\Environment` (or the machine key for `--global`), keeping the value's type, without expanding variables, and broadcasts `WM_SETTINGCHANGE`. `drift uninstall` reverses the recorded edits in reverse order, removes the binary and the directories it created if empty, and then the receipt; with no receipt it says so and removes nothing. `--dry-run` prints every action and changes nothing, and two dry runs on the Mac were byte-identical.

Run for this report, on the macOS release executable in a scratch home directory (`tools/test-installers.sh`): 33 checks passed and none failed. They cover install, a second install that changes nothing, uninstall restoring every byte of the home directory (profile checksums equal the "before" values), a corrupted archive refused as a checksum mismatch with nothing changed, no network, `--no-modify-path`, and conflicting flags exiting 2. The same script passed 33 of 33 in a Debian 12 container as a non-root user. `install.sh` (POSIX `sh`) maps `uname` to an asset, refuses musl (on Alpine 3.21 it exits 1 with "built for glibc and this system uses musl"), checks the SHA-256 sidecar, runs `--version` before handing over to `drift install`, and passes `shellcheck` clean; `InstallScriptTest` runs it hermetically (19 tests, with a fake binary and a `file://` release). `install.ps1` parses with 0 errors under PowerShell 7.4.2, has no PSScriptAnalyzer findings including the Windows PowerShell 5.1 syntax rule, and a mocked run passed 16 of 16. The Windows registry logic ran under Wine (not Windows): 10 of 10 comparisons of the registry and file tree before and after matched, including a `REG_EXPAND_SZ` value with unexpanded variables, a missing `Path`, and an uninstall of a running binary.

Not verified: real Windows (`GetModuleFileNameW`, the elevation check, the effect of the broadcast on Explorer, a detached cleanup after exit), Windows PowerShell 5.1 execution, a real download from GitHub or the `/releases/latest` redirect against github.com (a local server stood in), macOS Intel and Rosetta, and a read-only or full disk. Clikt's own parse errors exit 1 here as in every other command, and Drift's own usage errors exit 2.

**Studio installers.** `studio/build.gradle.kts` configures jpackage through the Compose plugin; version, app name and icons come from the build.

| asset | bytes | built on | tested |
| --- | --- | --- | --- |
| `Drift-1.0.0.dmg` | 74,624,330 | this Mac | mounted read-only; a copy launched and stayed alive 20 s |
| `Drift-1.0.0.pkg` | 70,015,031 | this Mac | expanded and listed, never installed |
| `drift_1.0.0_amd64.deb` | 58,793,038 | Linux container | `dpkg -i`, desktop file validated, ran 18 s under `xvfb`, `dpkg -r` |
| `drift-1.0.0-linux-x64.rpm` | 68,836,245 | Linux container | `dnf install`, `rpm -V`, `rpm -e` in Fedora |
| `drift-1.0.0-linux-x64.AppImage` | 66,243,064 | Linux container | extract-and-run 18 s under `xvfb` |
| `drift-1.0.0-android.apk` | 7,203,057 | this Mac | installed and launched on an emulator |
| `.msi`, `.exe` | not built | | configured only; need WiX on a Windows runner |

These sizes are from the builds of 2026-10-07 and were not rebuilt for this tree. The Studio sources have changed since (the design tokens, the Home and Case tabs, the Upgrade, Contract and Bisect screens, the third-party notices), so the packages would differ. Nothing is signed or notarized. The macOS app has an ad-hoc signature and Gatekeeper's `spctl` reports it as rejected; the APK is signed with the build machine's debug key, so a sideloaded update over a build from another machine needs an uninstall first. The deb and rpm are desktop packages: in a bare container `dpkg -i` and `dnf install` failed in jpackage's postinstall script until the desktop and MIME directories existed, as they do on a desktop. The `.driftcase` file association is configured and Studio shows an opened case on its Case tab; a double click was run on macOS from the `createDistributable` app only (see Open in ADVANCED_USAGE.md). The iOS app is a simulator build; no `.ipa` is made. Windows installers, per-machine install with a UAC prompt, upgrade UUID behavior and SmartScreen were not tested.

**Homebrew and Chocolatey.** `packaging/` holds the templates; `./gradlew renderPackaging -Pdrift.sums=SHA256SUMS` copies them to `build/packaging` and fills `@version@`, `@sha256:<suffix>@` (the digest of `drift-<version>-<suffix>` in `SHA256SUMS`) and `@license@`, and fails when a token names an asset missing from the sums file. Ten build-core tests cover it, and a render on the Mac and one in an Ubuntu container were byte-identical.

- The Homebrew formula `drift` installs the CLI on Apple silicon macOS, Linux x64 and Linux arm64 (no Intel macOS asset). The cask `drift-studio` installs the dmg and removes the quarantine attribute after install, because the app is not notarized; Homebrew's own policy says casks that fail Gatekeeper are disabled, so this cask can only live in a third-party tap. `brew style` and `brew audit --strict` reported no offenses, a formula install from a scratch tap against a local HTTP server printed `drift version 1.0.0 (macos)` and passed `brew test`, and the cask installed into a scratch application directory, launched and uninstalled. All of that was cleaned up afterwards. The public tap repository does not exist yet, so `brew audit --online` and a real `brew install gmitch215/tap/drift` were not run.
- The Chocolatey packages are `drift` (the Windows zip) and `drift-studio` (the MSI, installed silently with `/qn /norestart`). Real `choco pack` 2.7.4 under Mono built both nupkgs (4,916 and 4,977 bytes), and `packaging/chocolatey/verify.ps1` ran the rendered install and uninstall scripts against stubbed Chocolatey helpers in PowerShell 7.4.2 and compared URL, checksum, silent arguments and exit codes with `SHA256SUMS` (`all checks passed`; altering two digests made it fail). The MSI was never built, so the digest in the `drift-studio` render used for that check is a placeholder of zeros. Real `choco install`, the community repository's moderation, and whether the id `drift` is free were not tried.

**The release workflow.** `release.yml` builds the four CLI archives, the Studio packages and the APK, adds the sidecars, aggregates `SHA256SUMS`, attests each build job's files, publishes the GitHub release, and then runs the `homebrew` and `chocolatey` jobs when no suffix is given. They need two repository secrets, `HOMEBREW_TAP_TOKEN` and `CHOCOLATEY_API_KEY`, and fail early when one is empty on a real run. `dry-run` builds and checks everything and uploads the results as artifacts without attesting or pushing. `stage.sh`, the asset wait script and the render task were exercised by hand, and the steps of the Homebrew job ran from a fresh clone on the Mac and in a container. No job has run on GitHub, and no Windows machine was used for any step.

---

## Performance

`tools/perf` measures start-up, capture, diagnose, peak memory and size. A manifest (`tools/perf/manifest.yml`, 13 steps) fixes the workloads: `drift --version`, `drift capture --label perf` and `drift diagnose` on the two drangler capsules. `perf.sh` runs each workload 3 times to warm up and then 15 times, and `measure.py` reads wall time and peak resident memory from `os.wait4` rather than `/usr/bin/time`; `summarize.py` writes the table. The numbers below were recorded on 2026-10-12 and are the median with the minimum and maximum over the 15 runs, in milliseconds. They are in `tools/perf/results`, and they were not repeated on the build of this report. That build is larger: the macOS executable measured at 7.23 MB then is 9,866,776 bytes now, because the catalog, the scanners and the commands grew, so start-up times on the current build are not known.

| host | build | `--version` | `capture` | `diagnose` | peak RSS (diagnose) | executable | load1 at start |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Mac | JVM | 346.6 (330.6-707.5) | 163.5 (155.0-180.0) | 179.7 (171.7-223.0) | 79.6 MB | 0.29 MB launcher | 5.7 |
| Mac | Native | 7.5 (6.9-9.6) | 24.3 (22.2-27.0) | 9.0 (8.0-10.2) | 13.0 MB | 7.23 MB | 5.7 |
| Mac | Wasm | n/a | n/a | n/a | n/a | 12.07 MB wasm, 4.40 MB gzip | 5.9 |
| paisley-park, 8 CPUs | JVM | 135.4 (133.5-142.0) | 100.1 (98.8-105.0) | 120.1 (118.2-135.6) | 73.0 MB | 0.29 MB launcher | 0.2 |
| paisley-park, 8 CPUs | Native, built there | 1.6 (1.6-2.0) | 2.7 (2.6-3.3) | 2.5 (2.5-3.6) | 16.8 MB | 7.69 MB | 0.8 |
| paisley-park, 8 CPUs | Native, cross-built on the Mac | 1.7 (1.6-2.1) | 2.8 (2.6-3.2) | 2.6 (2.6-3.1) | 17.4 MB | 7.70 MB | 0.1 |
| paisley-park, 2 CPUs | JVM | 135.0 (131.9-321.6) | 101.2 (99.8-104.5) | 124.9 (122.2-139.3) | 71.4 MB | 0.29 MB launcher | 0.3 |
| paisley-park, 2 CPUs | Native, built there | 1.7 (1.6-2.7) | 2.7 (2.6-3.2) | 2.7 (2.6-3.6) | 16.9 MB | 7.69 MB | 0.7 |
| paisley-park, 2 CPUs | Native, cross-built on the Mac | 1.7 (1.6-2.6) | 2.7 (2.6-3.4) | 2.6 (2.6-3.2) | 17.4 MB | 7.70 MB | 0.2 |

**Machines and load.** The Mac is an Apple M2 Pro with 16 GB and JDK 25 (Temurin). Its runs started at a one-minute load of 5.7 to 5.9 from other builds and applications, so they are not quiet-machine numbers; an earlier pass at load 8.8 gave a JVM `--version` median of 343.8 ms against 346.6 here, which is within the spread. paisley-park is an x86-64 Linux host with 24 CPUs. Its runs were in containers capped at 8 CPUs and at 2 CPUs (`--memory 10g`), at a load below 1. The Mac and paisley-park rows compare configurations and not hardware: a container on a different CPU family and operating system measures nothing about the Mac.

**Cross-built against native-built.** The Linux x64 executable linked on the Mac and the one built inside a paisley-park container run within 0.1 ms of each other on every workload, at 8 and at 2 CPUs. The two binaries differ by 776 bytes and are not byte-identical. Two CPUs make no difference to the native build, which is single-threaded. The JVM column is dominated by JVM start-up, about 135 ms for `--version` on paisley-park; the native build is about 85 times faster there and 46 times faster on the Mac.

**Wasm.** The CLI has no Wasm executable, so Wasm is measured by size only: the Studio bundle's `.wasm` is 12.07 MB, 4.40 MB gzipped. No Wasm timing was taken.

**Not measured.** Windows and macOS Intel executables, a Linux arm64 executable on arm64 hardware, a cold file cache, Studio's load time and memory, the Lab's container build time, and the time of `triage` and `upgrade-check` (added after the sweep). Every cell is one session on one machine, so the min-max shows noise within a session and not between days.

---

## DriftBench

DriftBench scores a diagnosis against a known cause. A scenario is a pair of containers, green and red, running one small program. The red arm fails at a declared rate and the green arm passes. The injected cause is ground truth by construction, because the scenario was built around it, so the benchmark measures how well a ranking recovers a cause that was put there. It is synthetic and every scenario and result file says so (`"synthetic": true`).

**Scenarios.** 43 templates in 16 YAML files (`bench/scenarios/*.yml`, one per dimension and `migration.yml` with four templates for runtime upgrades) produce 80 scenarios. A template names a dimension, a program in `sh`, `python3`, `node` or `java`, the failure signature, the run configuration of each arm (image, environment, memory, CPUs, cpuset, ulimits, network, files), and the cause with optional bundle members and decoys. A seeded SplitMix64 generator (seed 20261006) picks variants and draws values. The class of a scenario is derived: `control` (no cause), `intermittent` (red rate under 1,000 permille), `bundle`, `decoy`, else `single`. The 80 split into single 29, bundle 18, intermittent 13, decoy 12 and control 8.

**Intermittency.** The harness arms the fault on trial `i` when a fixed integer hash of `i` and the scenario salt is under the gate, and the program fails only when the fault is armed and the injected cause is present. The failing trials are therefore known before Docker runs, and a flaky scenario is a deterministic pattern with a declared rate. No scenario contains a real race.

**Causes a container cannot vary.** Kernel release, clocksource, CPU model and browser engine run one configuration in both arms; the cause values exist as `capsule-overrides` written into the saved capsules, marked `bench-synthetic`. 10 scenarios are of this kind (`controllable: false`). Separately, 15 scenarios mark the cause `visible: false`, because the capsule has no attribute for it yet (open file descriptor limit, file size limit, cpu count under a cpuset, hosts file, lockfile pins, shell flavor, npm version). Those scenarios measure whether a diagnoser abstains.

**Validation.** A scenario is valid when both arms finish, the green arm passes, every red failure carries the declared signature (text or exit code), the failing trials equal the prediction from the hash gate, each arm's failure count passes an exact two-sided binomial test against the declared rate (each tail at least 0.0005, `BigInteger`), and the cause path appears in the capsule diff exactly when `visible` says so. A scenario that fails is listed in `data/dropped.json` and left out; its program is fixed, and no tolerance is loosened.

Dev scenarios ran in Docker on a Linux host, capsules captured with the Linux release executable (`capture --tools`) inside each arm:

| quantity | value |
| --- | --- |
| dev scenarios validated | 49 of 49; `data/dropped.json` is `[]` |
| trials run, both arms | 2,580 |
| red-arm failures | 978 |
| green-arm failures | 0 in every scenario |
| scenarios whose failing trials equal the prediction | 49 |
| scenarios with an overridden capsule value | 5 |
| scenarios with an invisible cause | 11 |

The four migration dev scenarios (PHP 7.4 and 8.3, Node 18 and 22, JDK 17 and 21, Python 3.11 and 3.13) ran on a Mac under Docker Desktop (linux/arm64) with the Linux arm64 executable inside each arm; the others ran on a Linux host. The first run had one drop, `build-npm-sbom-plain-1`, whose program sent npm's error message to `/dev/null`, so the red arm failed without the declared signature (green 0 of 20, red 20 of 20). The program was fixed and the batch rerun; the final run has no drops.

**Sealed test split.** A scenario is in dev when the first four bytes of `sha256(id)`, read as an unsigned integer, mod 100 are under 60. That gives 49 dev and 31 test scenarios. The test list is sealed: the SHA-256 of the sorted test ids, each followed by a newline, is `457097f88491381de17a43a67a7f09ab3843d008c838f02db2e81b0c6d5b4068`, committed in `data/split.json` and pinned in `CommittedDataTest`. `prepare` refuses a test id, the calibration harness refuses it (`SealedException`), and a test asserts no result or capsule file exists for a test id. The test scenarios were never executed, run through the ranker or scored. They are validated only through the dev sibling instances of the same templates. The hash and the split rule were recomputed independently from `bench/data/scenarios.json` for this report (see Reproducing the Numbers).

Template ids were renamed (suffixes such as `-basic`) until every template had at least one dev instance and the dev share was near 60%; it was 59.2% (45 of 76) before the four migration scenarios, which all fall in dev by the hash rule, and is 61.3% (49 of 80) now. The sealed list and its hash did not change. Only the hash rule was used, and no measurement.

---

## Evaluation and Limits

### Calibration on the Dev Split

The `bench` harness (`Observe.kt`, `Evaluate.kt`) runs the real `Ranker` on the capsule pair of each of the 49 dev scenarios and compares the result with the injected cause. The input that matters is the probed pair: the passing and the failing baseline that `solve` captured inside its containers with `capture --probes` (71 probes each, stored in `bench/data/probed/`). 45 scenarios have a cause (4 are controls). On the probed pairs the ranker produced 99 candidates, 29 of them the cause. In 14 of the 49 scenarios a probe differs between the two sides, which links 20 candidates to a probe that reads their attribute. Only four probes ever differ in these pairs (`resources.locale-env` in 7 scenarios, `resources.cgroup-memory` in 6, `process.timezone` in 5, `resources.cgroup-cpu` in 4), and each reads exactly the attribute that changed (`env.LANG`, `cgroup.memory.max`, `env.TZ`, `cgroup.cpu.max`). Seven scenarios now match a catalog rule (`runtime-java-virtual-basic-1`, `compiler-c2x-plain-2`, `os-distro-family-basic-1`, `os-glibc-floor-1` and three of the four migration scenarios). None matched one when the catalog held 32 rules, so an earlier version of this report said that no dev scenario matches a rule; that sentence is false for the current catalog of 296. Two comparison inputs share the harness: the same pairs with the probes removed, which gives a paired measurement of what probes do, and the original capsules the scenarios were validated with (no probes, 106 candidates, 33 of them the cause). The probed pairs come from the `reproduce` containers, so the five scenarios whose cause a container cannot set (kernel, CPU model, browser engine) have no candidates in them; the original capsules keep those.

Folds are the first four bytes of `sha256("fold:" + id)` mod 5. A model replaces the shipped one only if its out-of-fold Brier score is at least 2% lower and its out-of-fold MRR is not lower, and the Brier score is taken over the rankings that print a probability (those whose candidates all rest on attribute changes, 30 candidates).

The calibration files were regenerated on 2026-10-13 after the dev split grew from 45 to 49 scenarios (four migration scenarios, see DriftBench) and the catalog from 32 to 296 rules. The weights table did not change, and `selection.chosen` is still `hand-set+softmax-none`. `evaluation.json` is `4effe497...e374` and `fit.json` is `fbbb6f45...4a31`; `CalibrationTest` regenerates both and compares bytes.

Order of the hand-set ranker on the 45 scenarios with a cause (Wilson 95% intervals; "P" is the exact chance that a random order does at least as well, one-sided, not corrected for the three inputs):

| input | order | top-1 | top-3 | MRR | P, top-1 / top-3 |
| --- | --- | --- | --- | --- | --- |
| original, no probes | random (expected) | 0.440 | 0.665 | 0.554 | |
| | diff order, alphabetical | 19 of 45 (0.29 to 0.57) | 29 of 45 (0.50 to 0.77) | 0.549 | |
| | hand-set | 26 of 45 (0.43 to 0.71) | 33 of 45 (0.59 to 0.84) | 0.652 | 0.003 / 0.012 |
| probes removed | random (expected) | 0.362 | 0.575 | 0.470 | |
| | diff order | 15 of 45 (0.21 to 0.48) | 24 of 45 (0.39 to 0.67) | 0.449 | |
| | hand-set | 23 of 45 (0.37 to 0.65) | 29 of 45 (0.50 to 0.77) | 0.574 | 0.001 / 0.009 |
| probed | random (expected) | 0.362 | 0.575 | 0.470 | |
| | diff order | 15 of 45 (0.21 to 0.48) | 24 of 45 (0.39 to 0.67) | 0.449 | |
| | hand-set (shipped) | 21 of 45 (0.33 to 0.61) | 29 of 45 (0.50 to 0.77) | 0.548 | 0.018 / 0.009 |

The control arm of this evaluation is the three baselines (random order, alphabetical diff order, the logistic reading of the score). The ranker is deterministic, so the only spread is the fold split. The previous measurement, on 41 scenarios and 32 rules, found 19 of 41 at top-1 (P = 0.034) and 24 of 41 at top-3 against 23.1 expected (P = 0.37), and this report concluded that the order at top-3 could not be told from chance. That conclusion is refuted by the regenerated data: 29 hits against 25.9 expected at top-3 (P = 0.009), and 21 against 16.3 at top-1 (P = 0.018). The top of the order is better than a random order, and the gain over alphabetical order is 6 scenarios at top-1 (21 against 15) and 5 at top-3 (29 against 24). The change comes from the data and not from the weights: four scenarios were added, the catalog grew, and seven scenarios now carry a rule match. Measured on the 45 scenarios with a cause, the shipped order is still no better with probes (21 at top-1) than without them (23).

**Probes.** Probes change the order of two scenarios, both for the worse. In `env-pool-size-decoy-2` the cause `env.DB_POOL_SIZE` ranks first without probes and third with them, because its two decoys `env.LANG` and `env.TZ` really changed, the locale probes that read them differ, and so the decoys reach `plausible` while the cause stays at `difference`. In `migration-python-copy-1` the cause ranks first without probes and second with them. Probes help no scenario. A probe says that an attribute it reads behaved differently. It cannot say that the attribute caused this failure, and the dev scenarios inject decoys that are real differences, so a probe agrees with them as much as with the cause. In a scenario with one candidate there is nothing to reorder. The tier is compared before the score, so no probe weight can change the order (`noProbeRatioChangesTheOrderBecauseTheTierComesFirst`).

Probes were first linked to candidates through their dimension. They are now linked through the attribute path they read (`ProbeLinks`, see Ranking and Bundles). On the 45 scenarios and 32 rules then in use the two rules gave the same dev results: every ranking, tier, top-1, top-3, MRR and false-confidence rate was identical before and after (84 model blocks of `evaluation.json` compared, `fit.json` byte-identical), and the new rule demoted none of the 19 probe-linked candidates. That comparison was not repeated on the current catalog and the committed files have since changed, so it stands as a record of the earlier measurement. The change removes an unsound rule, and the tier counts below were measured with it in place.

**False confidence.** With neither probes nor rules, nothing reaches `plausible` or `known`, so the rate of a confident wrong answer is 0 by construction. With them it is not 0.

| quantity | value |
| --- | --- |
| candidates by tier | `difference` 71, `plausible` 25, `known` 3 |
| candidates that are the cause, by tier | `difference` 15 of 71 (0.21), `plausible` 11 of 25 (0.44), `known` 3 of 3 |
| scenarios whose top candidate is `plausible` or `known` and wrong | 10 of 49 (Wilson 0.11 to 0.34) |
| confident candidates (`plausible` or `known`) that are wrong | 14 of 28 (0.33 to 0.67); 14 of 25 `plausible` candidates |
| controls with a confident candidate | 1 of 4 (`control-locale-noise-fixed-1`, whose decoy `TZ` does differ and the timezone probe sees it) |

The three `known` candidates come from a matched catalog rule, and the cause rate of 3 of 3 says only that those rules agreed with the injected cause. Their interval, 0.44 to 1.00, is wide. The intervals for `difference` and `plausible` overlap, so the data does not show that `plausible` is better evidence than `difference`. In plain terms, the `plausible` tier is not reliable on this benchmark. More than half of what it names is wrong, and a user reading "plausible" as "probably right" would be misled about as often as not. The wrong `plausible` candidates are decoys that changed, and the probe is right about them: it separates attributes that behaved differently from attributes that did not, and does not separate a cause from a bystander. Only a link through the symptom (the probe whose output relates to the failure) could, and it was not tried. The ranking still prints the tier and the mechanism that earned it, and prints no probability for it.

**Re-weighting is refuted.** The probe, rule and spectrum weights were refit out of fold and none replaced the hand-set value.

- A fitted probe ratio, smoothed from the odds of a probe candidate being the cause, is 1.29 to 4.67 across the five folds (2.26, 4.67, 1.89, 1.67, 1.29), and the fit is not applied (`evidence_fit.applied` is `false`).
- Cross-validated Brier on the rankings that carry a probe at probe ratios 1, 1.5, 2, 3, 4 and 6 (the hand-set value) is 0.170, 0.175, 0.182, 0.194, 0.204 and 0.218, against 0.187 for "one of n". The hand-set ratio reads worse than "one of n" there. A ratio of 1 (the probe is no evidence) is best, which is not a result at this sample size.
- Rule weights and spectrum weights stay hand-set. Seven scenarios now carry a rule match, which is too few to fit a weight, and no dev scenario has a history.
- Fitted per-dimension priors lose again against the hand-set priors in the out-of-fold comparison that `fit.json` records.

The weights stay hand-set. This refutes one mechanism, that a fitted weight improves the order or the printed probability on this benchmark. It does not close the objective of weighing probe evidence. A probe linked through the symptom it relates to was not tried, and the data here says only that probes linked through the attribute they read carry nothing beyond the attribute change.

**Probability readout.** Models on the rankings that print a probability (30 candidates), Brier score out of fold unless stated:

| model | Brier |
| --- | --- |
| uniform 1/n | 0.179 |
| hand-set, p = logistic(score) | 0.311 |
| constant base rate | 0.254 |
| softmax of the scores, none weight 1 | 0.166 |
| softmax and a fitted none weight (shipped; fit 0.25), in sample | 0.155 |
| softmax and a fitted none weight (shipped; fit 0.25) | 0.166 |
| isotonic and fitted-prior variants | 0.206 to 0.270 |

The shipped readout is 7% below a uniform guess on those rankings and is mostly a count of rivals. It beats the logistic reading, the base rate and every isotonic map. On all rankings it reads 0.146 out of fold against 0.160 for uniform. The covered set shrank from 51 candidates in the earlier report to 30, because rule-matched and probe-linked candidates left the attribute-only class, so these figures do not compare one to one with the earlier 0.149. Reliability, out of fold, in five bins of width 0.2 (candidates, mean predicted, observed): 4, 0.188, 0; 12, 0.246, 2; 7, 0.461, 3; 5, 0.720, 5; 2, 0.923, 1. The none weight was fit on this benchmark's mix, where 20 of 49 scenarios have no cause (4) or one the ranking cannot list (16). It says nothing about how often real failures have no listed cause. On the drangler pair the nine probabilities add to 0.989, a claim of 98.9% that one of nine changes is the cause, which the case data does not support. The probability stays attribute-only.

Other facts of the shipped model on the probed pairs, out of fold. Top-1 by class: single 5 of 15, bundle 8 of 12, decoy 3 of 9, intermittent 5 of 9. The cause ranks above every decoy in 3 of 7 decoy scenarios (4 of 7 without probes). In the 11 bundle-class scenarios with a ranked cause, 6 put it inside a bundle (0.28 to 0.79, down from 8 of 8 on the earlier data); in the other classes the cause shares a bundle in 4 of 18. The 5 manual scenarios have no candidates at all in the probed pairs. Reversing green and red on the probed pairs gives 11 candidate rule matches in 5 scenarios, 7 printed `known` and 24 `plausible`; no inferred-basis rule printed `known`, and the reversed pairs have no ground truth, so the accuracy of those tiers is unmeasured.

Fold assignment is by id hash, so sibling instances of one template can land in different folds, which makes the out-of-fold figures optimistic. The family of probability maps was chosen after seeing that per-candidate maps lost, so cross-validation protects the fit but not the choice of family. The probed run is a second measurement on the same 49 scenarios, not independent data.

### The Lab on the Dev Split

All 45 dev scenarios ran through `drift solve` on a Linux host with Docker (this first run is described here; three repeats follow), using the Linux release executable inside each arm to capture its capsule. The sealed test split was never run. Per scenario, `--run` names the scenario's toolchain in its first word and `--fail-when` is the scenario's signature (or `exit`). The 45 runs used 1,460 trials in total, from 16 trials for a scenario that stopped at the pilot to 76 for the largest bundle. Truth comes from `bench/data/scenarios.json`. A CONFIRMED verdict counts as correct when its minimal set is exactly the injected cause. A bundle verdict counts as containing the cause when the true path, or one of the scenario's declared bundle members, is among its members.

| class (dev) | n | CONFIRMED, correct | CONFIRMED EFFECT (bundle) | NARROWED | STUCK |
| --- | --- | --- | --- | --- | --- |
| single | 15 | 4 | 0 | 0 | 11: 9 not-reproduced, 2 uncontrollable |
| bundle | 9 | 0 | 9 | 0 | 0 |
| decoy | 9 | 7 | 0 | 0 | 2 not-reproduced |
| intermittent | 8 | 3 | 0 | 1 | 4: 2 not-reproduced, 1 budget, 1 uncontrollable |
| control | 4 | 0 | 0 | 0 | 4 not-reproduced |
| all | 45 | 14 | 9 | 1 | 21 |

The result is 14 CONFIRMED, all correct; 9 CONFIRMED EFFECT (bundle), all with the cause inside; 1 NARROWED; 21 STUCK (17 not-reproduced, 3 uncontrollable, 1 budget); 0 CONFIRMED on a wrong cause; 0 on a control. Of the 9 bundles, 8 list the true path among their members. The ninth, `build-npm-sbom-plain-1`, does not: its cause is the npm version, which no capsule attribute shows, and the arms differ in `tool.node.version` and two environment variables, which the scenario declares as bundle members.

What the 21 STUCK runs say. Of the 17 not-reproduced, 10 have a cause that the capsule cannot show (`limits.*`, hosts file, lockfile pins, shell flavor, CPU count under a cpuset), 4 are controls, 2 are scenarios whose cause a container cannot set, and 1 is `locale-setlocale-2`. The run notes attribute that one to `reproduce` installing the missing locale in the container, so the failing container does not fail; that was not tested again. In all 5 manual scenarios of the dev split, none reached a CONFIRMED verdict (3 uncontrollable, 2 not-reproduced). The four controls ended `STUCK` because the same fault gate failed both containers equally (5 of 8 against 5 of 8 for `control-locale-noise-fixed-1`).

A few single runs, from the certificates:

- `locale-tz-date-2`: CONFIRMED `env.TZ`, failures 0 of 5 to 5 of 5 (exact one-sided p 0.003968) and back to 0 of 5 in reverse. The mechanism field reads "a probe differs for env.TZ", because the arm capsules carry probes.
- `memory-oom-1`, an intermittent scenario: CONFIRMED `cgroup.memory.max` with 13 trials per arm, 0 of 13 to 5 of 13 (p 0.019565).
- `env-retries-flaky-plain-1`: NARROWED, one experiment stayed inconclusive at 21 trials per arm.
- `cpu-limit-quota-basic-2`: STUCK `budget`, because no arm of up to 30 trials reaches alpha at the target power.
- `os-glibc-floor-1`: CONFIRMED EFFECT (bundle) of 4 attributes (OS release name and version, coreutils, libc); the settable member is the OS release name, and the verdict says the effect is the bundle's.

The limit of the first run is easy to read past: each scenario ran once, with one seed. Its 0 false CONFIRMED is a measurement on 45 synthetic scenarios, with 14 CONFIRMED and 4 controls, which gives no power to see a false rate near 1%. The scenarios are synthetic and the arms are `reproduce` containers. The repeats below add power, with their own limits. The sealed test split was never run.

**Repeated runs.** The same 45 scenarios ran three more times on a Linux host with the Linux x64 release executable, which with the first run makes 180. The scenario harness draws its fault from the trial index with a fixed salt per scenario, so a literal repeat would reproduce the same trial sequence and measure only Docker noise. The three repeats therefore add 1,000,003 times the repeat number to each scenario's salt, which gives independent draws at the same failure rate and changes nothing else. A repeat with the original salts was not run.

| run | trials | solve time | CONFIRMED, correct | false CONFIRMED |
| --- | --- | --- | --- | --- |
| first | 1,460 | 1,553 s | 14 | 0 |
| repeat 1 | 1,556 | 1,829 s | 15 | 0 |
| repeat 2 | 1,514 | 1,745 s | 15 | 0 |
| repeat 3 | 1,498 | 1,889 s | 15 | 0 |
| all four | 6,028 | 7,016 s | 59 | 0 |

43 of the 45 scenarios had the same verdict class, and the same minimal set where there is one, in all four runs: the 14 CONFIRMED, the 9 bundle verdicts and 21 STUCK stayed where they were. Two intermittent scenarios changed class: `cpu-limit-quota-basic-2` (STUCK budget, CONFIRMED `cgroup.cpu.max`, STUCK budget, CONFIRMED `cgroup.cpu.max`) and `env-retries-flaky-plain-1` (NARROWED, NARROWED, CONFIRMED `env.MAX_RETRIES`, STUCK budget). In their certificates the 8 failing-baseline trials of the pilot give a different failure-rate estimate in each run, and that estimate sizes the arms: at some estimates no arm of up to 30 trials reaches the target power (STUCK budget), and at others the experiment is SUPPORTED or stays inconclusive at 13 to 21 trials per arm. That the pilot size drives it is inferred from the records, not from reading the code. No control produced a CONFIRMED or a bundle verdict (0 of 16 control runs), no decoy scenario confirmed the decoy (0 of 36 runs), and no bundle verdict lacked the true path or a declared bundle member.

With 0 false CONFIRMED in 180 runs, the exact one-sided 95% upper bound on the per-run rate is 1 - 0.05^(1/180) = 1.65%. For the 16 control runs alone it is 17.1% and for the 36 decoy runs 8.0%. Read the bound per run and not per kind of failure: the 180 runs are 45 scenarios with four draws each, three of the four draws are reseeded and the first is not, the scenarios are synthetic, the arms are `reproduce` containers, and `solve` assumes one cause. The 180 certificates are not in the repository; the four archives in `fixtures/lab/cases` are four of the first 45.

**Migration scenarios.** The four migration dev scenarios were solved later, on the Mac, with the default budget: the Python and JDK scenarios ended CONFIRMED EFFECT (bundle), the first with four members (`env.LANG`, `env.PYTHON_SHA256`, `env.PYTHON_VERSION`, `tool.python.version`) and the second with two (`env.JAVA_VERSION`, `tool.java.version`), both moving failures from 0 of 5 to 5 of 5. The PHP scenario ended NARROWED because one experiment image could not be built (`php:8.3.35-cli-bullseye` does not exist), and the Node scenario ended STUCK because the experiment image build failed (`npm@10.9.0` needs Node 18.17 or newer, and the image has 18.16.0). Both build failures are in `solve`'s image tables and npm pin, not in the benchmark, and were not fixed. The Python template has an intermittent gate that `solve` does not reproduce (it has no per-trial gate), so its red arm fails every time. The probed captures of these runs feed the calibration above; their verdicts do not.

### Flagship Case B: Kotlin on Native and Wasm

Case B asks whether the Lab can isolate a Kotlin divergence that the Atlas found. The shipped archive is `fixtures/cases/b/resume-offset.driftcase` (395,776 bytes, 43 entries, sha256 `d8cadbf2...ca28a3`), and the program is in `fixtures/cases/b/program`.

**Selection rule.** Written down before any outcome was read: among the probes of `atlas/classification.yml` that are `unclassified` and have a repro draft, keep those whose 2.4.20 columns `jvm`, `macos` and `wasm` are not all in one group, and take the smallest draft by byte count, ties by probe id. Three smaller drafts (`numbers.type-checks`, `int.division-modulo`, `regex.groups-replace`) differ only on `cloudflare-jvm`, so the rule passes over them. It picks `kotlin.string.split-replace-lines`, whose one differing line is `indexOf-empty`: `"abc".indexOf("", 10)` is 3 on the JVM, Native and Android and -1 on Wasm, unchanged at 2.5.0-Beta1. Its classification entry and repro draft already exist; the cited Javadoc covers `String.indexOf(String, int)` on the JDK and the Kotlin KDoc gives no rule for a start index past the end.

**The program.** `resume(text, offset, separator)` is `text.indexOf(separator, offset)`, called with a saved offset of 40 into a 17-character text and an empty separator, which stands for "to the end". The program prints `cursor=` and a `FAIL` line when the cursor is outside the text. Built with Kotlin 2.4.20 it prints `cursor=17` on the JVM and on Native (linuxArm64, in a container) and `cursor=-1` with the `FAIL` line on Wasm (Node 24.21.0). The program was written to hit the divergence; nobody met it in the wild.

**Mapping to the Lab.** The environment is a `node:24-bookworm-slim` container on arm64 Docker Desktop. `run.mjs`, the test command, runs the Native build or, when `DRIFT_RUNTIME_PLATFORM=wasm`, the Wasm build. The green capsule and the red capsule were captured inside that container with the Linux arm64 executable, the red one with the variable set, and differ in `runtime.platform` only (`linux`, `wasm`). The failure is a line of output (`--fail-when "FAIL cursor"`). Each platform is a different compiler backend, standard library and runtime, so the platform is one dimension of the capsule and several things in fact; the capsule has no attribute for the rest.

**What the unmodified engine did.** Run on the capsules before any change (the red capsule then also carried `env.DRIFT_RUNTIME_PLATFORM`), `drift solve` returned CONFIRMED on `env.DRIFT_RUNTIME_PLATFORM`. The hypothesis `runtime.platform`, which held half of the prior, was blocked with "no typed intervention changes runtime.platform" and its experiment skipped. The Lab had confirmed the carrier and could not test the platform. Three changes fix that: a `set-platform` intervention that declares `runtime.platform` and that `run.sh` passes as `DRIFT_RUNTIME_PLATFORM`, `reproduce` mirroring a declared (not host-derived) `runtime.platform`, and `capture` no longer repeating the declaration as an `env.` attribute. Tests are in `InterventionTest`, `SolveTest` and `CaptureTest`, and the archive is pinned in `VerifyTest`.

```sh
drift solve passing.json failing.json --run "node run.mjs" --fail-when "FAIL cursor" \
  --workspace stage --drift-binary drift-linuxArm64.kexe --case case --name resume-offset
```

```
CONFIRMED: this dimension causes the failure: runtime.platform. Setting it to the failing values
  moved the failure count from 0 of 5 to 5 of 5 (exact one-sided p 0.003968) and back from 5 of 5 to
  0 of 5 when the failing side was set to the passing values (p 0.003968)
1 candidate changes, 2 experiments run in containers (36 trials, 36 seconds), not on the original
  host.
  p0 pilot: passing baseline 0 of 8, failing baseline 8 of 8, p 0.000077
Mechanism: unexplained (no rule or probe agreed)
```

The measured arm diff, from a capsule captured in each arm, holds `runtime.platform` and nothing else, and the belief in `runtime.platform` went from 0.75 to 0.98. The trial records hold each output line: `cursor=17 length=17` in every passing trial and `FAIL cursor outside the text` in every failing one. `drift pack`, `drift verify`, `drift report` and `drift verify --json` gave byte-identical output on the JVM and on the macOS release executable (`cmp`), and `verify` printed `verify: ok` with 14 checks passing.

**A counterfactual outside the certificate.** Replacing the call with `text.indexOf(separator, offset.coerceAtMost(text.length))`, rebuilding the Wasm module and running it in the same container 8 times gave 0 failures, against 8 of 8 for the original Wasm build. This was run by hand and is not in the archive.

**What it does not show.**

- The certificate's mechanism is `unexplained`. The arms were captured by a Native executable, so the probes in both arms are identical and none could tie the failure to `kotlin.string.split-replace-lines`. The tie to `indexOf-empty` comes from the program's output, the Atlas row and the hand-run counterfactual, none of which `verify` checks.
- The arms differ in which build the script runs, picked from the declared variable. That the variable selects the Wasm build is true by construction. The finding is that running the Wasm build produces the failure; the compiler backend, the standard library and the runtime that make up that build are not separated from each other.
- Failure is 0 or 100 percent here, so five trials per arm is the smallest count that can reach alpha 0.05, and p cannot say more.
- One host, one Node, one Kotlin version, Native on Linux arm64 only, and no JVM arm: the JVM result is a local run.
- The probe stays `unclassified`; the case adds no explanation of why Wasm returns -1, and nothing was filed.

### Flagship Case A: Drangler on the Same Commit

The reported symptom is a Docker end-to-end suite that passes on a developer machine and fails on the GitHub runner. The case compares a local run with a CI run of one commit, `df6dce4`, the head of the three red runs, and not the green runs at an earlier commit. The protocol is `bun run test:e2e:coverage` under `drift run --tools`, in a copy of the tree with the compose images pinned by digest, Node 24.21.0 (the runner's) and bun 1.4.2, on an x86-64 Ubuntu 24.04 host with the suite pinned to 4 CPUs. The CI side is the three frozen red logs. A non-gating `Runner Diagnostic` job in the drangler `e2e.yml` runs the same suite under the Drift action and uploads the capsule and a host probe (CPU steal, cgroup throttling, clocksource, a loopback heartbeat under stall). It needs a Drift release and a push, so it has only been linted with `actionlint`.

The first local run set the premise back. The suite does not pass locally. Over 9 full runs, the two `to-worker.spec.ts` tests that fail in every CI run failed in 6, with the CI signature (`TypeError: fetch failed`, `write EPIPE`, `/rows answered 500`, at the same source lines). They failed when `preview.spec.ts` ran earlier in the same process, as it does on a fresh checkout, and in the one two-file run; they passed in 3 full runs and in 2 runs of `to-worker.spec.ts` alone. The `modify` tests, which fail in CI, passed in all 9. `preview.spec.ts` failed in every local run on a different assertion than in CI. The probe beside three runs saw no CPU steal, no throttling, clocksource `tsc`, a worst event-loop lag of 2 ms and no loopback stall, and those runs failed 3 of 3 against 3 of 6 without it. After `preview.spec.ts` alone no wrangler or workerd process remained, so a leaked process was not what was seen. The macOS run tested nothing: the Docker Desktop disk was full and mariadb stopped with `No space left on device`.

The vacuous-green check, from the frozen logs, shows the two `to-worker` tests passed in 4 and 5 of the 5 green runs. The two `modify` tests and the `preview` file are in no green log. `ruled-out.yml` lists what the logs exclude: no kernel kill, memory, disk or install failure in the three red runs, each with the log line. `drift diagnose --frame environment` finds 61 attribute differences, 1 by value (`tool.git.version`) and 60 present on one side only, because the CI capsule is read from a log and the local one is captured. It ranks 56 and names no cause. The planner's top experiment, `e89`, sets 47 attributes and needs a compose stack its containers cannot start, so it was named and not run.

A Mac run, taken once Docker Desktop had disk again, shows the other half. On an Apple M2 Pro (arm64, Node 24.21.0, bun 1.4.0, the committed `df6dce4` tree, results cache cleared before each run) three full runs failed the `modify` test `requires simple_oauth, uploads simple_oauth_21 and enables it` twice (`Error: Network connection lost.`), as the runner does in 3 of 3 red runs, and `preview` in 3 of 3; the `to-worker` pair passed in all three full runs and in two runs of the file alone. So each local machine reproduces a different half of the CI failure: paisley-park shows `to-worker` (6 of 9 full runs) and not `modify` (0 of 9), and the Mac shows `modify` (2 of 3) and not `to-worker` (0 of 3). Three and nine runs measure no rate. `drift diagnose` of the paisley capture against one Mac capture (both captured, so the diff is two-sided) finds 98 changes, all one bundle with the same support, and names no cause: `runtime.platform` ranks first only through the hand-set weights, because the pair is two operating systems and nearly every attribute differs. That ranking cannot isolate a network or Docker difference, and it gives no support to any experiment. The Mac capsule is from the `mac-r2` run and is not committed.

Verdict: STUCK on the cause. By hand, the premise is narrowed: the failure is not CI-only, and on one host it follows the file order. The cause of the interaction with `preview.spec.ts` is unknown. This does not show why `modify` fails only on the runner, any result on the runner image, or that `preview.spec.ts` causes the failure (ten runs on one host, one of them a two-file run). The fixtures are in `fixtures/cases/a`, and a test pins the case directory.

### The Drangler Case

The drangler case is a CI-versus-local divergence: the reported symptom is that an end-to-end suite passes on a developer machine and fails on the GitHub runner. That report is not recorded in the fixtures. The fixtures are 8 sanitized runs of the Docker end-to-end workflow, all scheduled: 5 passing runs at commit `558d2a6` and 3 failing runs at a later commit, `df6dce4`. The change between the last green commit and the first failing one touches `bun.lock` and `package.json`, and its diff lists, among others, `wrangler` `^4.123.0` to `^4.146.0`, `prettier-plugin-sh` `^0.19.0` to `^0.20.0`, and a new dependency `@napi-rs/keyring`.

What the data shows: the hosted runner image moved from `20260920.314.1` to `20260927.320.1` and the provisioner from `20260828.587` to `20260901.588`, a new `Set up Node` step prints Node and npm versions, and three dependency entries changed (`@napi-rs/keyring` added, `prettier-plugin-sh` and `wrangler` raised), all between the last green and the first red. All eight candidate changes are present in all three red runs and absent from all five green ones. The data cannot separate them.

What the logs add: the first red run reports 3 failed test files and 4 failed tests. Two failed tests are in `to-worker.spec.ts`, which ran with the same 8 tests in every green and passed there; in the reds it ran 8 tests with 2 failed in each. Two are in a `pinned production modules` group of `modify.spec.ts`, a group the greens do not contain (`modify.spec.ts` ran 3 tests in the greens and 8 in the reds, with 2, 1 and 2 failures). The third failed file is `preview.spec.ts`, absent from the greens and present in the reds with its 3 tests skipped. So the greens executed the failing step and ran one of the failing files in full, and did not run the other failing tests, which exist only on the later commit. The fixtures compare the runner at two commits, so they cannot test the reported divergence, which is the same commit on the runner against a developer machine. Only that comparison would separate an environment cause from the new tests. The same-commit comparison is in Flagship Case A above.

The pair diff has 9 static changes. The tool ranks all 9 in one bundle and names none. The order inside the bundle comes from the dimension prior: `tool.node.version` leads because `runtime` has the highest weight, and it is an added attribute from a new step, not a version change. `tool.yarn.version` is also an added attribute; it is ranked because the general `tool.` prefix now maps it to `userland` (before that mapping it had no dimension and the bundle undercounted the changes by one). `ci.provisioner.version` and `ci.provisioner.build-date` are one change counted twice; the planner merges them. Over the run history the same transition is a bundle of the 9 changed attributes and 3 added steps (`Set up Node`, `Post Set up Node` and `Dump Host State on Failure`), and the data does not separate any member of it from the rest: each is present in all three failing runs and in none of the five passing ones.

### Flagship Case C: The Blind List

Case C is built so that nobody chooses it. The candidates are the 14 labelled incidents of the incident survey (`.claude/batch/research/incidents/incidents.md`): public issues or pull requests where one environment passed and another failed and the thread names the difference. The list, the rule and the order were written to `fixtures/cases/c/sealed.json` before Drift ran on any candidate, and its SHA-256 was logged first:

```
d92de5f5462780491b0e30cf4e6c5d0ae34e613c12e6565eb0b0543a4786f408  sealed.json
```

The rule: every labelled incident is a candidate, none is dropped or added; candidates are ordered by ascending SHA-256 of their URL; they run in that order and none is skipped; a candidate that cannot be built is recorded as not reconstructible, with the reason; the flagship is the first reconstructible candidate in the order, whatever verdict `solve` returns. A candidate counts as reconstructible when both sides build in Linux containers from pinned public images or package versions and the failing command exits nonzero on side b and zero on side a. No engine, rule, weight or probe was changed after the seal. `fixtures/cases/c/scripts/` holds the sealer, the capture and solve scripts and the generator of `results.json`; the per-candidate commands, images and hand checks are in `outcomes.json`.

Each reconstructed candidate ran as follows. Both sides were built by hand and the failing command was run on each. `drift capture --tools --probes` ran inside both images with the Linux release executable, and `drift diagnose --frame environment` and `drift solve` (default budget, `--fail-when` the thread's own signature or `exit`) ran on the host. `solve` builds its arms from the two capsules, not from the images, so Drift never saw the pinned tags. `results.json` has one row per candidate, and the six archives that reached `solve` are in the same directory.

| # | candidate | cause key (a to b) | outcome |
| --- | --- | --- | --- |
| 1 | INC-13 prisma on Alpine 3.21 | `os.release.VERSION_ID` (3.20 to 3.21) | CONFIRMED EFFECT (bundle), 12 members, 36 trials |
| 2 | INC-07 webpack on Node 17 | `tool.node.version` (16.13.0 to 17.0.0) | STUCK `executor` (no such image tag) |
| 3 | INC-06 Flutter on JDK 21 | `tool.java.version` | not reconstructible |
| 4 | INC-03 Node on Windows | `tool.node.version` (22.11.0 to 22.12.0) | not reconstructible |
| 5 | INC-10 AdoptOpenJDK on Alpine | `os.release.ID` | not reconstructible |
| 6 | INC-02 pip on Ubuntu 24.04 | `os.release.VERSION_ID` (22.04 to 24.04) | STUCK `not-reproduced` |
| 7 | INC-11 Lombok on JDK 21 | `tool.java.version` (17 to 21) | CONFIRMED EFFECT (bundle), 2 members, 36 trials |
| 8 | INC-09 .NET on a Windows image | `ci.runner.image.version` | not reconstructible |
| 9 | INC-08 Shopkeepers, Turkish locale | `jvm.user.language` | not reconstructible |
| 10 | INC-01 JerryScript, time zone | `env.TZ` | not reconstructible |
| 11 | INC-04 devcontainers/ci, skopeo | `tool.skopeo.version` | not reconstructible |
| 12 | INC-12 prisma on bookworm-slim | `os.release.VERSION_ID` (11 to 12) | CONFIRMED EFFECT (bundle), 4 members, 46 trials |
| 13 | INC-05 ubuntu-latest move | `os.release.VERSION_ID` | not reconstructible |
| 14 | INC-14 iodine on Alpine 3.21 | `tool.cc.version` | STUCK `executor` (no such image tag) |

Of 14, six were reconstructed and run: three CONFIRMED EFFECT (bundle), three STUCK. Eight were not reconstructible. In all three bundle results the sealed cause key is among the members (ranked 2 of 2, 1 of 2 and 3 of 4 by the diagnosis), and none was CONFIRMED on one attribute. The reasons for the eight, from `outcomes.json`: two Windows machines (INC-03, INC-09); a thread that announces an image move and names no single failing command (INC-05); a registry push through buildx that a container pair cannot repeat (INC-04); a macOS Android Studio runtime and the Android SDK (INC-06); a Minecraft server that needs the EULA accepted (INC-08); two cases where the hand check found no failing side (INC-10: both pinned AdoptOpenJDK tags throw the same NullPointerException; INC-01: JerryScript built from the parent commit passes all 306 scripts in four zones, and the `run-tests.py` option sets and test262 were not run).

**The flagship** is INC-13, the first entry in the order: `prisma@6.0.1` on `node:22.12.0-alpine3.20` against `node:22.12.0-alpine3.21`, from [prisma/orm#25817](https://github.com/prisma/orm/issues/25817). The command is `node node_modules/prisma/build/index.js -v` and the failure is a nonzero exit. By hand, the 3.20 side prints `linux-musl-arm64-openssl-3.0.x` and exits 0, and the 3.21 side prints the libssl detection warning and cannot load the query engine. `solve` finished in 73 seconds with `CONFIRMED EFFECT (bundle)`: the arms differ in 12 attributes (`os.release.PRETTY_NAME`, `os.release.VERSION_ID`, `tool.libc.version` and nine component rows for musl, OpenSSL, BusyBox, certificates, tzdata and the bundled npm and corepack), setting the OS release moved failures from 0 of 5 to 5 of 5 (exact one-sided p 0.003968) and back in reverse, and the mechanism field reads `unexplained`. Only `os.release.VERSION_ID` can be set on its own, so no arm splits the bundle. The archive `INC-13.driftcase` (179,712 bytes, SHA-256 `b4b20bdcb4d3c5e44687564c570fc0be77de505e84607a936720ec18def65aeb`) was packed by the JVM build and by the macOS executable with identical bytes, and `verify` and `report` output is identical on both. `VerifyTest` pins the hash, verifies the archive and checks that repacking reproduces it, and pins `sealed.json` and the ordering rule.

What the flagship does not show. The verdict says an OS release change is enough, not why. The thread proposes that libssl moved from `/lib` to `/usr/lib`; no arm tests that. The arms were not captured (the Linux binary is glibc and cannot run on Alpine), so the 12 members come from the list of what the base image changes, not from a measured arm diff. Capture inside the Alpine images needed `apk add gcompat`, so the two capsules describe the images plus that package. The pin of `prisma@6.0.1` is the latest release on the issue date and the Node version is held at 22.12.0 on both sides; neither is stated in the thread.

**The losses.** Three results are failures of reconstruction by `solve`, not of the diagnosis, and each is a defect in the engine that this protocol did not fix:

- INC-07: the capsules say Debian 10 (`node:16.13.0` is a buster image), and the image table in `ImageTable.kt` maps only Debian 11 to 13, so the arm is `node:16.13.0-bookworm-slim`, which does not exist. The diagnosis ranks `tool.node.version` first of 9 candidates, but `solve` stops at the first build. A variant run after the seal, with `node:16.20.2` and `node:17.9.1` (both Debian 11), failed the same way on the treatment arm (`node:17.9.1-bookworm-slim`); it is not a row of the table.
- INC-14: the command `gem install` names no toolchain, the capsule has a compiler, and the arm is `gcc:13.2.1`, a tag that does not exist (Alpine's patched GCC). The diagnosis ranks `tool.cc.version` first of 3.
- INC-02: the arm is `python:3.10.12-slim-bookworm`, the official Python image, which has no `EXTERNALLY-MANAGED` marker, so the failure does not appear in 0 of 8 trials. The cause is how the distribution packages Python, and a toolchain image cannot mirror it. The sealed cause key ranks 6 of 7.

Two limits apply to all six runs: the arms are containers built from capsules, not the original hosts, and the trials ran on a Mac under Docker Desktop (linux/arm64) for everything except the paisley-park checks of INC-10 and INC-01. The sealed order was not used to tune anything; the flagship was fixed by the rule before a result existed.

**Post-fix rerun.** This is not part of the sealed protocol (`fixtures/cases/c/post-fix.json`, with `sealed.json` and the sealed archives untouched). After the image-selection fixes (Debian 10 maps to a buster tag, GCC tags are pinned to major and minor, Alpine arms install `gcompat`) and the manifest record of the packages an arm adds, INC-13 was rerun from its sealed capsules and reproduced the sealed verdict: CONFIRMED EFFECT (bundle), the same 12 members, 36 trials, exit 10, with solve output identical except for the timing line. INC-07 now builds both arms (`node:16.13.0-buster-slim` and `node:17.0.0-buster-slim`) and ends CONFIRMED EFFECT (bundle) with two members, `env.NODE_VERSION` and `tool.node.version`: failures went from 0 of 5 to 5 of 5 (exact one-sided p 0.003968) and back from 5 of 5 to 0 of 5 in reverse, in 36 trials. INC-14 now resolves its base to `gcc:13.2` (linux/arm64) and the arms build, but the passing configuration fails as often as the failing one in the container (8 of 8 against 8 of 8), so the verdict is STUCK with 3 candidate changes and no experiment run. None of these rows replaces the sealed flagship result, INC-13.

### Flagship Case D: A Migration Predicted, Then Run

Case D uses Drift the way a team would before a migration: capture the project on the old stack, ask `drift upgrade-check` what the move to the new stack breaks, freeze the answer, run the project's own tests on the new stack, and score the answer against the failures. The project was chosen by a rule, not by hand. The rule, a pool of 12 libraries from the first 60 results of the GitHub search `language:php stars:1000..8000 archived:false fork:false`, the pin (the latest stable tag before 2021-07-01) and the order (ascending SHA-256 of the repository URL) are in `fixtures/cases/d/selection.json`, whose SHA-256 was logged before any capture or test ran:

```
24d17eff3383eb6c3ee5dada258620edefa909bd19a34866c8118602f097846d  selection.json
```

The first candidate, `sebastianbergmann/comparator` 3.0.3, is not reconstructible: on PHP 7.4 the newest `phpunit/phpunit` 8.5 requires `sebastian/comparator` 3.0.7 or newer and the root package is 3.0.3. The second, `lcobucci/jwt` 4.1.4 (`71cf170`), installs from its committed lock and passes 280 tests (522 assertions) on PHP 7.4.33, so it is the case. Composer 2.10.3 refuses the 2021 lock without an `allow-plugins` block, so both stacks retry once with `--no-plugins`.

`drift capture --project --tools` ran in a PHP 7.4.33 container with the Linux arm64 executable, and `drift upgrade-check <capsule> --to php=8.3` ran with the macOS executable. The prediction (11 will-break, 1 may-break, 0 unknown), the scoring protocol with its message patterns, the rule tree and both executables were hashed together at 08:52 UTC (`prediction.sha256`) before the PHP 8.3 container ran. The protocol fixed the install order on the new stack: the lock, then `composer update`, then `--ignore-platform-reqs`.

| measure | result |
| --- | --- |
| Predicted | 11 will break, 1 may break |
| True positives | 0 |
| False positives | 12 |
| Misses (new failing tests unattributed) | 0 |
| PHP 7.4.33 baseline | 280 tests, 522 assertions, pass |
| PHP 8.3.35, install step 2 (`composer update`) | 280 tests, 522 assertions, pass; PHPUnit 9.5.0 became 9.6.38 |
| Same suite with deprecations, notices and warnings made fatal | pass |
| Supplementary, lock held with `--ignore-platform-reqs` (not scored) | fatal error after 63 of 280 tests |

The score is a loss for the catalog, and the table understates why. All 12 rules fire on the version crossing alone; none reads a source fact, and the capture had none of the APIs they name (`create_function`, `each`, `implode` with reversed arguments, mysqli, PDO). What actually broke the move was not predicted. `composer install` from the lock stopped on `phpspec/prophecy` 1.12.2, which caps PHP below 8.1. With the lock held, `mikey179/vfsstream` 1.6.8 stopped the run with a fatal error because `getIterator()` lacks the PHP 8.1 tentative return type. `composer update` hid that second break by moving 60 packages. No rule in the frozen catalog covered a locked package with a PHP upper bound or a tentative return type, although the capture held both package versions. The scored run is therefore a result for "PHP 8.3 with whatever Composer resolves", not for the dependency set of the pin, and a suite that passes there does not show that the old dependencies would.

The group split that followed (see Upgrade Check) rerun on the same capture gives 0 will break, 1 may break and 11 version boundary, recorded in `fixtures/cases/d/post-fix.json`; it changes the wording of the 11 and not whether they were failures. Limits: one project, one outcome, so no precision or recall figure holds; the pass is the PHPUnit exit status of the default configuration, and the deprecation scan only covers what PHPUnit's handler sees; a deprecation that PHPUnit swallows would not count as a failure under the protocol; Docker Desktop on one Mac ran every stack (linux/arm64). Rules suggested by the result are in `fixtures/cases/d/follow-up-rules.md`; none was applied, and `rules/` did not change between the frozen prediction and the score. `VerifyTest` pins the selection hash, checks the order and the score against the prediction. After the score, `php-8-1-vfsstream-below-1-6-10-tentative-return` was written from the vfsStream source at each tag (the attribute arrives in 1.6.10). A labelled rerun with it, `fixtures/cases/d/post-fix-p3-rules.json`, gives 1 will break (vfsStream), 2 may break and 11 version boundary. The rule came after the outcome, so the rerun reproduces the break rather than predicting it; the frozen score above is unchanged. The `phpspec/prophecy` upper bound still has no rule.

### Threats to Validity

- The benchmark is synthetic. The injected cause is ground truth because the scenario was built around it. Real failures have causes that nobody injected and that may lie outside any capsule.
- Intermittency is a hash gate on the fault, so no scenario contains a real race, and five scenarios carry capsule values that were written in and not measured.
- The 31 sealed scenarios were never run. Their validity is inferred from dev siblings.
- Weights are hand-set. The ranking weights, the planner's cost weights, its 25% unknown prior and the trial-plan thresholds are guesses with a written reason, and none was calibrated.
- Rule sources are cited and not reproduced. Rule fixtures are synthetic. 230 of 298 rules have a symptom that rests on the mechanism alone (233 of 319 symptoms are `inferred`), and 91 rules wait for an attribute no scanner emits.
- Rule matches on real data are few. Seven dev scenarios and one real project (case D, where the 12 matched rules predicted breaks that did not happen) are the only matches, and the Drangler pair matches no rule. Real multi-rule captures were not tested.
- A probe links to a candidate through the attribute path it reads, a table that is a reading of the probe sources and not a measurement, and the link ignores the symptom. A differing probe supports a decoy that really changed as much as the cause.
- `correlated` needs the history to carry step data. Without the run JSON joined to the logs, every green's steps are unknown, all five are excluded as `NO_STEP_EVIDENCE`, and all eight drangler candidates drop to `difference` (`withoutStepEvidenceInTheGreensTheDranglerFallsBackToDifferences`).
- The history ranking assumes the failing runs share one failure signature; the caller must group them.
- Atlas cells are single runs, and the 16 columns come from five machines and two CPU families, so a column describes a machine as much as a target (see Platform and CPU Findings). The Wasm Node column was recorded on Apple Silicon and the browser columns on x86-64, which confounds engine and CPU. Linux Native columns depend on the host that linked the executable. The displayed source of a probe and the code that runs are two hand-written copies; the ladder measured 1 of 38 probes differing between them, by a class name. Probe classification rests on the pages the authors read, with at most the first 8 tracker results of each query; Native's Unicode table version and whether Native and Wasm call libm or the JS engine for math are unverified.
- No workflow has run on GitHub: `build.yml`, `atlas.yml`, `release.yml` and `docs.yml` are checked with `actionlint` and their steps ran by hand. The Linux test suites ran in containers and the mingw ones under Wine, which is not Windows; real Windows ran nothing (no test, no installer, no registry edit), and neither did Safari or a signed or notarized build. The Linux arm64 executable was cross-linked, and a Kotlin/Native build on an arm64 Linux host was not run.
- Capture removes the account name, the host name and the home path only where they appear in the capturing host's own `env.*`. Probe transcripts and labels are not rewritten, and emails and account ids are not recognized. `--keep-identifiers` keeps everything.
- The Lab ran each of the 45 dev scenarios four times (180 runs, three reseeded). 0 false CONFIRMED bounds the per-run rate at 1.65% with 95% confidence on these synthetic scenarios and says nothing about real failures; an exact repeat of the original draws was not run. The loop needs two `SUPPORTED` tests to confirm (forward and reverse), which makes a false CONFIRMED on a decoy rare by design (about 0.25% at alpha 0.05 if the two tests are independent), but that figure is arithmetic and was not measured.
- The pilot of 8 trials sizes the arms, and for intermittent scenarios its estimate moved 2 of 45 scenarios between STUCK, NARROWED and CONFIRMED across four runs. A larger or sequential pilot was not tested.
- The arms are `reproduce` containers, not the scenario's own: they differ in an apt locales layer, image tags and the missing `--network none`. The harness that ran the dev scenarios added a wrapper that applies the red arm's fault gate to both arms, so a scenario whose cause a container cannot vary fails in both arms, which is what a container can show.
- `solve` assumes one cause, runs locally only, retries an infrastructure error once, never repeats an `INCONCLUSIVE` experiment, and caps split arms at 3. It has no CI mode. Its `--fail-when` pattern uses the regex semantics of the target it runs on.
- When `--run` names no toolchain, `reproduce` takes the first of its image table, so a container for a C compiler scenario with a distribution-bundled Python picked a Python tag that does not exist; the run stopped with an executor error and no verdict. The evaluation names the toolchain in the first word of `--run`.
- `verify` shows that an archive agrees with itself. It does not show that the experiments ran, and a consistent rewrite of every file verifies. `ranking` and `plan` are re-derived for pair cases only. The `verdict` rules it applies are the loop's own, read back.
- The four `.driftcase` fixtures have no `reproduce/` directory, and the Windows path handling of `pack` and `verify` and the Linux executable running them were not tested.
- `upgrade-check` has one scored real case. Its predictions in case D were 0 true positives and 12 false positives, and what broke the move had no rule. The rules that read a call or an import (added-API rules) fire for a runner older than the developer machine, not for an upgrade.
- The triage extractor is scored on form-labelled issues of large trackers, with the form sections as truth. Recall is 15% by construction, small repositories have 9 scored facts, and the claim check flags none of 67 labelled threads. The corpus holds third-party text that is not in the repository, and publishing it is a decision that has not been made.
- The calibration files were regenerated after the dev split grew from 45 to 49 scenarios and the catalog from 32 to 296 rules. The earlier claim that the order at top-3 is indistinguishable from chance does not survive that change, and the bundle claim got weaker (6 of 11 where it was 8 of 8). The sealed 31 are still unrun, so none of these is a held-out result.
- The Mac performance rows were taken at a load of 5.7 to 5.9, the build under test has since grown by 2.6 MB, and nothing was timed after that.

### Tests

Counts from the test result files of the last run of each task (`*/build/test-results/*/*.xml`), read on 2026-10-13 after the final clean build:

| module | JVM | macOS arm64 Native | Wasm on Node |
| --- | --- | --- | --- |
| `core` | 639 | 639 | 639 |
| `scan` | 152 | 152 | 152 |
| `bench` | 108 | 76 | 76 |
| `cli` | 335 | 311 | 290 |
| `host` | 37 | 40 | 31 |
| `studio` | 114 | - | 69 in Chrome |
| `tools` | 82 | - | - |
| `build-core` | 101 | - | - |

Every count has 0 failures and 0 skipped. The platform columns differ for `host` and `cli` because some tests exist only for the targets that can write files, run commands or open sockets, and for `bench` because the YAML, Docker and binomial tests are JVM-only. The Lab's tests run in common code on all three targets: `SolveTest` 29, `IngestTest` 10, `ArchiveTest` 18 and `VerifyTest` 23 in `core`, and `SolveCommandTest` 9, `ArchiveCommandTest` 12 and `DockerExecutorTest` 7 in `cli`. Other new domains: `TriageTest` 39, `LearnedTest` 6, `CalibrateTest` 7, `UpgradeCheckTest` 19, `ContractTest` 21 in `core`; `TriageCommandTest` 16, `UpgradeCheckCommandTest` 9, `ContractCommandTest` 14 in `cli`; `ProjectScannerTest` 33 in `scan`; `CommandLineTest` 14 and `HostRunTest` 7 in `host`. They run against a fake executor; no test builds an image or starts a container. The installer tests (classes with `Install` in the name) number 64 on the JVM, 49 on macOS Native and 43 on Wasm, and the serve tests 51, 50 and 37.

---

## Reproducing the Numbers

Build and gates, from the repository root with JDK 25 (the build's toolchain here is Temurin 25):

```sh
./gradlew build spotlessCheck checkLineLength
./gradlew jvmJacocoAggregateReport verifyCoverage coverageSummary
./gradlew sharedRatio
./gradlew :cli:linkReleaseExecutableMacosArm64
./gradlew atlasSite
```

`coverageSummary` writes `build/coverage/summary.txt`, which holds the coverage table and the per-module test counts; `sharedRatio` prints the shared-code table. The CLI examples above run the linked executable, `cli/build/bin/macosArm64/releaseExecutable/drift.kexe`. A JVM run is `./gradlew :cli:jvmRun --args="..."`. The figures here were read on 2026-10-13 on a Mac from the executables linked by the final `./gradlew build`.

The sealed split and the dev rule, recomputed from the scenario list:

```python
import json, hashlib
ids = [s["id"] for s in json.load(open("bench/data/scenarios.json"))]
dev = {i for i in ids if int.from_bytes(hashlib.sha256(i.encode()).digest()[:4], "big") % 100 < 60}
test = sorted(set(ids) - dev)
print(len(dev), len(test), hashlib.sha256("".join(i + "\n" for i in test).encode()).hexdigest())
```

This prints `49 31 457097f88491381de17a43a67a7f09ab3843d008c838f02db2e81b0c6d5b4068`.

| figure | value | command or file |
| --- | --- | --- |
| core tests per target | 639, 639, 639 | sum of `tests` in `core/build/test-results/<task>/*.xml` |
| other module test counts | table in Tests | same files, one directory per task |
| shared-code ratio | 90.7% (27,341 and 2,798 lines); `host` 344 and 622 (35.6%) | `./gradlew sharedRatio` |
| JVM line coverage | 96.0%, 20,448 of 21,293 | `./gradlew jvmJacocoAggregateReport verifyCoverage coverageSummary`, then `build/coverage/summary.txt` |
| no `Double` or `Float` in common code | 0 | `grep -rn "Double\|Float" core/src/common/main bench/src/common/main` |
| fixed-point and hash goldens | table in Design Principles | `core/src/common/test/.../GoldenTest.kt` |
| `drift diagnose` hash | `4b0dd023...ff8a` | `drift diagnose fixtures/drangler/capsules/36702683742.json fixtures/drangler/capsules/37114464625.json \| shasum -a 256`; `DriftCommandTest.DIAGNOSE_SHA` |
| capsule hash in `reproduce` | `540e5c22...0531` | `drift reproduce fixtures/lab/node-alpine.json --run "npm test" --out out`; equals the sha256 of the file without its final newline |
| case files match the pinned hashes | 11 of 11 | `drift diagnose ... --case case`, then `shasum -a 256` per file against `CaseCommandTest.CASE_SHAS` |
| `rule sha256` of the drangler case | `8bf205db...4559` | `drift case show case`, `drift next case --detail detail` |
| CLI size and commands | 9,866,776 bytes; 21 subcommands | `ls -l cli/build/bin/macosArm64/releaseExecutable/drift.kexe`, `drift --help` |
| capture counts (90, 117, 71) | this Mac | `drift capture`, `drift capture --tools`, `drift capture --probes --tools` |
| masked and identifier-bearing env attributes | 13 masked of 81; with `--keep-identifiers` 22 hold the account name and 20 the home path; by default 0 and 0 | `drift capture` and `drift capture --keep-identifiers`, then count `env.*` values equal to `<redacted>`, containing the account name, containing `$HOME` |
| capture stdout bytes | strict JSON parse succeeds; 1 raw U+0085 and 1 raw U+2028 | `drift capture --probes --tools > c.json; python3 -c "import json; json.load(open('c.json'))"`; count the UTF-8 bytes of both code points in `c.json` |
| rules, status, fixtures, sources, severities | 298; 207 active and 91 pending-scanner; 913 (303/610); 338 entries, 210 distinct; 85/166/47 | `find rules -name '*.yml' \| wc -l`; `grep -rh '^status:' rules --include='*.yml' \| sort \| uniq -c`; `grep -rh '^severity:' rules --include='*.yml' \| sort \| uniq -c`; `- name:` under `fixtures:` and `kind:` under `provenance:` per file |
| rule symptom basis | 86 verified, 233 inferred; 230 rules | `grep -rh "basis:" rules --include="*.yml"` |
| transcript fixtures | 77 files, 23 tool folders | `find fixtures/transcripts -type f`; `ls fixtures/transcripts` |
| probes | 71 = 33 + 38 | `drift capture --probes` |
| probe links | 33 entries | `core/src/common/main/.../rank/ProbeLinks.kt`; `ProbeTest.everyCatalogProbeHasALinkEntryNamingRealAttributes` |
| Atlas, 16 columns | 38 probes, 32 divergent, 6 identical, sha256 `03146e65...4aef` | `drift atlas build fixtures/atlas/*.txt --out atlas16.json` |
| Atlas, 21 columns | 32 divergent (2.4.20) and 18 (2.5.0-Beta1), 658,855 bytes, `2d7e84b9...d3af` | `drift atlas build fixtures/atlas/*.txt fixtures/atlas/2.5.0-beta1/*.txt --out atlas.json` |
| Atlas, 3 columns | 23 divergent, 734 lines per column, 107 differing lines | `drift atlas build fixtures/atlas/jvm.txt fixtures/atlas/macos.txt fixtures/atlas/wasm.txt --out atlas3.json`, then count `cells[].lines` that differ across columns; 340 over the 16 columns of `atlas16.json` |
| Atlas ladder | 74 columns, 2,812 cells, 34 divergent, 4 identical, `f238d8e4...f2f6` | `./gradlew atlasLadderDataset`, or `drift atlas build fixtures/atlas/*.txt fixtures/atlas/2.5.0-beta1/*.txt $(ls fixtures/atlas-ladder/*/*.txt \| grep -v -e 2.4.20/linux.txt -e 2.4.20/js-ir.txt) --out ladder.json` |
| classification | 10 / 3 / 25; 4 / 3 / 25 among the 32 divergent on 2.4.20 | `summary.byClassification` in `atlas.json`; `drift atlas show atlas.json` |
| ladder matrix | table in The Kotlin Portability Atlas | count `ok` cells per (version, target) in `ladder.json` |
| Beta convergence | 5 probes identical; 5 more changed in the Wasm column | compare cell hashes per probe between `2.4.20` and `2.5.0-Beta1` in `atlas.json` |
| this device against the dataset | 38 own, 38 iOS, 37 Linux arm64, 36 Linux x64, 21 JVM, 25 Wasm, 11 `js-ir`; 37 for `macos@2.5.0-Beta1` | `drift atlas record --out t; drift atlas compare atlas.json t/macos.txt` |
| macOS transcript equals fixture | identical | `cmp t/macos.txt fixtures/atlas/macos.txt` |
| column pairs | `linux` and `linux-ubuntu` 0 probes; `linux` and `macos` 2; `linux` and `linux-arm64` 1; `jvm` and `jvm-21` 3; `jvm-17` and `jvm-21` 6; `jvm-21` and `jvm-25` 2; each browser and `wasm` 1 | compare the `@@ <probe id>` sections of the files in `fixtures/atlas` and `fixtures/atlas-extra` |
| value changes on the ladder | `hello` 604317365 to 99162322; `boolean` 1 to 1231; JS IR `Long` | `diff` of the probe sections of `fixtures/atlas-ladder/1.4.32/linux.txt` and `1.5.32/linux.txt`, `1.8.22` and `1.9.25`, `js-ir` `1.5.32` and `1.6.21` |
| Atlas site | 79 files, 18,548 KiB, 39 probe pages, no `<script>` on a probe page | `./gradlew atlasSite`; `find build/atlas-site -type f \| wc -l`; `du -sk build/atlas-site`; `shasum -a 256` of every file, then `./gradlew atlasSite --rerun` and again |
| Dokka site | 3,827 pages; 132 unresolved relative links inside `engine/` | `./gradlew :dokkaGenerate`; count `*.html` and unresolved relative `href` targets in `build/dokka/html` |
| Documentation site | 3,969 files, 68,944 KiB; 95,013 relative links, 0 unresolved outside `engine/`, 132 inside it | `./gradlew docsSite`; `find build/site -type f \| wc -l`; `du -sk build/site`; the `checkSiteLinks` output |
| DriftBench | 80 scenarios, 43 templates, 49 dev, 31 sealed | `bench/data/scenarios.json`, `bench/data/split.json` |
| dev validation | 49 of 49, 2,580 trials, 978 red failures, 0 green failures | `bench/data/results/*.json` (`valid`, `arms.*.trials`, `arms.*.failures`) |
| order table, three inputs | 21, 23 and 26 of 45 top-1 for hand-set (probed, probes removed, original) | `bench/data/calibration/evaluation.json` (`original`, `probes_removed`, `probed`: `models[id=hand-set].in_sample`); Wilson 95% intervals computed from the counts |
| exact tests against a random order | P = 0.018 (probed), 0.001, 0.003 at top-1; 0.009, 0.009, 0.012 at top-3 | `evaluation.json` `versus_random.top1.p_at_least_micro`, `.top3` |
| probe effect | 2 scenarios move down (`env-pool-size-decoy-2`, `migration-python-copy-1`); none improves | compare the `ranked` order of `env-pool-size-decoy-2` and every other scenario in `probed` and `probes_removed` |
| calibration files | `evaluation.json` sha256 `4effe497...e374`; `fit.json` `fbbb6f45...4a31`; regenerated and byte-compared by `CalibrationTest` | `shasum -a 256 bench/data/calibration/*.json`; `./gradlew :bench:jvmRun --args="calibrate --root $PWD/bench"` |
| tiers and false confidence | 71 / 25 / 3; 10 of 49; 14 of 25 `plausible` wrong | `evaluation.json` `probed.scenarios[].ranked[].tier`, `.cause` |
| probe ratio sweep | 0.170 at ratio 1 to 0.218 at 6; 0.187 for "one of n" | `evaluation.json` `probe_sweep` |
| readout Brier, covered rankings | 0.166 shipped; 0.179 uniform; 0.146 against 0.160 on all rankings | `evaluation.json` `probed.models[].cross_validated.covered.brier` |
| none weight, fit provenance | 0.25; seed 20261006 | `fit.json` `probability.none`, `provenance`; `Weights.NONE`, `Weights.FIT` |
| dev inputs | 99 candidates, 29 causes, 14 scenarios with a differing probe, 7 scenarios with a rule match | `fit.json` `provenance.inputs.probed` |
| drangler pair ranking | 9 candidates, bundle-1, probabilities 0.129032 and 0.107527 | `drift diagnose ... --detail full` |
| drangler history ranking | 9 candidates, `ef 3, nf 0, ep 0`, coverage 862,068 | `RankHistoryTest.theDranglerTransitionIsOneBundleNoCandidateIsTheCause` |
| failing test files in the logs | `to-worker` 8 tests (greens) and 8 with 2 failed (reds); `modify` 3 and 8; `preview` 3 skipped in reds | `HistoryFixtureTest.theStepRanInEveryGreenButTheFailingTestsAreNotAllInThem`; `fixtures/drangler/logs/*.log` |
| planner, 11 + 2 experiments, EIG 0.499 bits | e11 first, 157 cost points | `drift next case --detail full`; `case/experiments/plan.json` |
| Fisher and power | 35/1001 = 0.0350; 3 of 7: 0.0962; 1808/2187 = 0.8267 | closed forms: `C(7,4) / C(14,4)`, `C(7,3) / C(14,3)`, `sum C(7,k) (2/3)^k (1/3)^(7-k)` for k = 4 to 7 |
| EIG recomputed | 0.49854 bits, P(reproduced) 0.490447 | prior masses in `plan.json`, power 1808/2187, alpha 0.05 |
| `reproduce` counts | 23 attributes: 10, 4, 9 | `drift reproduce fixtures/lab/node-alpine.json --run "npm test" --out out` |
| `reproduce --force` | replaces three own files; refuses a foreign `Dockerfile` | run the command twice, then once more after `printf 'FROM x\n' > out/Dockerfile` |
| tampered case | exit 1, chain broken at entry 1 | replace `drangler-36995781138` with `drangler-X` in `observations/0002.json`, then `drift case show case` |
| Lab fixtures, archive bytes | 4 archives, 50,176 / 58,368 / 43,008 / 31,744 bytes | `ls -l fixtures/lab/cases`; extract each with `tar -xf`, `drift pack <dir> --out x`, `cmp x <fixture>` |
| Lab, JVM against native | identical archives, `report.html` and `verify` output | `./gradlew :cli:jvmRun --args="pack <abs dir> --out <abs file>"` and the native `drift pack`, then `cmp`; same for `report` and `verify` |
| `verify`, 14 checks | `verify: ok` for the four fixtures | `drift verify fixtures/lab/cases/*.driftcase` |
| tamper detection | first failure `manifest`, then `results-chain`, `counts`, `fisher` | the `python3` edit in The Lab, then `drift verify bt.driftcase` |
| `tar` interoperability | 19 entries; 18 manifest hashes match | `tar -tf`, `tar -xf`, then sha256 of each file against `manifest.json` |
| Lab dev evaluation, first run | 14 / 9 / 1 / 21 verdicts; 1,460 trials; 0 wrong CONFIRMED | the 45 `certificate.json` files (not in the repository) from `drift solve` on a Linux host; truth from `bench/data/scenarios.json` (`expected.path`, `expected.bundle`); the four archives in `fixtures/lab/cases` are 4 of them |
| Lab repeats | 180 runs, 59 CONFIRMED correct, 0 false CONFIRMED, 6,028 trials, 7,016 s; 2 of 45 scenarios changed class | the 135 further `certificate.json` files (not in the repository), same truth file; the bound is `python3 -c "print(1 - 0.05 ** (1 / 180))"`, 0.016505 |
| 45 cases pack and verify | 45 of 45 exit 0; 180 of 180 on three executables | `drift pack <case> --out x.driftcase && drift verify x.driftcase` per case directory |
| `--exit-status` | 0, 10, 11, 12 for the four verdicts | `drift solve --help`, `drift ingest --help`; `SolveCommandTest` |
| CLI release archives | 2,645,407 / 2,793,616 / 2,458,436 / 2,781,268 bytes | `.github/release/stage.sh archive <exe> <name>` in an empty directory, then `stage.sh sums dist` and `shasum -a 256 -c SHA256SUMS` |
| installer script checks | 33 ok, 0 failed on the Mac | `sh tools/test-installers.sh cli/build/bin/macosArm64/releaseExecutable/drift.kexe <scratch dir>` |
| Studio installers | dmg 74,624,330; pkg 70,015,031; APK 7,203,057 | `ls -l studio/build/compose/binaries/main/{dmg,pkg} androidApp/build/distributions` (Linux packages: `./gradlew :studio:packageDeb :studio:packageRpm :studio:packageAppImage` in a Linux container) |
| `serve` checks | listeners `127.0.0.1` and `[::1]`; 403, 400, 404, 405 as listed; exit 0 on SIGINT | `drift serve --dir cli/build/serve/studio --port 18771`, then `lsof -a -p <pid> -iTCP -sTCP:LISTEN`, `curl` with `-H "Host: ..."` and `--path-as-is` |
| `serve` embedding sizes | 493,368 / 12,588,056 / 33,913,192 bytes | executables of a trivial program that embeds the 12.4 MB build as a gzip base64 string and as a raw base64 string; not in the repository |
| triage, held-out | 1,376 facts, precision 0.783, Brier 0.1486; 1 claim hit in 222,707 bodies | `./gradlew :tools:evalTriage -Pdrift.corpus=<corpus.json.zst>` (needs the corpus file) |
| triage fixtures | 70 bodies, 189 facts, precision 0.989, recall 0.979, Brier 0.0851 | `TriageTest` |
| upgrade-check | 0 / 7 / 2 / 0 on the Drupal capture, 73 of 298 rules; case D 1 / 2 / 11 / 0, 46 of 298 | `drift upgrade-check fixtures/upgrade-check/drupal-10-php-8-1.json --to php=8.3,drupal=11,symfony=7.1`; `drift upgrade-check fixtures/cases/d/capture-php-7-4.json --to php=8.3` |
| performance | the table in Performance | `tools/perf/results/SUMMARY.md`; `python3 tools/perf/summarize.py` |
| Linux and mingw test runs | 730 tests each on Linux x64 and arm64; Wine counts as listed | `./gradlew :core:linkDebugTestLinuxX64` (and the other modules), then run `<module>/build/bin/linuxX64/debugTest/test.kexe` in a Debian container; `linkDebugTestMingwX64` and `wine64 test.exe` |
| browser transcripts | byte-identical over 4 runs per engine | `node tools/browsers/run.mjs --engine chromium --headed --dist studio/build/dist/wasmJs/productionExecutable --out out` (under `xvfb-run` in the Playwright image); `cmp` between runs |
| studio tests | 114 JVM, 69 browser, 0 failed | `studio/build/test-results/*/*.xml` |

The Kotlin 2.5.0-Beta1 transcripts were recorded in a copy of the repository with `kotlin = "2.5.0-Beta1"` in `gradle/libs.versions.toml`. The repository pin is 2.4.20. To record again, change the pin, run `drift atlas record --out DIR --target LABEL` on each target, and keep the transcripts under `fixtures/atlas/<version>/`; the Wasm transcript needs a test-driven recording because the Wasm CLI has no file writer.

---

## References

Tracker metadata was read through the YouTrack REST API on 2026-10-06 (title, state, resolution date and affected versions; descriptions of KT-61028 and KT-44746).

- KT-88414: Wasm floating-point parsing and `toString` differ from JVM and Native. State Fixed, resolved 2026-08-30.
- KT-89072: Kotlin/Native `1.0.pow(NaN)` returns 1.0 where the contract says NaN. State Open.
- KT-89074: Kotlin/Native and Kotlin/Wasm `roundToInt` and `roundToLong` do not follow the contract. State Open.
- KT-44746: Different `hashCode()` results for Kotlin/Native strings. State Fixed, resolved 2021-02-09.
- KT-61028: Behavioral changes to the Native standard library API, including `Boolean.hashCode()` returning 1231 and 1237. State Fixed, resolved 2023-08-07.

The Atlas classification cites more: each entry in `atlas/classification.yml` lists the Kotlin API pages, JDK and Android Javadoc pages, ECMAScript and WebAssembly sections, JLS sections, tracker issues (for example KT-58195, KT-69456 and KT-78708) and the JDK 19 release note JDK-4511638 that its authors read, with a short quote and, for an issue, the state read on the research date. The ids are not repeated here.

Rule sources are listed per rule in the `provenance` of `rules/**/*.yml`. The Atlas probe notes cite the Kotlin API pages, the JDK 21 javadoc pages and the ECMAScript sections their authors read. The Ochiai formula and the delta debugging algorithm are implemented from their standard definitions; the original papers were not opened for this report.
