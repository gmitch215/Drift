# Technical Report: Drift

Drift is a Kotlin Multiplatform command line tool and desktop, web and mobile app, version 1.0.0. It records an environment as a capsule, compares two capsules, ranks what changed between a passing run and a failing one, proposes the experiments that would separate the candidates, and writes a container description that mirrors a capsule. Its Lab runs those experiments in containers under a rule fixed before the first trial, reports a verdict, and packs the evidence into an archive that a second command re-checks. It also ships two measured data sets: the Kotlin Portability Atlas, a table of Kotlin behavior across targets and compiler versions, with a static site and a viewer in the app, and DriftBench, a seeded benchmark of container pairs with an injected cause. The release pipeline builds a CLI archive per platform, consumer installers for the app, a Homebrew formula and cask, and two Chocolatey packages.

This document is the engineering reference for what the working tree does today, what it costs, and what was measured. Each table names its instrument. The section Reproducing the Numbers lists the command or file behind every figure. Test counts, line counts and coverage describe the tree on 2026-10-07, read between 05:07 and 05:38 local time, and move with it. Nothing described here has run on GitHub: every workflow is checked with `actionlint` and its steps were exercised by hand on a Mac and on a Linux host, and no Windows machine was available.

---

## Summary

| | measured | instrument |
| --- | --- | --- |
| Targets built | 6: JVM, macOS arm64, Linux x64, Linux arm64, Windows x64 (mingw), Wasm on Node; `-Pdrift.mobile=true` adds Android and the iOS simulator for the app | `drift.kmp` plugin |
| Test runs in the tree | JVM, macOS arm64 Native and Wasm on Node by Gradle on a Mac; Studio in Chrome by Gradle and in Firefox by Karma; Linux x64 and Linux arm64 Native in containers; mingw under Wine (not Windows) | `*/build/test-results/*/*.xml`, container logs (see Platform and CPU Findings) |
| Core tests, each of the 3 Gradle targets | 492 passed, 0 failed, on every target | `*/build/test-results/*/*.xml` |
| Shared-code ratio, all modules | 88.4% common lines (18,824 common, 2,470 platform); `core` and `scan` 100%, `cli` 62.4% | `./gradlew sharedRatio` |
| JVM line coverage | 95.1% (13,582 of 14,281 lines); floor 95% | `build/coverage/summary.txt` |
| `core` and `bench` common code using `Double` or `Float` | 0 occurrences | `grep -rn` |
| macOS arm64 CLI, release executable | 6,742,776 bytes; 14 subcommands | `ls -l`, `drift --help` |
| Probes in the catalog | 71: 33 general, 38 Kotlin | `drift capture --probes` |
| Scanners | 12 | `Scanners.all` |
| Rules | 32 active, 80 fixtures (33 positive, 47 negative), 39 cited sources (26 distinct) | `rules/*.yml` |
| Atlas, Kotlin 2.4.20, 13 columns | 23 of 38 probes differ on at least one column; 15 are identical on all 13 | `drift atlas build fixtures/atlas/*.txt` |
| Atlas, Kotlin 2.5.0-Beta1, 5 columns | 18 of 38 differ | `drift atlas build` over the Beta fixtures |
| Atlas version ladder | 12 compiler versions, 1.3.72 to 2.4.20, plus 2.5.0-Beta1; 65 columns, 2,470 cells | `drift atlas build` |
| Atlas probe classification | 18 documented, 4 platform-defined, 16 unclassified; among the 23 divergent probes 6, 4 and 13 | `drift atlas show` |
| Atlas site | 72 files, 14,632 KiB, 39 probe pages; byte-identical on a second run | `./gradlew atlasSite` |
| DriftBench | 76 scenarios from 39 templates; 45 dev scenarios validated in containers, 31 sealed | `bench/data` |
| Ranker on the 41 dev scenarios that have a cause, with probes | top-1 19 of 41 (Wilson 95% interval 0.32 to 0.61); random order expects 0.371; exact P = 0.034. Top-3 24 of 41, P = 0.37 | `evaluation.json` |
| `plausible` top candidate wrong | 7 of 45 dev scenarios (0.08 to 0.29); 10 of 19 `plausible` candidates; unchanged after probes were linked by attribute path | same |
| Lab, 45 dev scenarios, first run | 14 CONFIRMED (all correct), 9 CONFIRMED EFFECT (bundle), 1 NARROWED, 21 STUCK; 0 false CONFIRMED | 45 case directories, `drift verify` |
| Lab, three more runs of the same 45 | 180 runs in all: 0 false CONFIRMED, exact one-sided 95% upper bound 1.65%; 2 of 45 scenarios changed verdict class, both intermittent | 135 more `certificate.json` files |
| `.driftcase` archives, JVM and macOS native | byte-identical; `verify` output identical; 4 archives in `fixtures/lab/cases` | `cmp` |
| Studio installers built here | macOS dmg 74,624,330 bytes and pkg 70,015,031; Linux deb, rpm and AppImage in a container; Android APK 7,203,057; Windows msi and exe not built | `studio/build/compose/binaries`, `androidApp/build/distributions` |
| CLI release archives | 1.9 to 2.1 MB each for macOS arm64, Linux x64, Linux arm64 and Windows x64 | `.github/release/stage.sh` |
| Drangler transition, ranked | 8 candidates, 1 bundle, no single cause named | `drift diagnose` |

Four results shape how to read the rest. The fit of ranking weights on the 45 dev scenarios did not beat the hand-set weights out of fold, so the weights are hand-set and every ranking says so. On the benchmark the ranked order beats a random order at the top only narrowly: the top-1 interval contains the random rate, an exact test puts the chance of doing this well by luck at 3.4%, and the order at top-3 cannot be told from chance. The `plausible` tier, which a differing probe that reads the candidate's attribute earns, is not reliable on this benchmark: more than half of the candidates in it are wrong, because the probe agrees with decoys that really changed. Linking probes by attribute path instead of by dimension changed no ranking on the dev data. The probability the tool prints is mostly a count of rivals, "one of n", and it exists only for rankings that rest on attribute changes alone.

What does hold up under test is the machinery around the ranking. Output is byte-identical on three targets and pinned by hashes. A bundle of changes the data cannot separate is reported as a bundle. A missing probe is missing evidence and never a pass. The planner prices each experiment in expected bits before anything is run, and says which experiments no one can run on a hosted runner. The Lab fixes its decision rule before the first trial and gave no false CONFIRMED verdict in 180 runs of the 45 dev scenarios (synthetic scenarios, one container configuration, and a bound that holds per run, not per kind of failure). `verify` re-derives every count, p-value, posterior and verdict from an archive alone. It does not re-run an experiment.

Platform findings sit beside those. A Kotlin probe can print different bytes on x86-64 and arm64 hardware, on different JDKs, and on a Native executable linked on a different build host, so an Atlas column describes a machine as much as a target. Safari was not measured, Windows was not run, and no workflow has run on GitHub.

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

Three further commands deal with delivery and are described under Serving Studio Locally and Distribution. `drift serve` serves the Studio web build on the loopback interface. `drift install` and `drift uninstall` copy the executable onto the user's PATH and reverse that from a receipt.

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

**Byte-identical output.** Golden tests assert exact bytes and a SHA-256 in common test code, so the same assertion runs on the JVM, macOS arm64 Native and Wasm on Node. The `.driftcase` archive follows the same rule (see The Lab). `GoldenTest` pins the canonical bytes of a capsule with non-ASCII text, a control character and a surrogate pair (hash `9a69c086...edf1`), the hash of 1,000 `a` characters (`41edece4...7ea3`, checked here against Python's `hashlib`), and a canonical rule. Ranking, plan, case and rendering goldens follow the same pattern. The macOS release CLI printing `drift diagnose` for a real pair produced the same hash, `0bd53221...b05b`, as the golden that `DriftCommandTest` asserts on all three targets.

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
| `cli` | The `drift` command on Clikt 5.1.0, with 14 subcommands: `capture`, `diagnose`, `next`, `case`, `reproduce`, `atlas`, `solve`, `ingest`, `pack`, `verify`, `report`, `install`, `uninstall` and `serve`. Writes files and exact stdout bytes through a small `expect fun` per target, holds the Docker executor behind `solve`, the installer, and the web server behind `serve`. |
| `studio` | Compose Multiplatform 1.12.1 app for JVM Desktop and for the browser as Wasm, plus the Android and iOS builds when `-Pdrift.mobile=true` is set. |
| `androidApp` | The Android application module (AGP 9.4.1), present only with `-Pdrift.mobile=true`. |
| `iosApp` | The Xcode project for the iOS simulator app; it calls Gradle with `-Pdrift.mobile=true`. |
| `tools` | JVM only. Freezes CI logs into sanitized fixtures, builds capsules from them, verifies rule source URLs, and drives the Kotlin version ladder (`tools/ladder`). Also holds the Playwright scripts (`tools/browsers`: `run.mjs` records the browser columns, `serve.mjs` loads a served URL in each engine) and the installer test scripts. |
| `bench` | DriftBench: scenario DSL, seeded generator, container runner, ingest, calibration. |
| `build-core` | Included build with the `drift.kmp`, `drift.layout`, `drift.coverage`, `drift.quality`, `drift.mobile`, `drift.docs`, `drift.docs-root`, `drift.atlas` and `drift.packaging` plugins and the tasks they register: `embedFixtures`, `embedRules`, `embedClassification`, `embedDriftVersion`, `embedKotlinVersion`, `sharedRatio`, `checkLineLength`, the coverage tasks, `atlasSite` and `renderPackaging`. |

The remaining directories are data and delivery, not modules: `rules` (32 YAML rule files), `atlas` (the hand-maintained classification of every Atlas probe), `fixtures` (recorded capsules, Atlas transcripts and solved cases), `packaging` (templates for the Homebrew and Chocolatey packages), `assets` (the icon), and `install.sh` and `install.ps1` at the root. `.github/release` holds the scripts the release workflow runs. The version is `version=1.0.0` in `gradle.properties`; `core` embeds it for `--version`, the Studio and Android packages read it, and the Xcode project carries its own copy of the marketing version.

Sources sit at `src/<set>/main` and `src/<set>/test`. The shared-code ratio counts non-comment lines under `src/common/main` against every other source set's `main`.

| module | common lines | platform lines | shared |
| --- | --- | --- | --- |
| `host` | 176 | 388 | 31.2% |
| `core` | 9,712 | 0 | 100.0% |
| `scan` | 3,542 | 0 | 100.0% |
| `cli` | 2,576 | 1,549 | 62.4% |
| `studio` | 822 | 67 | 92.5% |
| `bench` | 1,996 | 466 | 81.1% |
| total | 18,824 | 2,470 | 88.4% |

`host` is where the platform code lives, at 388 of its 400-line budget. `cli` holds the per-target file, binary file and stdout writers and, since `host` has no room left, the installer and server actuals: the registry and process code for Windows, the profile edits on Unix, the sockets of `serve` for POSIX and Winsock, and the OpenSSL loader. That is why its shared share fell from 88.7% to 62.4% and the total from 94.6% to 88.4%; the floor is 80%. `bench` keeps its platform lines in JVM-only code: YAML reading, scenario preparation, result ingest and an exact binomial test with `BigInteger`.

JVM line coverage comes from JaCoCo. No coverage tool exists for Kotlin/Native or Wasm in Kotlin 2.4.20, so no coverage figure exists for those targets. The same common tests run on them.

| module | covered | missed | lines |
| --- | --- | --- | --- |
| `bench` | 1,713 | 76 | 95.8% |
| `cli` | 1,746 | 91 | 95.0% |
| `core` | 6,429 | 359 | 94.7% |
| `host` | 192 | 4 | 98.0% |
| `scan` | 2,100 | 24 | 98.9% |
| `studio` | 583 | 23 | 96.2% |
| `tools` | 644 | 104 | 86.1% |
| total | 13,582 | 699 | 95.1% |

`build/coverage/summary.txt` was written by `coverageSummary` at 05:32 on 2026-10-07 and `verifyCoverage` passed in the same run. The total, 95.11%, sits 0.11 points above the 95% floor, and `core` and `tools` are each below it alone because the floor applies to the total.

Targets and what ran:

| target | executable | tests with a recorded run |
| --- | --- | --- |
| JVM | jar; `./gradlew :cli:jvmRun` | yes, Gradle on a Mac |
| macOS arm64 | `drift.kexe`, 6,742,776 bytes (release) | yes, Gradle on a Mac |
| Wasm on Node | none | yes, Gradle on a Mac |
| Linux x64 | `drift.kexe`, 7,169,368 bytes, cross-linked on the Mac | test binaries run in a Debian container on a Linux host |
| Linux arm64 | `drift.kexe`, 6,113,704 bytes, cross-linked on the Mac | test binaries run in an arm64 Debian container; a build on an arm64 Linux host was not run |
| Windows x64 (mingw) | `drift.exe`, 6,912,512 bytes, cross-linked on the Mac | under Wine only, which is not Windows |
| Android, iOS simulator | APK, simulator app (only with `-Pdrift.mobile=true`) | emulator and simulator runs; see Platform and CPU Findings |

**Workflows.** `.github/workflows` holds four files, and none has a run recorded in this report. `actionlint` exits 0 on all four.

- `build.yml` runs on push and pull request for `master`, `ver/*`, `feat/*` and `renovate/*`, and by hand. A lint job runs `spotlessCheck`, `checkLineLength`, `sharedRatio` and `:build-core:check`, then compiles the JVM, Linux x64, Linux arm64, mingw and Wasm targets. A test matrix of three runners (Linux x64, macOS arm64, Windows x64) runs each target's tests, links the release executable and runs it with `--version`; a separate pair of jobs links the Linux arm64 tests on x64 and runs them on an arm64 runner. Only the Linux x64 job collects coverage, checks the 95% floor and uploads to Codecov. A `browsers` job builds the Studio web bundle, runs the Playwright smoke test in Chromium, Firefox and WebKit, and runs the Karma tests in Firefox.
- `release.yml` runs by hand with a `dry-run` input and a `suffix` input. It builds the four CLI archives, checks the arm64 archive on an arm64 runner, builds the Studio packages on three operating systems and the APK, then aggregates `SHA256SUMS`, publishes the release, and, when `suffix` is empty, updates the Homebrew tap and pushes the two Chocolatey packages. A dry run builds and checks every asset, renders the packages, and skips attestation and every push.
- `atlas.yml` runs on every push to `master`, `ver/*`, `feat/*` and `renovate/*` and by hand, and a newer push to a branch other than `master` cancels the older run: it records the Atlas transcripts on Linux x64, Linux arm64, macOS arm64 and Windows, records the JVM columns, records the three browser columns with Playwright, and builds the dataset and the static site.
- `docs.yml` builds the Dokka site and the Atlas site on the same pushes, or by hand, and pushes both to a `gh-pages` branch from `master` only, with a `CNAME` file for `drift.gmitch215.dev`.

**KDoc site.** `./gradlew :dokkaGenerate` aggregates the KDoc of `core`, `host`, `scan`, `tools`, `bench`, `cli` and `studio` into one Dokka 2.2.0 site, `build/dokka/html`. It is reference documentation of the engine for people reading the source. Drift ships as an application, the modules are not published artifacts, and the site is not published anywhere: only `docs.yml` would do that, and it has not run. The copy built on 2026-10-07 at 05:24 has 2,954 HTML pages. A scan of its relative links finds 14 broken among 51,678: 6 point at `--root--.html` pages that Dokka links to and does not write (`-cand`, `-classification-entry`), and 8 point at a `-coded` page of the `know` package that Dokka does not write. The causes were not examined further. An earlier Dokka failure on the `cli` module (two source sets sharing the POSIX source root, a known Dokka limitation, issue 3701) is worked around in `build-core/src/main/kotlin/drift.docs.gradle.kts` by dropping those roots from the Linux source set only.

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

`Capture.run` records, without options: `drift.platform`, `os.name`, `os.arch`, `kotlin.version` (the Kotlin version the build was compiled with), `kotlin.target`, `runtime.platform`, every environment variable as `env.<name>` (`PWD`, `OLDPWD`, `SHLVL`, `_`, `RANDOM`, `SECONDS` and `LINENO` are volatile), `kernel.release` and `cpu.count` from `uname` and `getconf` (or `ver` and `NUMBER_OF_PROCESSORS` on Windows), `os.release.ID`, `VERSION_ID` and `PRETTY_NAME` from `/etc/os-release`, and the cgroup CPU and memory limits when the files exist. `--tools` adds the scanner attributes and `--probes` runs the catalog. A capsule records `drift.platform` and no Drift version, so no recorded capsule, hash or fixture changes when the version does. On Windows the environment is read from the process's Win32 environment block with `GetEnvironmentStringsW` (`host/src/mingw/main/.../SystemHost.mingw.kt`, parsed by `EnvBlock.kt`), not from a `cmd /c set` child process, so it does not depend on a shell being found (see Platform and CPU Findings).

On the macOS development machine used for this report:

| command | attributes | of which volatile | probes |
| --- | --- | --- | --- |
| `drift capture` | 86 (78 are `env.*`) | 3 | 0 |
| `drift capture --tools` | 99 | 3 | 0 |
| `drift capture --probes --tools` | 99 | 3 | 71, all `ok` |

The probe catalog has 71 probes, each a Kotlin function that prints labelled lines. Thirty-three are general: 7 numeric, 8 text, 3 collection, 2 time, 9 process and 4 resource probes (cgroup limits, locale variables, a missing file). They map to dimensions through `Dimension.ofProbe`. Thirty-eight are the Kotlin probes of the Atlas, described below.

Capture masks secrets and removes identifiers by default. `Redactor` replaces the value of any `env.*` whose name contains `KEY`, `TOKEN`, `SECRET`, `PASSWORD`, `PASSWD`, `CREDENTIAL`, `AUTH` or `COOKIE`, and any value matching a GitHub token, AWS access key id, Slack token, PEM private key header, bearer token or `user:password@` URL. `Anonymizer` then rewrites the account name (`USER`, `LOGNAME`, `USERNAME` and the last component of `HOME`) to `user`, the host name (`HOSTNAME`, `COMPUTERNAME`) to `host` and the exact `HOME` directory to `~`, and turns any other `/Users/<name>`, `/home/<name>` or `C:\Users\<name>` path into `.../user`. Names are replaced as whole words and only when they have three or more characters. An attribute that changed gets ` (anonymized)` appended to its `source`, which diff, rank and reproduce never read. `--keep-identifiers` skips this step and prints `warning: the capsule keeps your account, host name and home path` on standard error; it is the escape hatch for a capsule that stays on the machine.

On the capture above, 13 of the 78 `env.*` attributes were masked. With `--keep-identifiers`, 18 `env.*` attributes held the account name and 16 held the home directory path. By default no attribute of any family holds either, and 18 attributes carry the marker. Three limits remain. The identities come from the `env.*` of the capturing host, so a host name that is not in the environment is not known to the step (`HOSTNAME` is unset in zsh). Probe transcripts and labels are not rewritten; the transcripts of this Mac hold no occurrence. The redactor has no rule for emails or account ids. `reproduce` uses the same `Anonymizer` with its own allowlist (see Reproduce).

**Exact bytes.** Standard output carries the capsule, a ranking's canonical JSON and an Atlas transcript exactly as encoded. An earlier build sent them through Clikt's `echo`, which rewrote U+0085 and U+2028 to a line feed and expanded each TAB to spaces. `drift capture --probes` on the macOS release executable then printed raw line breaks inside the JSON string of `kotlin.string.split-replace-lines`, and a strict parser rejected the output at column 35,430. That was a bug, and `capture`, `diagnose`, `atlas show`, `atlas compare` and `atlas record` without `--out` now write UTF-8 bytes through one `expect fun` per target (`System.out.write` on the JVM, `fwrite` on native, `process.stdout.write` on Node). The text of `case`, `next`, `reproduce`, `solve`, `ingest`, `pack` and `report` still goes through `echo`. `DriftCommandTest.captureBytesSurviveEveryLineSeparatorAndALongLine` pins U+0085, U+2028, U+2029, CRLF, TAB, NUL, U+FFFD and a 1 MiB line on all three targets. With the macOS release executable, `drift capture --probes --tools` parses with a strict JSON parser and its output holds one raw U+0085 and one raw U+2028.

---

## Scanners and the Kit

A scanner runs one command and parses its first matching line with plain character scanning. `Scanners.all` lists 12.

| tool | command | attributes under `tool.<name>.` |
| --- | --- | --- |
| `java` | `java -version` | `implementation`, `version` |
| `cc` | `gcc --version` | `family` (gcc or clang), `version` |
| `go` | `go version` | `version`, `os`, `arch` |
| `php` | `php -v` | `version`, `sapi`, `thread-safety` |
| `node` | `node --version` | `version` |
| `python` | `python3 --version` | `version` |
| `rust` | `rustc --version` | `version` |
| `git` | `git --version` | `version` |
| `coreutils` | `ls --version` | `flavor` (gnu, busybox, other), `version` |
| `libc` | `ldd --version` | `family` (glibc, musl), `version` |
| `visualstudio` | `vswhere -latest -property installationVersion` | `version` |
| `python-managed` (group `python`) | `python3 -c` checking for `EXTERNALLY-MANAGED` | `externally-managed` |

A missing tool (exit 127) writes nothing. Output that matches no parser writes `tool.<name>.version = unparsed` and a `raw` attribute holding the first line cut to 120 characters and redacted. The grouped scanner never writes that fallback, so a failed marker check cannot overwrite `tool.python.version`.

The kit is the same scanner list as a script. `Kit.sh()` and `Kit.ps1()` emit a script that runs every scanner command and wraps each output in `@@ <tool> exit=<code>` markers; `Kit.parse` and `Scanners.fromTranscripts` turn the captured text into the same attributes. An environment without the `drift` binary, such as a minimal container, can run the script and have the result parsed elsewhere. `fixtures/transcripts` holds 32 recorded outputs across the 12 tools, captured by `tools/capture-transcripts.sh` in Docker on an arm64 Mac. The two Visual Studio files are synthetic, since `vswhere` cannot run in Docker; everything else is real tool output.

---

## Diff and Dimensions

`CapsuleDiff.diff(green, red)` compares two capsules and returns three lists: `changes` (static attributes that were added, removed or changed), `ignored` (volatile and noisy attributes, with the less stable side deciding), and one entry per probe id with status `same`, `different` or `unavailable`.

Two details matter for correctness.

- **Order.** For a changed value, `order` says whether the version went up, stayed equal or went down. Values are parsed as versions (the JAVA scheme under `tool.java.`, so `1.8.0_504` and `8.0.504+1` parse as the same core). A changed value whose core compares equal, such as two build timestamps, reports `unordered`, never `equal`. An earlier version of the diff reported `equal` for a changed build date; `DiffTest.aChangedValueNeverReportsEqualOrder` pins the fix.
- **Operating system names.** `mac`, `macos`, `macosx`, `mac os x` and `darwin` normalize to `macos`; `windows*` and `mingw*` to `windows`. The normalization is applied to `os.name` on both sides during the diff, and capsules stay unchanged. `unknown`, which Wasm reports, is not mapped, so a Wasm capture against a Mac shows an `os` change.

Each changed path maps to one of 14 dimensions by longest matching prefix: `os`, `kernel`, `cpu`, `cpu-limit`, `memory`, `runtime`, `compiler`, `build`, `userland`, `env`, `locale`, `limits`, `network` and `browser`. For example `tool.node.` is `runtime`, `tool.npm.` and `deps.` and `ci.` are `build`, `env.TZ` is `locale`, and `env.HTTPS_PROXY` is `network`. A path no prefix claims has no dimension; it is listed under `changes` and never ranked. Probe ids map the same way, so a probe that differs in a dimension can support a candidate in that dimension.

---

## Rules and Provenance

A rule records a documented difference between two environments that can break software. The catalog has 32 rules in `rules/*.yml`, all active, each with a mechanism, one or more symptoms, a matcher, fixtures and cited sources.

A rule file has `schema: 1`, an `id` equal to the file name, a `title`, `applies` (dimension ids), `difference`, `symptoms`, `mechanism` (one sentence), `detect`, `severity` (`low`, `medium`, `high`), `provenance`, `fixtures` and `status`. A symptom has a `basis`: `verified` when the cited source states the symptom, `inferred` when it only states the mechanism. The matcher is a small language over a passing side `a` and a failing side `b`: `eq`, `ne`, `in`, `has`, `starts`, `ver` (version ranges), `present`, `changed` (with a trailing `*`), and `all`, `any`, `not`, to a depth of 8. This is a rule from the catalog:

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

Rule files are YAML because people maintain them by hand. snakeyaml-engine 3.2 parses them at build time under the failsafe schema, so every scalar is a string (`1.20`, `no`, `0o17` and `~` stay strings), and `embedRules` writes the JSON that `core` reads. Common code never parses YAML. A stray `rules/*.json` fails the build, and the embedded JSON is byte-identical to what the earlier JSON catalog produced (32 of 32 rules, checked when the format changed).

The checker, `Constraints.check`, runs in the `core` test suite, so on every build. It validates each rule (id shape, non-empty fields, one-sentence mechanism, dimension ids, provenance, fixtures), requires unique ids, requires every positive fixture to match and every negative fixture not to match, and lists any `pending-scanner` rule (a rule that reads an attribute no scanner emits yet; none exist today). A scan-side test (`RuleCatalogTest`) checks that every attribute path an active rule reads is one that capture or a scanner emits.

| property | count |
| --- | --- |
| rules | 32 |
| severity high / medium / low | 12 / 17 / 3 |
| fixtures, positive / negative | 33 / 47 |
| cited sources | 39 |
| source kinds: measurement / vendor-doc / release-note / spec | 14 / 12 / 7 / 6 |
| symptoms, `verified` / `inferred` | 25 / 10 |
| rules with at least one `inferred` symptom | 10 |

A matched rule gives a candidate the `known` tier and a weight by severity, with its mechanism text attached. A rule whose matcher touches no changed attribute is listed as unattached and never dropped.

The rule sources are cited and not reproduced. The fixtures are synthetic pairs written to exercise the matcher. They show that the rule fires on the stated versions and stays quiet on others; they do not show that the failure occurs. The 39 provenance entries name 26 distinct locations: 24 web URLs and 2 `fixture:` references, which the verifier skips. `tools` includes a verifier that fetches each distinct web URL and records the HTTP status. Its unit tests use a fake fetcher. A run against the network covered 3 URLs (all 3 returned HTTP 200), and an offline run over all of `rules/` skipped every URL. A run over all 24 web URLs is not recorded. An HTTP 200 shows the page exists, not that the quoted section still does.

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

**Probe links.** `ProbeLinks` maps each of the 33 general probes to the attribute path prefixes its output depends on: the numeric, text, collection and duration probes run inside the Drift process and read `runtime.platform` and `tool.java.`; the shell probes read `tool.coreutils.`, `os.name` and `os.release.`; `process.timezone` reads `env.TZ`; `resources.locale-env` reads `env.LANG`, `env.LC_` and `env.TZ`; the two cgroup probes read `cgroup.cpu.` and `cgroup.memory.`; two probes read nothing. The prefix lists are a reading of the probe sources, not a measurement, and `tool.java.` for the in-process probes assumes the `java` on `PATH` is the JVM that runs Drift. A probe in the candidate's dimension that does not read its attribute no longer counts for the candidate, which removes a rule that supported every changed attribute of a dimension whether or not the probe could have seen it. On the 45 dev scenarios the change moved nothing: every ranking, tier and rate is identical before and after (see Calibration on the Dev Split). The benefit is by construction, tested with a property test over random capsule pairs, and is not measured.

**Bundles.** Candidates with the same tier, the same probe and rule evidence and the same Ochiai counts cannot be told apart by the data. They share a bundle id, and no member of a bundle is reported as the cause on its own. Order inside a bundle comes from the dimension prior alone and means nothing.

**Probability.** The ranking carries a probability only when every candidate rests on attribute evidence alone. It is the candidate's share of the softmax over all scores plus a fitted weight for "none of the listed candidates is the cause" (0.25). The shares of a ranking add up to less than one. Where any candidate has probe, rule or spectrum evidence, every probability is null.

Pair mode, the real drangler failure, a passing run against a failing one:

```sh
drift diagnose fixtures/drangler/capsules/36702683742.json fixtures/drangler/capsules/36995781138.json --detail summary --case case
```

```
wrote 11 files to case
8 changes differ with the same support, and tool.node.version ranks first only by hand-set weights,
so the data does not name one cause.
```

At `--detail full` the eight candidates are all tier `difference`: `tool.node.version` scores 1.098612 (runtime, 3x) with probability 0.144578, and the other seven score 0.916291 (build, 2.5x) with probability 0.120482 each. The eight probabilities sum to 0.988, which is the fitted "none" weight showing; the real case is outside the distribution that weight was fitted on (see Evaluation and Limits).

History mode over the same case, from `RankHistoryTest.theDranglerTransitionIsOneBundleNoCandidateIsTheCause` (5 passing runs, 3 failing runs, job `Docker E2E`): the transition is a `BUNDLE` with three added steps (`Set up Node`, `Post Set up Node`, `Dump Host State on Failure`); all 8 candidates are tier `correlated`, basis `inferred`, in one bundle `bundle-1`; every candidate has spectrum counts `ef 3, nf 0, ep 0`, score 1.000000 and coverage 0.862068 (25 of 29 attributes present in each passing run). The data separates nothing: every changed value is present in all three failing runs and in none of the five passing ones. The catalog fires no rule on this pair. The ranking's order within the bundle is the dimension prior.

---

## The Experiment Planner

The planner takes a ranking and proposes experiments. Each experiment sets some attributes of the passing environment to the failing side's values and compares failures against an unchanged control.

**Hypotheses and prior.** Each candidate is a hypothesis. Candidates that are one change seen twice can be coupled (`ci.provisioner.build-date` with `ci.provisioner.version`), and an attribute that comes from a step can couple to that step. The prior is `exp(score - max)` over the ranking scores, normalized by largest remainder, with a fixed 25% reserved for `unknown`. In pair mode on the drangler capsules there are 7 hypotheses plus `unknown`, and the prior entropy is 2.914 bits.

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
  e9: drangler-36702683742 with deps.@napi-rs/keyring.version, deps.prettier-plugin-sh.version,
    deps.wrangler.version, tool.node.version, tool.npm.version set to drangler-36995781138 values
    automatic-ci; 10 min, 70 runner min, 14 trials
    why: highest information gain per cost among the experiments that can run
    expected information gain 0.506 bits from the hand-set prior (a prediction, not a measurement)
    ...
    outcomes: reproduced 0.470713; not-reproduced 0.529287
```

The plan has 9 automatic CI experiments and 2 manual ones. All 9 automatic experiments cost the same (157 points: 10 minutes, 70 runner minutes, 14 trials at 7 per arm), so EIG alone orders them. Seven trials per arm with a clean control and a threshold of 4 failures: the exact one-sided p for 4 of 7 against 0 of 7 is 35/1001 = 0.0350, and for 3 of 7 it is 0.0962. The power at the smoothed rate of 2/3 is 1808/2187 = 0.8267. Both figures reproduce by hand from the formulas above, and so does the EIG: recomputing it from the prior masses, the power and alpha gives 0.50595 bits and P(reproduced) 0.470714.

The first experiment flips all five controllable hypotheses together. Part of the mass cannot be flipped at all (unknown 25%, runner image and provisioner about 21%), so asking whether the controllable set reproduces the failure at all gains more than splitting it: 0.506 bits against 0.479 for a split of three and 0.401 for a split of two. Over 8 equally likely controllable members, a half split goes first (`PlanTest.theFirstExperimentOnEightEquallyLikelyMembersSplitsTheBundleInHalf`).

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

**Interventions and controllability.** An intervention is one typed edit of a run configuration: set or unset an environment variable, set the locale, the timezone, a CPU or memory limit, a `ulimit`, a toolchain version or an operating system release, set a flag, write a file, toggle a workflow step or pin a dependency (13 types in `Intervention.kt`). One that cannot apply returns a typed problem (invalid, not applicable in this environment, no effect, or not mirrored into the container) and never runs. `Controllability.classify` sorts each candidate into automatic-local, automatic-CI or manual. It starts from the planner's `Controls` table, so a dimension the planner calls manual is never promoted, then demotes what the `reproduce` manifest says a container cannot mirror. A member that only travels with a base image (`tool.libc.`, `tool.coreutils.`, `tool.git.`) is manual, because nothing sets it alone. The loop re-plans without any hypothesis its arms cannot set, and never runs an experiment it knows cannot isolate its target.

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

**Classification.** The classification lives in `atlas/classification.yml`, one hand-maintained entry per probe, and the build embeds it as JSON so the dataset, `atlas show` and Studio read one source. A probe is `documented` (pages that the author fetched and read in full state the behavior, for every differing line), `platform-defined` (a platform specification delegates it: a JDK Javadoc page, an ECMAScript section or the Unicode version a Character page names), or `unclassified` (no explanation found; this does not call the behavior a defect). Each entry records its references with a short quote, the KT issue ids with the state read on the research date, the cause classes, and for an `unclassified` probe what was searched. Of 38 probes, 18 are documented, 4 platform-defined and 16 unclassified. Among the 23 divergent probes on 2.4.20, 6 of 18 documented, 4 of 4 platform-defined and 13 of 16 unclassified diverge. Four entries were downgraded from `documented` to `unclassified` when the page read did not cover every differing line.

The method has limits. The tracker was read through YouTrack's REST API, read only: the entries list their queries, and where an entry says so only the first 8 results of each query were read, so a relevant issue ranked lower was not seen. Anything not named in an entry's references was not read. Quoted passages are short and not reproduced. The classification says what the sources named explain and does not say why a target differs. `drift atlas show --probe` prints these fields and a draft for human review that begins `GENERATED DRAFT, NOT REVIEWED`; the draft files under `atlas/repros/` (one per divergent unclassified probe, 13) are never sent anywhere.

**Results, Kotlin 2.4.20** (13 columns: JVM on JDK 21 on a Mac and on JDK 17, 21 and 25 on x86-64 Linux, Native on macOS arm64, Linux x64 and Linux arm64, Wasm on Node and in three browsers, Android, iOS):

| quantity | value |
| --- | --- |
| probes identical on all 13 columns | 15 |
| probes that differ on at least one | 23 |
| probe output lines per column | 734 |
| lines that differ between JVM, macOS and Wasm on Node | 107 |
| lines that differ between any of the 13 columns | 126 |
| dataset, 13 columns, canonical JSON | sha256 `363716f2...64c4` |
| dataset, 18 columns (adds the 5 at 2.5.0-Beta1), schema 2 | 575,879 bytes on disk, sha256 `de6b25ca...ccebb` of the body |

Adding the ten columns beyond JVM 21, macOS and Wasm on Node made no identical probe divergent: the divergent count stayed at 23.

A fresh `drift atlas record` on the macOS release executable wrote a transcript byte-identical to the committed `fixtures/atlas/macos.txt`. Compared against the 18-column dataset, this Mac matches its own column on 38 of 38 probes, the iOS column on 38, the Linux arm64 column on 37, Linux x64 on 36, JDK 21 on 21, and the Wasm column on 25.

The divergences are specific and small. Two lines of a 14-line probe:

```sh
drift atlas show atlas.json --probe kotlin.collections.iterator-modification
```

```
kotlin 2.4.20: 3 different results
differing lines: 2 of 14
  line 3
    android: list-remove-second = err:ConcurrentModificationException
    ios, linux, linux-arm64, macos, wasm, wasm-chromium, wasm-firefox, wasm-webkit: list-remove-second = ok:[1, 3]
    jvm, jvm-17, jvm-21, jvm-25: list-remove-second = ok:[1, 3]
  line 4
    android: list-remove-last = ok:[1, 2]
    ios, linux, linux-arm64, macos, wasm, wasm-chromium, wasm-firefox, wasm-webkit: list-remove-last = ok:[1, 2]
    jvm, jvm-17, jvm-21, jvm-25: list-remove-last = err:ConcurrentModificationException
controls: 12 lines are the same on every target
```

Others, each in `fixtures/atlas/*.txt`:

- `Double.toString` on Wasm (2.4.20) prints `1e7` as `10000000.0`, `1e21` as `1e+21` and `9.999e-4` as `0.0009999`; JVM and Native print `1.0E7`, `1.0E21` and `9.999E-4`. Seven of 11 lines of `kotlin.double.tostring-boundaries` differ. KT-88414 is the matching tracker issue.
- `1.0.pow(Double.NaN)` is `1.0` on Native (macOS, Linux and iOS) and on Android, and `NaN` on the JVM and Wasm (KT-89072, open, reports the Native result).
- `Double.roundToInt()` and `roundToLong()` give `1` for 0.49999999999999994 on Native and Wasm and `0` on the JVM and Android (KT-89074, open).
- `String.compareTo` ignoring case returns `108` for the sharp s case on the JVM and Android and `1` on Native and Wasm. Only the sign is portable.
- A `HashSet` of the same integers iterates in a different order on the JVM and Android than on Native and Wasm.

**Kotlin 2.5.0-Beta1.** The same 38 probes were recorded on five targets (JVM, macOS arm64 Native, Wasm on Node, an Android emulator and an iOS simulator) with the compiler pinned to 2.5.0-Beta1 in a copy of the repository, once each. Divergent probes fall from 23 to 18. Five probes became identical on all five targets: `double.extremes`, `double.parse`, `double.tostring-boundaries`, `float.tostring` and `numbers.float-conversions`, all by the Wasm column moving to match the others (and, for the last, the macOS and iOS columns too). Five more changed without becoming identical, all in the Wasm column: `collections.tostring-forms`, `double.shortest-digits` (three groups to two; Android still differs from the rest), `exceptions.messages`, `math.pow-special-cases` and `math.rounding`. The probe notes cite KT-88414 for the Double and Float formatting; the tracker lists it as Fixed, resolved 2026-08-30. The data shows what changed. It does not show why, and the compiler changes behind it were not read. The `kotlin.version` header of a Beta transcript keeps the qualifier, because the scan module embeds the version from the build's version catalog (`embedKotlinVersion`).

Against the 18-column dataset, this Mac matches `macos@2.4.20` on 38 probes and `macos@2.5.0-Beta1` on 37.

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
| 2.4.20 | 38 | 38 | 38 | - | 37 | 38 |
| 2.5.0-Beta1 | 38 | 38 | - | - | - | 38 |

The two newest rows also have `android` and `ios` columns (38 probes each), left out of the table, and 2.4.20 has the 13 columns described above. `atlas build` over the 13 columns of `fixtures/atlas`, the 5 of `fixtures/atlas/2.5.0-beta1` and the ladder transcripts gives 65 columns, 2,470 cells, 33 divergent probes and 5 identical (sha256 `5c830fd4...8d55`). The ladder's own `2.4.20/linux.txt` is left out of that build because `fixtures/atlas/linux.txt` is the same column (Linux Native at 2.4.20). A probe counts as divergent when its output or its availability differs between columns of one version, so these counts include APIs that do not exist yet in old versions. Value changes, as opposed to appearing APIs, are fewer. On the JVM, no probe changes value anywhere from 1.3.72 to 2.4.20, apart from one class name printed by `kotlin.exceptions.tostring-cause`, which differs because the ladder compiles the displayed source and the committed 2.4.20 column ran the real code. Changes on the other targets:

- Native `String.hashCode` aligned with the JVM between 1.4.32 and 1.5.32 on Linux: `"hello"` hashes to 604317365 in 1.4.32 and 99162322 from 1.5.32. KT-44746 describes the mismatch (state Fixed, resolved 2021-02-09). The first compiler in the ladder that shows the aligned value is 1.5.32; the issue text does not name a fix version.
- `Boolean.hashCode()` of `true` changes from 1 to 1231 at 1.9.25 on Linux, macOS and JS IR. KT-61028 lists the change for Native, JS and Wasm.
- JS IR printed `Long` values through a double until 1.5.32 and prints them exactly from 1.6.21: `Long.MIN_VALUE / -1` prints `-9223372036854776000` in 1.5.32 and `-9223372036854775808` in 1.6.21. No tracker issue was read for this one.

What did not run, and why:

- Native 1.3.72 and 1.4.32 on Linux need `libtinfo.so.5`, and 1.4.32 fails in `konanc` on JDK 21; a Temurin 11 image with `libtinfo5` ran all three Linux builds up to 1.5.32. macOS Native 1.5.32 does not run on an arm64 JDK 21 or under Rosetta; 1.3.72 and 1.4.32 have only an x86_64 macOS bundle.
- On Native up to 1.8.22, `kotlin.collections.iterator-modification` ends the process with a segmentation fault (exit 139), and on Linux 1.3.72 and 1.4.32 `kotlin.int.overflow` exits with SIGFPE (136). Both are recorded as `UNAVAILABLE` with the exit code, and every other probe ran in a per-probe rerun. The cause of either was not isolated.
- Native 1.9.25 and 2.0.21 throw `ArrayIndexOutOfBoundsException` out of the `kotlin.double.parse` body; 2.1.21 fixes it. The triggering input was not isolated.
- JS legacy stops at 1.8.22 (1.9.25 refuses the legacy compiler). JS IR from 2.1 loses one probe, `kotlin.collections.tostring-forms`, which needs a reflection API Kotlin/JS does not support.
- Wasm has no standalone compiler route (`kotlinc-js -Xwasm` on 2.0.21 fails with `jsFrontEndResult has not been initialized`), so Wasm columns exist for 2.4.20 and 2.5.0-Beta1 only, both from Gradle builds.

**Studio.** The Atlas is the first tab of Drift Studio. It shows the matrix of 13 columns at 2.4.20 and 5 at 2.5.0-Beta1 with a version selector, the columns grouped by family (JVM, Native, Wasm, Mobile, Device) under one horizontal scroll, a divergent-only filter, three detail levels, a column for the device the app runs on, and a predict-then-reveal mode that shows the question and the source, asks which output a target gives, and keeps the score in memory. A tap on a row shows the source, each target's full output, the basis and quoted references, the issues with the state read, what was searched, and the notes. A probe with no explanation reads "unclassified: no explanation found", and the footer states the limits of the data. The app has an Investigation tab that runs `StudioState.of` over the drangler capsules (funnel, candidates, drill-in, three detail levels). Studio builds for JVM Desktop and for the browser as Wasm; the Android and iOS builds replay the embedded transcripts and add the live probes of the device. The JVM tests (41) and the browser tests (28, in Chrome through `wasmJsBrowserTest`) pass.

**The site.** `./gradlew atlasSite` writes a static site to `build/atlas-site`: the Studio web build at the root, `data/dataset.json` (575,879 bytes), `data/classification.json`, the 18 transcripts, `404.html`, and one page per probe under `probe/` (38 pages and an index). It has 72 files and 14,632 KiB, of which the two Wasm files are about 12 MB. The probe pages are static HTML with no script and no external resource, and the text is escaped. Building twice gives the same SHA-256 for all 72 files; a unit test also builds twice from two temporary directories. `docs.yml` places this site at the root of the `gh-pages` branch and the Dokka site under `/engine/`. The deploy steps were run against a local bare repository in a scratch copy of the tree: the first run made one commit of 2,907 files, the second printed "No changes to deploy" and made none. No GitHub run exists, and the sticky name column and horizontal scroll of the matrix were not looked at in a browser.

**What the Atlas does not cover.** There is no Safari column. The `mingw-wine` transcript in `fixtures/atlas-extra` comes from Wine and is not a Windows column, and no Windows JVM column exists. Android is an emulator on API 37 and iOS is a simulator; no device ran. There is no macOS x64 column and no Kotlin/JS column from the Gradle build. `.github/workflows/atlas.yml` defines the jobs that would record Linux x64, Linux arm64, macOS, Windows, three JDKs, and the browsers; it has not been run. Every cell is a single run, and the hardware and build host change some cells (see Platform and CPU Findings and The build host).

---

## Platform and CPU Findings

The same Kotlin code printed different bytes depending on the machine around it. Each finding below names the probe, the columns and what was and was not isolated.

**Linux, mingw and mobile runs.** The Linux x64 and Linux arm64 test binaries were cross-linked on the Mac and run natively, x64 in a Debian container on a Linux host and arm64 in an arm64 Debian container on the Mac's Docker; each ran 730 tests with 0 failures (core 492, scan 80, host 8, cli 74, bench 76, the counts of the tree at that run, before the installer, server and Atlas additions). A later x64 run in a Temurin 21 container (`--memory 10g --cpus 8`, 0 failures) passed core 492, scan 94, host 14, cli 220 and bench 76. A Kotlin/Native build on an arm64 Linux host was not run. The mingw test binaries ran under Wine 9.0 on Linux. Wine is not Windows, so these runs show that the binaries start and the logic runs and show nothing about Windows behavior. In the first run 79 of 80 `scan` tests passed: `CaptureTest.selfDiffOnTheRealHostIsEmpty` failed with "no env captured on mingw", because `NativeHost.env()` ran `cmd /c set` through `_popen`, and under Wine `_popen` failed with errno 2 for every command string tried; `getenv("COMSPEC")` and `getenv("PATH")` were both null in the test process, so the C runtime could not find a shell. Real Windows always sets both, so this cause is inferred not to apply there, and that inference was not run. The fix reads the Win32 environment block with `GetEnvironmentStringsW` (`EnvBlock.kt` and the mingw `SystemHost`, six parser tests and one real-host test). After it, `scan` passed 80 of 80 and `host` 15 of 15 under Wine, and `capture` under Wine recorded 21 `env.*` attributes and `cpu.count`. Under Wine every other `run()` call still returns null, so facts that come from commands stay absent. A later cli run under Wine passed 205 of 206 tests; the failure is `NativeInstallTest.filesAndDirectoriesBehaveLikeTheFakeDoes` (an installer assertion, cause not investigated, possibly Wine's file system). On Android, the three `host` device tests passed on the emulator; on iOS, `host`, `core` and `scan` simulator tests passed at the counts of that run (11, 484 and 79). No device ran either.

**Browsers.** `tools/browsers/run.mjs` loads the Studio web build in Playwright 1.63.0 and writes the this-device transcript the page produces. On one x86-64 Linux host:

| engine | version | first contentful paint, 4 runs | this-device probes, 4 runs |
| --- | --- | --- | --- |
| Chromium (V8) | 153.0.8010.12 | 288 to 332 ms | 40 to 42 ms |
| Firefox (SpiderMonkey) | 155.0 | 314 to 372 ms | 41 to 45 ms |
| WebKit (JavaScriptCore) | 26.6, Playwright's Linux build | 343 to 372 ms | 44 to 46 ms |

Four full runs per engine wrote byte-identical transcripts, which are the committed `wasm-chromium.txt`, `wasm-firefox.txt` and `wasm-webkit.txt`. The three engines agree on all 38 probes. Karma ran `studio:wasmJsBrowserTest` in Firefox ESR 153.4.0 in a container: 26 tests, 0 failures at that run (the Chrome run now has 28). Safari was not measured: its automation needs "Allow remote automation" in Safari's Developer settings, which was off and was not changed. Playwright's WebKit is a Linux build and says nothing about Safari. Two engine findings: headless Firefox in the container could not create the WebGL context that Compose needs, so the runs use headed Firefox under `xvfb`; and WebKit under `xvfb` with no locale reported `navigator.language` as `C`, and Compose threw `RangeError: invalid language tag`, so the runner sets the locale to `en-US`. Studio does not survive a browser that reports an invalid language tag.

**A CPU-dependent probe.** `kotlin.double.nan-bits` records the raw bits of `0.0 / 0.0`, `sqrt(-1.0)` and `inf - inf`. On x86-64 `0/0` has the sign bit set (`fff8000000000000`) and on Apple Silicon and arm64 Linux it does not (`7ff8000000000000`). Every browser column differs from `wasm` (Node on a Mac) on that probe only, and the three browsers agree with each other. A hand-assembled WebAssembly module doing `f64.div 0 0` and reinterpreting the result printed `7ff8...` on Node 26.8.2 on arm64 and `fff8...` on Node 22.23.3 on x64, so in this data the cell follows the CPU and not the engine. That script is not in the repository. The Node column was never recorded on x86-64, so the `wasm` column and the browser columns stay confounded. The same probe splits the JVM columns by CPU: `jvm` (JDK 21, Mac) and `jvm-21` (JDK 21, x86-64 Linux) differ on `double.nan-bits`, `math.pow-special-cases` and `math.transcendental-bits`, and a JDK 21 run on arm64 Linux is byte-identical to `jvm`. Linux x64 against Linux arm64 differs on `double.nan-bits` only.

**The JDK.** With the same classes (compiled for Java 17) on one x86-64 host, the JVM column changes with the JDK. JDK 17 against 21 differs in 6 probes: `char.bmp-case-mapping` and `char.bmp-categories` (the Character Javadocs of 17, 21 and 25 name Unicode 13.0, 15.0 and 16.0, and the probe's letter counts differ: 48,909, 48,965 and 48,973), `double.shortest-digits` (17 prints `2e23` as `1.9999999999999998E23`, 21 prints `2.0E23`; the JDK 19 release note JDK-4511638 describes the `Double.toString` change), `exceptions.messages`, `numbers.float-conversions` and `regex.unicode-matching`. JDK 21 against 25 differs in the two Character probes. One line of the 17 column is a harness effect: `removeFirst` on an empty list reports `NoSuchMethodError` because the probe was compiled against the JDK 21 API. The classification entries cite those pages; which Unicode release each table corresponds to was not checked beyond what the Javadocs name.

**R8 on Android.** Turning on `isMinifyEnabled` with `proguard-android-optimize.txt` shrank the release APK from 7,203,057 to 1,435,574 bytes and the app still launched, but the on-device Atlas summary changed from "38 of 38 probes match" to "35 of 38" and "matches no column: 3". R8 changed the measured result of 3 probes, which defeats the app, so minification stays off in `androidApp/build.gradle.kts`. The three probes were not identified, because a release APK cannot be read with `run-as`. This was measured once during packaging and not repeated for this report.

**The build host.** Eight `cli` executables were built from the same sources (x64 and arm64, linked on macOS or on Ubuntu, release or debug) and run in Debian containers. Only the macOS-linked release build prints `...a5` for `tan1` and `...7365` for `asin-half` and `acos-half` in `kotlin.math.transcendental-bits`; the other seven print `...a6` and `...7366`. The macOS-linked release transcripts are the committed `linux` and `linux-arm64` columns (sha256 equal), and `fixtures/atlas-variants` holds the Ubuntu-linked ones. A throwaway program (linuxX64, release, linked on macOS) printed `tan(1.0)`, `asin(0.5)` and `acos(0.5)` as `...a5` and `...7365` when written as constants and as `...a6` and `...7366` when read from the command line, so one executable disagreed with itself: the constants are Apple's `libm` values and the run-time values are glibc's. Linked on Ubuntu, or built as debug on macOS, it printed the glibc values for both. Ubuntu 24.04.5 and 26.04.1 builds gave identical transcripts. A run of one executable on glibc 2.36 and on glibc 2.39 printed identical output on all 38 probes (`fixtures/atlas-extra/linux-ubuntu`), so the runtime library does not explain it. The ladder's 2.4.20 Linux transcript, compiled with the standalone Kotlin/Native compiler on x86-64 Linux, prints the `...a6` and `...7366` values (`diff` of `fixtures/atlas-ladder/2.4.20/linux.txt` and `fixtures/atlas/linux.txt` shows those three lines and the two class-name lines). Gradle's native test binaries are debug builds, so no test binary can reproduce the committed `linux` column; `KotlinProbeTest` and `AtlasCommandTest` compare a Linux test binary with the variant transcript instead. The mechanism inside the compiler is an inference: that an optimizing build folds `tan`, `asin` and `acos` on constants with the `libm` of the machine that runs the compiler, and that a debug build does not. Nothing inspected the compiler. The same throwaway program for linuxArm64, linked on macOS, did not show the fold, though the real `cli` arm64 executable does; that is unexplained, so the arm64 evidence is the four builds, not the throwaway. The committed Linux columns are the macOS-linked release builds, and a run of `atlas.yml`, which links on Ubuntu runners, would record the `...a6` and `...7366` values. A Wine run of the mingw executable differs from Linux x64 on `math.transcendental-bits` only, so its NaN bits follow the x86-64 host and its last-bit results follow the C library it uses.

---

## Serving Studio Locally

`drift serve` serves the Studio web build to a browser on the same machine, so the Atlas viewer needs no hosting step. Its options are `--dir`, `--port`, `--tls` with `--cert` and `--key`, `--mkcert`, `--domain` (repeatable), `--mdns`, `--open`, `--unsafe-bind-all` and `--quiet`. It answers `GET` and `HEAD` for files under one directory and nothing else. The server core, request parser and static file rules are common code; the sockets are a JVM actual, a POSIX actual for macOS and Linux and a Winsock actual for mingw.

The loopback, Host, path, header and exit checks below were run on the macOS release executable for this report (port 18771, build of 2026-10-07). Further variants named in them were tried earlier on the same executable.

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

**Tests and gaps.** The serve tests number 78 on the JVM, 76 on macOS Native and 64 on Wasm. Under Wine, `drift.exe serve --dir` served `/` and the Wasm file with the right MIME type, refused traversal and a bad Host, and printed `stopped` on SIGINT. Not run: real Windows, Safari, `--open` with a real browser launch (a fake runner only), the Linux arm64 and macOS x64 executables, and a Linux or macOS host with no OpenSSL.

---

## Distribution

**CLI archives.** `.github/release/stage.sh archive` packs an executable with `LICENSE` and a `README.txt` into `drift-<version>-<os>-<arch>.tar.gz` (`.zip` for Windows), flat, with a `.sha256` sidecar; `stage.sh sums` checks every sidecar and writes `SHA256SUMS`. Run on the current executables for this report:

| archive | bytes |
| --- | --- |
| `drift-1.0.0-macos-arm64.tar.gz` | 1,984,195 |
| `drift-1.0.0-linux-x64.tar.gz` | 2,107,037 |
| `drift-1.0.0-linux-arm64.tar.gz` | 1,856,352 |
| `drift-1.0.0-windows-x64.zip` | 2,095,181 |

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

The macOS dmg and pkg and the APK exist in `studio/build/compose/binaries/main` and `androidApp/build/distributions` here; the Linux sizes come from the container build and are not on this machine. Nothing is signed or notarized. The macOS app has an ad-hoc signature and Gatekeeper's `spctl` reports it as rejected; the APK is signed with the build machine's debug key, so a sideloaded update over a build from another machine needs an uninstall first. The deb and rpm are desktop packages: in a bare container `dpkg -i` and `dnf install` failed in jpackage's postinstall script until the desktop and MIME directories existed, as they do on a desktop. The `.driftcase` file association is configured, but Studio does not open case files, so a double click only starts Studio. The iOS app is a simulator build; no `.ipa` is made. Windows installers, per-machine install with a UAC prompt, upgrade UUID behavior and SmartScreen were not tested.

**Homebrew and Chocolatey.** `packaging/` holds the templates; `./gradlew renderPackaging -Pdrift.sums=SHA256SUMS` copies them to `build/packaging` and fills `@version@`, `@sha256:<suffix>@` (the digest of `drift-<version>-<suffix>` in `SHA256SUMS`) and `@license@`, and fails when a token names an asset missing from the sums file. Ten build-core tests cover it, and a render on the Mac and one in an Ubuntu container were byte-identical.

- The Homebrew formula `drift` installs the CLI on Apple silicon macOS, Linux x64 and Linux arm64 (no Intel macOS asset). The cask `drift-studio` installs the dmg and removes the quarantine attribute after install, because the app is not notarized; Homebrew's own policy says casks that fail Gatekeeper are disabled, so this cask can only live in a third-party tap. `brew style` and `brew audit --strict` reported no offenses, a formula install from a scratch tap against a local HTTP server printed `drift version 1.0.0 (macos)` and passed `brew test`, and the cask installed into a scratch application directory, launched and uninstalled. All of that was cleaned up afterwards. The public tap repository does not exist yet, so `brew audit --online` and a real `brew install gmitch215/tap/drift` were not run.
- The Chocolatey packages are `drift` (the Windows zip) and `drift-studio` (the MSI, installed silently with `/qn /norestart`). Real `choco pack` 2.7.4 under Mono built both nupkgs (4,916 and 4,977 bytes), and `packaging/chocolatey/verify.ps1` ran the rendered install and uninstall scripts against stubbed Chocolatey helpers in PowerShell 7.4.2 and compared URL, checksum, silent arguments and exit codes with `SHA256SUMS` (`all checks passed`; altering two digests made it fail). The MSI was never built, so the digest in the `drift-studio` render used for that check is a placeholder of zeros. Real `choco install`, the community repository's moderation, and whether the id `drift` is free were not tried.

**The release workflow.** `release.yml` builds the four CLI archives, the Studio packages and the APK, adds the sidecars, aggregates `SHA256SUMS`, attests each build job's files, publishes the GitHub release, and then runs the `homebrew` and `chocolatey` jobs when no suffix is given. They need two repository secrets, `HOMEBREW_TAP_TOKEN` and `CHOCOLATEY_API_KEY`, and fail early when one is empty on a real run. `dry-run` builds and checks everything and uploads the results as artifacts without attesting or pushing. `stage.sh`, the asset wait script and the render task were exercised by hand, and the steps of the Homebrew job ran from a fresh clone on the Mac and in a container. No job has run on GitHub, and no Windows machine was used for any step.

---

## DriftBench

DriftBench scores a diagnosis against a known cause. A scenario is a pair of containers, green and red, running one small program. The red arm fails at a declared rate and the green arm passes. The injected cause is ground truth by construction, because the scenario was built around it, so the benchmark measures how well a ranking recovers a cause that was put there. It is synthetic and every scenario and result file says so (`"synthetic": true`).

**Scenarios.** 39 templates in 15 YAML files (`bench/scenarios/*.yml`, one per dimension) produce 76 scenarios. A template names a dimension, a program in `sh`, `python3`, `node` or `java`, the failure signature, the run configuration of each arm (image, environment, memory, CPUs, cpuset, ulimits, network, files), and the cause with optional bundle members and decoys. A seeded SplitMix64 generator (seed 20261006) picks variants and draws values. The class of a scenario is derived: `control` (no cause), `intermittent` (red rate under 1,000 permille), `bundle`, `decoy`, else `single`. The 76 split into single 29, bundle 15, intermittent 12, decoy 12 and control 8.

**Intermittency.** The harness arms the fault on trial `i` when a fixed integer hash of `i` and the scenario salt is under the gate, and the program fails only when the fault is armed and the injected cause is present. The failing trials are therefore known before Docker runs, and a flaky scenario is a deterministic pattern with a declared rate. No scenario contains a real race.

**Causes a container cannot vary.** Kernel release, clocksource, CPU model and browser engine run one configuration in both arms; the cause values exist as `capsule-overrides` written into the saved capsules, marked `bench-synthetic`. 10 scenarios are of this kind (`controllable: false`). Separately, 15 scenarios mark the cause `visible: false`, because the capsule has no attribute for it yet (open file descriptor limit, file size limit, cpu count under a cpuset, hosts file, lockfile pins, shell flavor, npm version). Those scenarios measure whether a diagnoser abstains.

**Validation.** A scenario is valid when both arms finish, the green arm passes, every red failure carries the declared signature (text or exit code), the failing trials equal the prediction from the hash gate, each arm's failure count passes an exact two-sided binomial test against the declared rate (each tail at least 0.0005, `BigInteger`), and the cause path appears in the capsule diff exactly when `visible` says so. A scenario that fails is listed in `data/dropped.json` and left out; its program is fixed, and no tolerance is loosened.

Dev scenarios ran in Docker on a Linux host, capsules captured with the Linux release executable (`capture --tools`) inside each arm:

| quantity | value |
| --- | --- |
| dev scenarios validated | 45 of 45; `data/dropped.json` is `[]` |
| trials run, both arms | 2,400 |
| red-arm failures | 912 |
| green-arm failures | 0 in every scenario |
| scenarios whose failing trials equal the prediction | 45 |
| scenarios with an overridden capsule value | 5 |
| scenarios with an invisible cause | 11 |

The first run had one drop, `build-npm-sbom-plain-1`, whose program sent npm's error message to `/dev/null`, so the red arm failed without the declared signature (green 0 of 20, red 20 of 20). The program was fixed and the batch rerun; the final run has no drops.

**Sealed test split.** A scenario is in dev when the first four bytes of `sha256(id)`, read as an unsigned integer, mod 100 are under 60. That gives 45 dev and 31 test scenarios. The test list is sealed: the SHA-256 of the sorted test ids, each followed by a newline, is `457097f88491381de17a43a67a7f09ab3843d008c838f02db2e81b0c6d5b4068`, committed in `data/split.json` and pinned in `CommittedDataTest`. `prepare` refuses a test id, the calibration harness refuses it (`SealedException`), and a test asserts no result or capsule file exists for a test id. The test scenarios were never executed, run through the ranker or scored. They are validated only through the dev sibling instances of the same templates. The hash and the split rule were recomputed independently from `bench/data/scenarios.json` for this report (see Reproducing the Numbers).

Template ids were renamed (suffixes such as `-basic`) until every template had at least one dev instance and the dev share was near 60%; it is 59.2%. Only the hash rule was used, and no measurement.

---

## Evaluation and Limits

### Calibration on the Dev Split

The `bench` harness (`Observe.kt`, `Evaluate.kt`) runs the real `Ranker` on the capsule pair of each of the 45 dev scenarios and compares the result with the injected cause. The input that matters is the probed pair: the passing and the failing baseline that `solve` captured inside its containers with `capture --probes` (71 probes each, stored in `bench/data/probed/`). 41 scenarios have a cause (4 are controls). On the probed pairs the ranker produced 76 candidates, 25 of them the cause. In 13 of the 45 scenarios a probe differs between the two sides, which links 19 candidates to a probe that reads their attribute. Only four probes ever differ in these pairs (`resources.locale-env` in 6 scenarios, `resources.cgroup-memory` in 6, `process.timezone` in 5, `resources.cgroup-cpu` in 4), and each reads exactly the attribute that changed (`env.LANG`, `cgroup.memory.max`, `env.TZ`, `cgroup.cpu.max`). No scenario matches a catalog rule: the dev scenarios fail on the older side (Python 3.12 to 3.9, Node 20 to 16) and the catalog rules describe upgrades. Two comparison inputs share the harness: the same pairs with the probes removed, which gives a paired measurement of what probes do, and the original capsules the scenarios were validated with (no probes, 86 candidates, 29 of them the cause). The probed pairs come from the `reproduce` containers, so the five scenarios whose cause a container cannot set (kernel, CPU model, browser engine) have no candidates in them; the original capsules keep those.

Folds are the first four bytes of `sha256("fold:" + id)` mod 5. A model replaces the shipped one only if its out-of-fold Brier score is at least 2% lower and its out-of-fold MRR is not lower, and the Brier score is taken over the rankings that print a probability (those whose candidates all rest on attribute changes, 51 candidates).

Order of the hand-set ranker on the 41 scenarios with a cause (Wilson 95% intervals; "P" is the exact chance that a random order does at least as well, one-sided, not corrected for the three inputs):

| input | order | top-1 | top-3 | MRR | P, top-1 / top-3 |
| --- | --- | --- | --- | --- | --- |
| original, no probes | random (expected) | 0.456 | 0.662 | 0.557 | |
| | diff order, alphabetical | 19 of 41 (0.32 to 0.61) | 27 of 41 (0.51 to 0.78) | 0.574 | |
| | hand-set | 23 of 41 (0.41 to 0.70) | 28 of 41 (0.53 to 0.80) | 0.624 | 0.022 / 0.368 |
| probes removed | random (expected) | 0.371 | 0.564 | 0.466 | |
| | diff order | 15 of 41 (0.24 to 0.52) | 22 of 41 (0.39 to 0.68) | 0.464 | |
| | hand-set | 20 of 41 (0.34 to 0.64) | 24 of 41 (0.43 to 0.72) | 0.539 | 0.009 / 0.368 |
| probed | random (expected) | 0.371 | 0.564 | 0.466 | |
| | diff order | 15 of 41 (0.24 to 0.52) | 22 of 41 (0.39 to 0.68) | 0.464 | |
| | hand-set (shipped) | 19 of 41 (0.32 to 0.61) | 24 of 41 (0.43 to 0.72) | 0.522 | 0.034 / 0.368 |

The control arm of this evaluation is the three baselines (random order, alphabetical diff order, the logistic reading of the score). The ranker is deterministic, so the only spread is the fold split. An earlier version of this report called the top-1 order indistinguishable from chance because the interval of the hand-set rate contains the random expectation. That was too strong. The exact test puts the chance of 19 or more hits under a random order at 0.034 (0.009 and 0.022 on the other two inputs), so the top of the order is better than random, and only just. At top-3 it is not: 24 hits against 23.1 expected, P = 0.37. The gain over alphabetical order is 4 scenarios (19 against 15).

**Probes.** Probes change the order of exactly one scenario, for the worse. In `env-pool-size-decoy-2` the cause `env.DB_POOL_SIZE` ranks first without probes and third with them, because its two decoys `env.LANG` and `env.TZ` really changed, the locale probes that read them differ, and so the decoys reach `plausible` while the cause stays at `difference`. Probes help no scenario. A probe says that an attribute it reads behaved differently. It cannot say that the attribute caused this failure, and the dev scenarios inject decoys that are real differences, so a probe agrees with them as much as with the cause. In a scenario with one candidate there is nothing to reorder. The tier is compared before the score, so no probe weight can change the order (`noProbeRatioChangesTheOrderBecauseTheTierComesFirst`).

Probes were first linked to candidates through their dimension. They are now linked through the attribute path they read (`ProbeLinks`, see Ranking and Bundles). The two rules give the same dev results: every ranking, tier, top-1, top-3, MRR and false-confidence rate is identical before and after (84 model blocks of `evaluation.json` compared; only a new `with_dimension_probe` group was added, and `fit.json` is byte-identical). The new rule demotes a candidate to `correlated` when the only probe that differs in its dimension does not read its path; on the 19 probe-linked dev candidates that demotion fires on none. Three candidates (`env.LANG` in 3 scenarios) also carry a dimension-level probe, and each is read by `resources.locale-env` as well, so they stay `plausible`. The change removes an unsound rule and does not lower the false confidence rate below.

**False confidence.** On the dev split without probes the rate of a confident wrong answer was 0 by construction, since nothing could reach `plausible` or `known`. With probes it is not 0.

| quantity | value |
| --- | --- |
| candidates by tier | `difference` 57, `plausible` 19, `known` 0 |
| candidates that are the cause, by tier | `difference` 16 of 57 (0.28), `plausible` 9 of 19 (0.47) |
| scenarios whose top candidate is `plausible` and wrong | 7 of 45 (Wilson 0.08 to 0.29) |
| `plausible` candidates that are wrong | 10 of 19 (0.32 to 0.73) |
| controls with a `plausible` candidate | 1 of 4 (`control-locale-noise-fixed-1`, whose decoy `TZ` does differ and the timezone probe sees it) |

The intervals for the two cause rates overlap, so the data does not show that `plausible` is better evidence than `difference`. In plain terms, the `plausible` tier is not reliable on this benchmark. More than half of what it names is wrong, and a user reading "plausible" as "probably right" would be misled about as often as not. The 10 wrong `plausible` candidates are decoys that changed, and the probe is right about them: it separates attributes that behaved differently from attributes that did not, and does not separate a cause from a bystander. Only a link through the symptom (the probe whose output relates to the failure) could, and it was not tried. The ranking still prints the tier and the mechanism that earned it, and prints no probability for it.

**Re-weighting is refuted.** The probe, rule and spectrum weights were refit out of fold and none replaced the hand-set value.

- A fitted probe ratio (smoothed odds of a probe candidate being the cause, 9 of 19 against 25 of 76) is 1.82, with folds from 1.33 to 3.92. It ties the shipped model on the rankings that print a probability, where the probe never occurs. On all rankings its 3.8% lower Brier is 9 scenarios better, 9 worse and 13 tied.
- Cross-validated Brier on the rankings that carry a probe (25 candidates) at probe ratios 1, 1.5, 2, 3, 4, 6 (the hand-set value) and 8 is 0.177, 0.181, 0.186, 0.196, 0.203, 0.213 and 0.218, against 0.187 for "one of n". The hand-set ratio reads worse than "one of n" there. A ratio of 1 (the probe is no evidence) is best, by 5% on 25 candidates, which is not a result.
- Rule weights and spectrum weights are untestable here: no dev scenario matches a rule or has a history. They keep their hand-set values by construction.
- Fitted per-dimension priors lose again: out of fold they reach 17 of 41 at top-1 and MRR 0.485 against 19 of 41 and 0.522.

The weights stay hand-set. This refutes one mechanism, that a fitted weight improves the order or the printed probability on this benchmark. It does not close the objective of weighing probe evidence. A probe linked through the symptom it relates to was not tried, and the data here says only that probes linked through the attribute they read carry nothing beyond the attribute change.

**Probability readout.** Models on the rankings that print a probability (51 candidates), Brier score out of fold unless stated:

| model | Brier |
| --- | --- |
| uniform 1/n | 0.163 |
| hand-set, p = logistic(score) | 0.344 |
| constant base rate | 0.227 |
| softmax of the scores, none weight 1 | 0.150 |
| softmax and a fitted none weight (shipped; fit 0.25), in sample | 0.143 |
| softmax and a fitted none weight (shipped; fit 0.25) | 0.149 |
| isotonic and fitted-prior variants | 0.178 to 0.245 |

The shipped readout is 8% below a uniform guess and is mostly a count of rivals. It beats the logistic reading, the base rate and every isotonic map. Reliability, out of fold, in five bins of width 0.2 (candidates, mean predicted, observed): 15, 0.116, 1; 21, 0.247, 4; 7, 0.461, 3; 6, 0.700, 6; 2, 0.923, 1. The none weight was fit on this benchmark's mix, where 20 of 45 scenarios have no cause (4) or one the ranking cannot list (16). It says nothing about how often real failures have no listed cause. On the drangler pair the eight probabilities add to 0.988, a claim of 98.8% that one of eight changes is the cause, which the case data does not support. The probability stays attribute-only.

Other facts of the shipped model on the probed pairs, out of fold. Top-1 by class: single 5 of 15, bundle 6 of 9, decoy 3 of 9, intermittent 5 of 8. The cause ranks above every decoy in 3 of 7 decoy scenarios (4 of 7 without probes). In the 8 bundle-class scenarios with a ranked cause, 8 of 8 put it inside a bundle; in the other classes the cause shares a bundle in 3 of 17 (7 of 17 without probes, since a probe breaks ties that the ranker would otherwise bundle). The 5 manual scenarios have no candidates at all in the probed pairs. Reversing green and red on the probed pairs gives 5 candidate rule matches in 3 scenarios, 2 printed `known` and 22 `plausible`; an inferred-basis rule never printed `known`, and the reversed pairs have no ground truth, so the accuracy of those tiers is unmeasured.

Fold assignment is by id hash, so sibling instances of one template can land in different folds, which makes the out-of-fold figures optimistic. The family of probability maps was chosen after seeing that per-candidate maps lost, so cross-validation protects the fit but not the choice of family. The probed run is a second measurement on the same 45 scenarios, not independent data.

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

### The Drangler Case

The drangler case is a CI-versus-local divergence: the reported symptom is that an end-to-end suite passes on a developer machine and fails on the GitHub runner. That report is not recorded in the fixtures. The fixtures are 8 sanitized runs of the Docker end-to-end workflow, all scheduled: 5 passing runs at commit `558d2a6` and 3 failing runs at a later commit, `df6dce4`. The change between the last green commit and the first failing one touches `bun.lock` and `package.json`, and its diff lists, among others, `wrangler` `^4.123.0` to `^4.146.0`, `prettier-plugin-sh` `^0.19.0` to `^0.20.0`, and a new dependency `@napi-rs/keyring`.

What the data shows: the hosted runner image moved from `20260920.314.1` to `20260927.320.1` and the provisioner from `20260828.587` to `20260901.588`, a new `Set up Node` step prints Node and npm versions, and three dependency entries changed (`@napi-rs/keyring` added, `prettier-plugin-sh` and `wrangler` raised), all between the last green and the first red. All eight candidate changes are present in all three red runs and absent from all five green ones. The data cannot separate them.

What the logs add: the first red run reports 3 failed test files and 4 failed tests. Two failed tests are in `to-worker.spec.ts`, which ran with the same 8 tests in every green and passed there; in the reds it ran 8 tests with 2 failed in each. Two are in a `pinned production modules` group of `modify.spec.ts`, a group the greens do not contain (`modify.spec.ts` ran 3 tests in the greens and 8 in the reds, with 2, 1 and 2 failures). The third failed file is `preview.spec.ts`, absent from the greens and present in the reds with its 3 tests skipped. So the greens executed the failing step and ran one of the failing files in full, and did not run the other failing tests, which exist only on the later commit. The fixtures compare the runner at two commits, so they cannot test the reported divergence, which is the same commit on the runner against a developer machine. Only that comparison would separate an environment cause from the new tests. No local capture of the case exists in the repository. `--frame environment` and the planner's local frame exist and are covered by synthetic tests only.

The pair diff has 9 static changes. The tool ranks 8 of them in one bundle and names none. The order inside the bundle comes from the dimension prior: `tool.node.version` leads because `runtime` has the highest weight, and it is an added attribute from a new step, not a version change. `tool.yarn.version` was also added but has no dimension and is never ranked, so the bundle undercounts the changes by one. `ci.provisioner.version` and `ci.provisioner.build-date` are one change counted twice; the planner merges them. Over the run history the same transition is a bundle of the 8 changed attributes and 3 added steps (`Set up Node`, `Post Set up Node` and `Dump Host State on Failure`), and the data does not separate any member of it from the rest: each is present in all three failing runs and in none of the five passing ones.

### Threats to Validity

- The benchmark is synthetic. The injected cause is ground truth because the scenario was built around it. Real failures have causes that nobody injected and that may lie outside any capsule.
- Intermittency is a hash gate on the fault, so no scenario contains a real race, and five scenarios carry capsule values that were written in and not measured.
- The 31 sealed scenarios were never run. Their validity is inferred from dev siblings.
- Weights are hand-set. The ranking weights, the planner's cost weights, its 25% unknown prior and the trial-plan thresholds are guesses with a written reason, and none was calibrated.
- Rule sources are cited and not reproduced. Rule fixtures are synthetic. Ten of 32 rules have a symptom that rests on the mechanism alone.
- The catalog fired no rule on any real capsule pair in this report. Real multi-rule captures were not tested.
- A probe links to a candidate through the attribute path it reads, a table that is a reading of the probe sources and not a measurement, and the link ignores the symptom. A differing probe supports a decoy that really changed as much as the cause.
- `correlated` needs the history to carry step data. Without the run JSON joined to the logs, every green's steps are unknown, all five are excluded as `NO_STEP_EVIDENCE`, and all eight drangler candidates drop to `difference` (`withoutStepEvidenceInTheGreensTheDranglerFallsBackToDifferences`).
- The history ranking assumes the failing runs share one failure signature; the caller must group them.
- Atlas cells are single runs, and the 13 columns come from five machines and two CPU families, so a column describes a machine as much as a target (see Platform and CPU Findings). The Wasm Node column was recorded on Apple Silicon and the browser columns on x86-64, which confounds engine and CPU. Linux Native columns depend on the host that linked the executable. The displayed source of a probe and the code that runs are two hand-written copies; the ladder measured 1 of 38 probes differing between them, by a class name. Probe classification rests on the pages the authors read, with at most the first 8 tracker results of each query; Native's Unicode table version and whether Native and Wasm call libm or the JS engine for math are unverified.
- No workflow has run on GitHub: `build.yml`, `atlas.yml`, `release.yml` and `docs.yml` are checked with `actionlint` and their steps ran by hand. The Linux test suites ran in containers and the mingw ones under Wine, which is not Windows; real Windows ran nothing (no test, no installer, no registry edit), and neither did Safari or a signed or notarized build. The Linux arm64 executable was cross-linked, and a Kotlin/Native build on an arm64 Linux host was not run.
- Capture removes the account name, the host name and the home path only where they appear in the capturing host's own `env.*`. Probe transcripts and labels are not rewritten, and emails and account ids are not recognized. `--keep-identifiers` keeps everything.
- The Lab ran each of the 45 dev scenarios four times (180 runs, three reseeded). 0 false CONFIRMED bounds the per-run rate at 1.65% with 95% confidence on these synthetic scenarios and says nothing about real failures; an exact repeat of the original draws was not run. The loop needs two `SUPPORTED` tests to confirm (forward and reverse), which makes a false CONFIRMED on a decoy rare by design (about 0.25% at alpha 0.05 if the two tests are independent), but that figure is arithmetic and was not measured.
- The pilot of 8 trials sizes the arms, and for intermittent scenarios its estimate moved 2 of 45 scenarios between STUCK, NARROWED and CONFIRMED across four runs. A larger or sequential pilot was not tested.
- The arms are `reproduce` containers, not the scenario's own: they differ in an apt locales layer, image tags and the missing `--network none`. The harness that ran the dev scenarios added a wrapper that applies the red arm's fault gate to both arms, so a scenario whose cause a container cannot vary fails in both arms, which is what a container can show.
- `solve` assumes one cause, runs locally only, retries an infrastructure error once, never repeats an `INCONCLUSIVE` experiment, and caps split arms at 3. It has no CI mode. Its `--fail-when` pattern uses the regex semantics of the target it runs on.
- When `--run` names no toolchain, `reproduce` takes the first of its image table, so a container for a C compiler scenario with a distribution-bundled Python picked a Python tag that does not exist; the run stopped with an executor error and no verdict. The evaluation names the toolchain in the first word of `--run`.
- `verify` shows that an archive agrees with itself. It does not show that the experiments ran, and a consistent rewrite of every file verifies. `ranking` and `plan` are re-derived for pair cases only. The `verdict` rules it applies are the loop's own, read back.
- The four `.driftcase` fixtures have no `reproduce/` directory, and the Windows path handling of `pack` and `verify` and the Linux executable running them were not tested.

### Tests

Counts from the test result files of the last run of each task (`*/build/test-results/*/*.xml`), read on 2026-10-07 between 05:30 and 05:36 local time; the files were written between 05:07 and 05:27:

| module | JVM | macOS arm64 Native | Wasm on Node |
| --- | --- | --- | --- |
| `core` | 492 | 492 | 492 |
| `scan` | 91 | 91 | 91 |
| `bench` | 108 | 76 | 76 |
| `cli` | 243 | 222 | 203 |
| `host` | 11 | 15 | 8 |
| `studio` | 41 | - | 28 in Chrome |
| `tools` | 53 | - | - |
| `build-core` | 74 | - | - |

Every count has 0 failures and 0 skipped. The platform columns differ for `host` and `cli` because some tests exist only for the targets that can write files, run commands or open sockets, and for `bench` because the YAML, Docker and binomial tests are JVM-only. The Lab's tests run in common code on all three targets: `SolveTest` 28, `IngestTest` 10, `ArchiveTest` 18 and `VerifyTest` 16 in `core`, and `SolveCommandTest` 9, `ArchiveCommandTest` 8 and `DockerExecutorTest` 7 in `cli`. They run against a fake executor; no test builds an image or starts a container. The installer tests number 86 on the JVM, 70 on macOS Native and 65 on Wasm (19 of the JVM tests run `install.sh`), and the serve tests 78, 76 and 64.

---

## Reproducing the Numbers

Build and gates, from the repository root with JDK 21:

```sh
./gradlew build spotlessCheck checkLineLength
./gradlew jvmJacocoAggregateReport verifyCoverage coverageSummary
./gradlew sharedRatio
./gradlew :cli:linkReleaseExecutableMacosArm64
./gradlew atlasSite
```

`coverageSummary` writes `build/coverage/summary.txt`, which holds the coverage table and the per-module test counts; `sharedRatio` prints the shared-code table. The CLI examples above run the linked executable, `cli/build/bin/macosArm64/releaseExecutable/drift.kexe`. A JVM run is `./gradlew :cli:jvmRun --args="..."`. The figures here were read on 2026-10-07 between 05:07 and 05:38 local time on a Mac, from the executable linked at 05:32.

The sealed split and the dev rule, recomputed from the scenario list:

```python
import json, hashlib
ids = [s["id"] for s in json.load(open("bench/data/scenarios.json"))]
dev = {i for i in ids if int.from_bytes(hashlib.sha256(i.encode()).digest()[:4], "big") % 100 < 60}
test = sorted(set(ids) - dev)
print(len(dev), len(test), hashlib.sha256("".join(i + "\n" for i in test).encode()).hexdigest())
```

This prints `45 31 457097f88491381de17a43a67a7f09ab3843d008c838f02db2e81b0c6d5b4068`.

| figure | value | command or file |
| --- | --- | --- |
| core tests per target | 492, 492, 492 | sum of `tests` in `core/build/test-results/<task>/*.xml` |
| other module test counts | table in Tests | same files, one directory per task |
| shared-code ratio | 88.4% (18,824 and 2,470 lines) | `./gradlew sharedRatio` |
| JVM line coverage | 95.1%, 13,582 of 14,281 | `./gradlew jvmJacocoAggregateReport verifyCoverage coverageSummary`, then `build/coverage/summary.txt` |
| no `Double` or `Float` in common code | 0 | `grep -rn "Double\|Float" core/src/common/main bench/src/common/main` |
| fixed-point and hash goldens | table in Design Principles | `core/src/common/test/.../GoldenTest.kt` |
| `drift diagnose` hash | `0bd53221...b05b` | `drift diagnose fixtures/drangler/capsules/36702683742.json fixtures/drangler/capsules/37114464625.json \| shasum -a 256`; `DriftCommandTest.DIAGNOSE_SHA` |
| capsule hash in `reproduce` | `540e5c22...0531` | `drift reproduce fixtures/lab/node-alpine.json --run "npm test" --out out`; equals the sha256 of the file without its final newline |
| case files match the pinned hashes | 11 of 11 | `drift diagnose ... --case case`, then `shasum -a 256` per file against `CaseCommandTest.CASE_SHAS` |
| `rule sha256` of the drangler case | `8bf205db...4559` | `drift case show case`, `drift next case --detail detail` |
| CLI size and commands | 6,742,776 bytes; 14 subcommands | `ls -l cli/build/bin/macosArm64/releaseExecutable/drift.kexe`, `drift --help` |
| capture counts (86, 99, 71) | this Mac | `drift capture`, `drift capture --tools`, `drift capture --probes --tools` |
| masked and identifier-bearing env attributes | 13 masked of 78; with `--keep-identifiers` 18 hold the account name and 16 the home path; by default 0 and 0 | `drift capture` and `drift capture --keep-identifiers`, then count `env.*` values equal to `<redacted>`, containing the account name, containing `$HOME` |
| capture stdout bytes | strict JSON parse succeeds; 1 raw U+0085 and 1 raw U+2028 | `drift capture --probes --tools > c.json; python3 -c "import json; json.load(open('c.json'))"`; count the UTF-8 bytes of both code points in `c.json` |
| rules, fixtures, sources, severities | 32; 80 (33/47); 39; 12/17/3 | `rules/*.yml` (count `severity:`, `- name:` under `fixtures:`, `kind:` under `provenance:`) |
| rule symptom basis | 25 verified, 10 inferred; 10 rules | `grep -h "basis:" rules/*.yml` |
| transcript fixtures | 32 files, 12 tools | `find fixtures/transcripts -type f` |
| probes | 71 = 33 + 38 | `drift capture --probes` |
| probe links | 33 entries | `core/src/common/main/.../rank/ProbeLinks.kt`; `ProbeTest.everyCatalogProbeHasALinkEntryNamingRealAttributes` |
| Atlas, 13 columns | 38 probes, 23 divergent, 15 identical, sha256 `363716f2...64c4` | `drift atlas build fixtures/atlas/*.txt --out atlas13.json` |
| Atlas, 18 columns | 23 and 18 divergent, 575,879 bytes, `de6b25ca...ccebb` | `drift atlas build fixtures/atlas/*.txt fixtures/atlas/2.5.0-beta1/*.txt --out atlas.json` |
| Atlas, 3 columns | 23 divergent, 734 lines per column, 107 differing lines | `drift atlas build fixtures/atlas/jvm.txt fixtures/atlas/macos.txt fixtures/atlas/wasm.txt --out atlas3.json`, then count `cells[].lines` that differ across columns; 126 over the 13 columns of `atlas13.json` |
| Atlas ladder | 65 columns, 2,470 cells, 33 divergent, 5 identical, `5c830fd4...8d55` | `drift atlas build fixtures/atlas/*.txt fixtures/atlas/2.5.0-beta1/*.txt $(ls fixtures/atlas-ladder/*/*.txt \| grep -v 2.4.20/linux.txt) --out ladder.json` |
| classification | 18 / 4 / 16; 6 / 4 / 13 among the 23 | `summary.byClassification` in `atlas.json`; `drift atlas show atlas.json` |
| ladder matrix | table in The Kotlin Portability Atlas | count `ok` cells per (version, target) in `ladder.json` |
| Beta convergence | 5 probes identical; 5 more changed in the Wasm column | compare cell hashes per probe between `2.4.20` and `2.5.0-Beta1` in `atlas.json` |
| this device against the dataset | 38 own, 38 iOS, 37 Linux arm64, 36 Linux x64, 21 JVM, 25 Wasm; 37 for `macos@2.5.0-Beta1` | `drift atlas record --out t; drift atlas compare atlas.json t/macos.txt` |
| macOS transcript equals fixture | identical | `cmp t/macos.txt fixtures/atlas/macos.txt` |
| column pairs | `linux` and `linux-ubuntu` 0 probes; `linux` and `macos` 2; `linux` and `linux-arm64` 1; `jvm` and `jvm-21` 3; `jvm-17` and `jvm-21` 6; `jvm-21` and `jvm-25` 2; each browser and `wasm` 1 | compare the `@@ <probe id>` sections of the files in `fixtures/atlas` and `fixtures/atlas-extra` |
| value changes on the ladder | `hello` 604317365 to 99162322; `boolean` 1 to 1231; JS IR `Long` | `diff` of the probe sections of `fixtures/atlas-ladder/1.4.32/linux.txt` and `1.5.32/linux.txt`, `1.8.22` and `1.9.25`, `js-ir` `1.5.32` and `1.6.21` |
| Atlas site | 72 files, 14,632 KiB, 39 probe pages, no `<script>` on a probe page; identical hashes on a second run | `./gradlew atlasSite`; `find build/atlas-site -type f \| wc -l`; `du -sk build/atlas-site`; `shasum -a 256` of every file, then `./gradlew atlasSite --rerun` and again |
| Dokka site | 2,954 pages; 14 of 51,678 relative links broken | `./gradlew :dokkaGenerate`; count `*.html` and unresolved relative `href` targets in `build/dokka/html` |
| DriftBench | 76 scenarios, 39 templates, 45 dev, 31 sealed | `bench/data/scenarios.json`, `bench/data/split.json` |
| dev validation | 45 of 45, 2,400 trials, 912 red failures, 0 green failures | `bench/data/results/*.json` (`valid`, `arms.*.trials`, `arms.*.failures`) |
| order table, three inputs | 19, 20 and 23 of 41 top-1 for hand-set | `bench/data/calibration/evaluation.json` (`original`, `probes_removed`, `probed`: `models[id=hand-set].in_sample`); Wilson 95% intervals computed from the counts |
| exact tests against a random order | P = 0.034 (probed), 0.009, 0.022 at top-1; 0.368 at top-3 | `evaluation.json` `versus_random.top1.p_at_least_micro`, `.top3` |
| probe effect | 1 scenario moves, rank 1 to 3; none improves | compare the `ranked` order of `env-pool-size-decoy-2` and every other scenario in `probed` and `probes_removed` |
| attribute link changed nothing | `evaluation.json` sha256 `66f6c529...7dfc`; `fit.json` unchanged `29712e5a...6c11` | `shasum -a 256 bench/data/calibration/*.json`; `CalibrationTest.everyDevProbeAgreementReadsTheCandidatePathSoTheLinkChangesNoTier` |
| tiers and false confidence | 57 / 19 / 0; 7 of 45; 10 of 19 | `evaluation.json` `probed.scenarios[].ranked[].tier`, `.cause` |
| probe ratio sweep | 0.177 at ratio 1 to 0.213 at 6; 0.187 for "one of n" | `evaluation.json` `probe_sweep` |
| readout Brier | 0.149 shipped; 0.163 uniform | `evaluation.json` `probed.models[].cross_validated.covered.brier` |
| none weight, fit provenance | 0.25; seed 20261006 | `fit.json` `probability.none`, `provenance`; `Weights.NONE`, `Weights.FIT` |
| dev inputs | 76 candidates, 25 causes, 13 scenarios with a differing probe, 0 rule matches | `fit.json` `provenance.inputs.probed` |
| drangler pair ranking | 8 candidates, bundle-1, probabilities 0.144578 and 0.120482 | `drift diagnose ... --detail full` |
| drangler history ranking | 8 candidates, `ef 3, nf 0, ep 0`, coverage 862,068 | `RankHistoryTest.theDranglerTransitionIsOneBundleNoCandidateIsTheCause` |
| failing test files in the logs | `to-worker` 8 tests (greens) and 8 with 2 failed (reds); `modify` 3 and 8; `preview` 3 skipped in reds | `HistoryFixtureTest.theStepRanInEveryGreenButTheFailingTestsAreNotAllInThem`; `fixtures/drangler/logs/*.log` |
| planner, 9 + 2 experiments, EIG 0.506 bits | e9 first, 157 cost points | `drift next case --detail full`; `case/experiments/plan.json` |
| Fisher and power | 35/1001 = 0.0350; 3 of 7: 0.0962; 1808/2187 = 0.8267 | closed forms: `C(7,4) / C(14,4)`, `C(7,3) / C(14,3)`, `sum C(7,k) (2/3)^k (1/3)^(7-k)` for k = 4 to 7 |
| EIG recomputed | 0.50595 bits, P(reproduced) 0.470714 | prior masses in `plan.json`, power 1808/2187, alpha 0.05 |
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
| CLI release archives | 1,984,195 / 2,107,037 / 1,856,352 / 2,095,181 bytes | `.github/release/stage.sh archive <exe> <name>` in an empty directory, then `stage.sh sums dist` and `shasum -a 256 -c SHA256SUMS` |
| installer script checks | 33 ok, 0 failed on the Mac | `sh tools/test-installers.sh cli/build/bin/macosArm64/releaseExecutable/drift.kexe <scratch dir>` |
| Studio installers | dmg 74,624,330; pkg 70,015,031; APK 7,203,057 | `ls -l studio/build/compose/binaries/main/{dmg,pkg} androidApp/build/distributions` (Linux packages: `./gradlew :studio:packageDeb :studio:packageRpm :studio:packageAppImage` in a Linux container) |
| `serve` checks | listeners `127.0.0.1` and `[::1]`; 403, 400, 404, 405 as listed; exit 0 on SIGINT | `drift serve --dir cli/build/serve/studio --port 18771`, then `lsof -a -p <pid> -iTCP -sTCP:LISTEN`, `curl` with `-H "Host: ..."` and `--path-as-is` |
| `serve` embedding sizes | 493,368 / 12,588,056 / 33,913,192 bytes | executables of a trivial program that embeds the 12.4 MB build as a gzip base64 string and as a raw base64 string; not in the repository |
| Linux and mingw test runs | 730 tests each on Linux x64 and arm64; Wine counts as listed | `./gradlew :core:linkDebugTestLinuxX64` (and the other modules), then run `<module>/build/bin/linuxX64/debugTest/test.kexe` in a Debian container; `linkDebugTestMingwX64` and `wine64 test.exe` |
| browser transcripts | byte-identical over 4 runs per engine | `node tools/browsers/run.mjs --engine chromium --headed --dist studio/build/dist/wasmJs/productionExecutable --out out` (under `xvfb-run` in the Playwright image); `cmp` between runs |
| studio tests | 41 JVM, 28 browser, 0 failed | `studio/build/test-results/*/*.xml` |

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

Rule sources are listed per rule in the `provenance` of `rules/*.yml`. The Atlas probe notes cite the Kotlin API pages, the JDK 21 javadoc pages and the ECMAScript sections their authors read. The Ochiai formula and the delta debugging algorithm are implemented from their standard definitions; the original papers were not opened for this report.
