# Advanced Usage: The Full CLI

This guide covers every `drift` command and flag for people who have already run `drift capture`. It shows scripting patterns, the JSON and text output levels, cases, reproduction, the Lab (`solve`, `ingest`, `pack`, `report`, `verify`), hands-off planning (`handsoff plan`), CI commands (`run`, `import`, `diagnose --against`, `bisect plan`, `bisect apply`), portability contracts (`contract create`, `contract verify`), issue triage (`triage`), upgrade preflight (`upgrade-check`), the Atlas, and the commands that install and serve Drift (`install`, `uninstall`, `serve`, `install.sh`, `install.ps1`). For the design and the measurements behind the commands, see [TECHNICAL_REPORT.md](./TECHNICAL_REPORT.md).

Every example below was run with the macOS release executable (`drift version 1.0.0 (macos)`) from a directory that holds `fixtures/` and `bench/`, against the files in [`fixtures/`](./fixtures/) and [`bench/data/`](./bench/data/). Output is copied from those runs; a line that holds only `...` marks output cut for length. Where a command prints to standard error, the example says so. The offline run in [Offline](#offline) used the Linux arm64 executable inside a Docker container. The `install`, `uninstall` and `serve` examples ran with a scratch home directory and a scratch prefix, never the real profile, registry or trust store. Their output shows the scratch home as `/home/you`, the scratch work directory as `/work` and the scratch release directory as `/release`. The Windows and Linux builds and the `.ps1` script were not run for this guide, and the sections say where a statement comes from a recorded run of someone else's.

The executable is `cli/build/bin/macosArm64/releaseExecutable/drift.kexe`; the [install guide](./docs/install.md) explains how to build it and how to download one. The examples call it `drift`; put it on `PATH` (see [Install and Uninstall](#install-and-uninstall)) or alias it.

## Command Index

| Command | Reads | Writes | Needs Docker |
| --- | --- | --- | --- |
| [`why`](#why) | this machine and a container, a machine over ssh, a saved capture, the latest failed CI run, a command or an issue text | text or JSON; `.drift/why` | no (`docker:` places use `docker exec`) |
| [`capture`](#capture) | this machine | a capsule on standard output | no |
| [`diagnose`](#diagnose) | two capsules | JSON or text, optionally a case directory | no |
| [`diagnose --against`](#against-a-baseline) | a baseline and a capsule, or this machine | JSON or text, optionally a case directory | no |
| [`diagnose --set`](#over-a-set-of-capsules) | two or more capsules, each passing or failing | JSON or text | no |
| [`compare`](#compare-two-sets-of-runs) | two capsule directories written by `run --repeat` | text or JSON | no |
| [`run`](#run) | this machine, a command | a capsule directory | no |
| [`import`](#import) | a saved GitHub Actions or GitLab CI log | a capsule directory | no |
| [`ci`](#ci) | the git checkout, `gh` or `glab` | a case directory and two capsule directories under `.drift/ci` | no |
| [`handsoff plan`](#handsoff) | a checkout, optionally a case from `ci` | text or JSON, optionally `--out FILE` | no |
| [`bisect plan`](#bisect) | two capsules | a plan file | no |
| [`bisect apply`](#bisect) | a plan file | the plan file | no |
| [`next`](#next-and-case-show) | a case directory | text | no |
| [`case show`](#next-and-case-show) | a case directory | text | no |
| [`reproduce`](#reproduce) | a capsule | `Dockerfile`, `run.sh`, `manifest.json` | no (it only writes files) |
| [`solve`](#solve) | two capsules | a case directory with a certificate | yes |
| [`ingest`](#ingest) | a case directory, result files | the case directory | no |
| [`pack`](#pack-report-and-verify) | a solved case directory | a `.driftcase` archive | no |
| [`report`](#pack-report-and-verify) | a `.driftcase` | `report.html` | no |
| [`open`](#open) | a `.driftcase` | Studio, or `<name>.report.html` next to the case | no |
| [`verify`](#pack-report-and-verify) | a `.driftcase` | text or JSON | no |
| [`contract create`](#create) | green capsules, optionally red capsules | a contract file | no |
| [`contract verify`](#verify) | a contract, a capsule or this machine | text or JSON | no |
| [`upgrade-check`](#upgrade-check) | a capsule or this machine, target versions | text or JSON | no |
| [`atlas record`](#atlas) | this machine | a transcript | no |
| [`atlas here`](#atlas) | this machine, the recorded columns | text, optionally a transcript | no |
| [`probe cloudflare`](#probe) | a built Worker directory | a transcript | no |
| [`triage`](#triage) | an issue body (a file or standard input) | text or JSON | no |
| [`atlas build`](#atlas) | transcripts | a dataset | no |
| [`atlas compare`](#atlas) | a dataset, a transcript | text | no |
| [`atlas show`](#atlas) | a dataset | text | no |
| [`atlas diff`](#atlas) | a dataset | text | no |
| [`install`](#install-and-uninstall) | this executable | a copy of it, shell profiles or the registry, a receipt | no |
| [`uninstall`](#install-and-uninstall) | the receipt | removes what `install` wrote | no |
| [`serve`](#serve) | a Studio web build directory | nothing (a local web server) | no |
| [`doctor`](#doctor) | this machine | text or JSON | no |
| [`help`](#help-and-completion) | nothing | text | no |
| [`completion`](#help-and-completion) | nothing | a shell script on standard output | no |

## Environment and Configuration

The CLI has no configuration file and no config flag. It reads the following.

| Source | Used by | Effect |
| --- | --- | --- |
| process environment (`env.*`) | `capture` | every variable becomes an `env.NAME` attribute; secret-looking values are stored as `<redacted>` |
| `DRIFT_RUNNER_LABEL` | `capture` | sets the `ci.runner.label` attribute |
| `DRIFT_RUNTIME_PLATFORM` | `capture` | overrides `runtime.platform`, which otherwise holds the build platform |
| `PATH` | `capture --tools` | the scanners run the tools they find there |
| `docker` on `PATH`, `sh` | `solve` | one `docker build` per arm, one `docker run --rm` per trial |
| `DRIFT_TRIAL=<n>` | set by `solve` | passed into each trial container; the command can read it |
| `DRIFT_NO_MODIFY_PATH=1` | `install` | same as `--no-modify-path` |
| `XDG_BIN_HOME`, `XDG_CONFIG_HOME` | `install`, `uninstall` | an absolute value replaces `~/.local/bin` and `~/.config` (the receipt lives in `$XDG_CONFIG_HOME/drift`) |
| `SHELL`, `ZDOTDIR` | `install` | choose which profile files get the `PATH` block |
| `PATH` | `serve --mkcert`, `serve --mdns` | finds `mkcert`, `dns-sd` or `avahi-publish` |
| `CAROOT` | `serve --mkcert` | read by `mkcert`; `drift serve` asks `mkcert -CAROOT` for the CA directory |
| `DRIFT_INSTALL_BASE_URL`, `DRIFT_VERSION` | `install.sh`, `install.ps1` | release location and version (see [Install Scripts](#install-scripts)) |

```sh
DRIFT_RUNNER_LABEL=ci-runner-7 DRIFT_RUNTIME_PLATFORM=container drift capture --label t \
  | jq -c '.attributes[] | select(.path | test("^(ci|runtime|drift)\\."))'
```

```
{"path":"ci.runner.label","source":"env","stability":"static","value":"ci-runner-7"}
{"path":"drift.platform","source":"drift","stability":"static","value":"macos"}
{"path":"runtime.platform","source":"env","stability":"static","value":"container"}
```

Defaults that matter:

| Flag | Default |
| --- | --- |
| `capture --label` | `capture` |
| `diagnose --detail` | none (canonical JSON) |
| `diagnose --frame` | `sha` |
| `diagnose --trial-minutes`, `--failures`, `--runs` | 5, 1, 1 |
| `next --detail`, `case show --detail` | `detail` for `next`, `summary` for `case show` |
| `reproduce` default env copy | `LANG`, `LC_*`, `TZ` |
| `solve --budget` | `trials=300,minutes=45` |
| `solve --pilot` | 8 trials per arm |
| `solve --trial-minutes` | 1 |
| `solve --timeout` | 120 seconds per trial |
| `solve --case` | `drift-case` |
| `solve --workspace` | `.` |
| `solve --detail` | `summary` |
| `compare --detail` (`atlas compare`) | `summary` |
| `install` scope | `--user`, unless the process is root or elevated, then `--global` |
| `install` directory | `~/.local/bin` (user), `/usr/local/bin` (global); Windows `%LOCALAPPDATA%\Programs\Drift` and `%ProgramFiles%\Drift` |
| `serve --port` | 8080 (8443 with TLS) |

`diagnose --case`, `solve --case` and `ingest` refuse to write into a directory that already holds a `case.json` (`ingest` rewrites the case it is given).

## Why

`drift why [--there PLACE] [--fails-here] [--detail | --json] [-- COMMAND ...]` and `drift why FILE` answer "why does this work here and fail there" without a capture file, a case directory or a flag. The [guide](./docs/why.md) walks through the four flows; this section lists what each one calls and writes. `drift why --reply [--session FILE]` continues a session that `drift why` wrote when it asked for a fact: it reads standard input as the answer to the first open ask, parses it with the scanner that runs the same command, and prints an answer or the next single ask. `drift why --error TEXT` alone, outside a git checkout, reads the text as a report.

| Arguments | Flow | Built from |
| --- | --- | --- |
| none, in a git checkout | the latest failed CI run against this machine, with the last passing run as extra evidence | the `ci` run selection and importers, `capture --tools --project`, `diagnose` |
| `--there docker:NAME[:DIR]` | this machine against a running container | `capture --tools --project` over `docker exec` |
| `--there user@host` | this machine against another machine | `capture --ssh` (read-only, opt-in, as described in [Remote Capture over SSH](#remote-capture-over-ssh)) |
| `--there PATH` | this machine against a saved capture | `capture --out`, read back as `diagnose` reads a capsule |
| `-- COMMAND` | this machine now against this machine when `COMMAND` last passed | `capture --tools --project` and the ranking and plan of `diagnose --against` |
| `FILE` or `-` | an issue or an error report | `triage` |

The first line of output names the flow. The answer has the verdict, up to three lines of evidence and what to try next. `--detail` appends the ranking and plan that `diagnose --detail` prints. `--json` prints the output of `diagnose` with a `plain` object (`flow`, `direction`, `certainty`, `verdict`, `evidence`, `findings`, `next`). The same inputs give the same bytes, and the exit status is 0 whenever Drift gave an answer, including "cannot tell".

Choices the flows make for you:

- **Scanners on.** `why` captures with the toolchain scanners (`--tools`) and the project scan (`--project`), so a missing tool, a tool version or a locked package shows up in a first answer. Probes (`--probes`) stay off: they run many programs on each side and need transcripts from both. Over ssh the scanners are not available, and the answer says so.
- **Noise removed.** Host name, account, terminal and `PATH` variables, capture bookkeeping and, in the CI flow, the runner's own labels are dropped before ranking. `NODE_VERSION` and its kin are dropped when `tool.node.version` is present.
- **Direction.** The default assumption is that it works on the first side (here) and fails on the second (there); `--fails-here` swaps them. With `-- COMMAND` after `--there docker:NAME`, the exit codes of the two runs decide, and the failing side's output conditions the ranking: phrases such as "is not a function", "No module named", "not found", "Too many open files", "Connection refused" and "Permission denied" point at the kind of attribute they fit.
- **The container.** `docker:NAME` runs `docker inspect` and then one `docker exec NAME ...` per fact. It never starts, stops or changes a container, copies nothing into it and needs only `cat` and `env` in the image. A command that the image lacks reads as absent.
- **A saved capture.** A fact family that the saved capture does not hold (for example `tool.` in a capture made without `--tools`) is left out of the comparison on both sides, and the answer says which.

Files it writes: `.drift/why/baseline` (a capsule directory, after a passing `-- COMMAND`) and `.drift/why/case` (a case directory, when the answer lists several differences, so `drift next .drift/why/case` works). It never edits a tracked file.

```sh
drift why -- sh check.sh
TZ=Asia/Tokyo drift why -- sh check.sh
```

```
drift why: running sh check.sh here

It most likely fails now because the time zone differs: TZ is Asia/Tokyo now and not set when it
  last passed; the error text points at it.

What I found:
  Time zone (TZ): not set when it last passed, Asia/Tokyo now

What to try next:
  Unset TZ now, or make the code name its time zone.
  To be sure, change only that and run it again now.
```

Problems end in one sentence and exit code 1: a container that is not running (`docker start NAME` is yours to run), a name that is not a container, no Docker, an ssh host that cannot be reached (the ssh messages of [When ssh Fails](#when-ssh-fails)), a saved capture that cannot be read, no git checkout or remote for the CI flow, `gh` or `glab` missing or not logged in, and a bare command without `--`.

## Capture

`drift capture` prints this machine's capsule as canonical JSON: sorted keys, no whitespace, one line. The output is the exact bytes that the capsule hash covers, so `jq`, `python3 -m json.tool` and `shasum` all work on it as written.

```sh
drift capture --label demo > cap.json
python3 -m json.tool cap.json > /dev/null && echo valid
shasum -a 256 cap.json
drift capture --label demo | shasum -a 256
```

```
valid
d97d073465e339c52eb89708936692297998cac742a0845ae5c86b215def0bd7  cap.json
d97d073465e339c52eb89708936692297998cac742a0845ae5c86b215def0bd7  -
```

Two captures taken one after another in the same shell hash the same (the digest above is from one shell and differs on every machine and in every environment). A capsule changes when the machine does (a new tool, a changed environment variable), so compare hashes only between captures you expect to match.

| Flag | Effect |
| --- | --- |
| `--label TEXT` | name stored in the capsule (default `capture`) |
| `--tools` | also run the toolchain scanners (`tool.*` attributes such as `tool.git.version`, `tool.java.version`, `tool.npm.version`, `tool.python.gil-disabled`, `tool.openssl.version`, `tool.ssh.version`, `tool.curl.version` and, for clang, `tool.cc.vendor` as `apple` or `llvm`; see [Tool and System Keys](#tool-and-system-keys)) |
| `--project` | also read dependency and platform versions from the manifests and lockfiles in the working directory, and the imports and call sites of its source files (`project.*` attributes, see [Project Facts](#project-facts) and [Source Facts](#source-facts)) |
| `--probes` | also run every probe and store its transcript under `probes` |
| `--keep-identifiers` | keep the account name, host name and home path |

```sh
drift capture --label demo --tools --probes > cap-full.json
jq '.attributes | length' cap.json cap-full.json
jq '.probes | length' cap.json cap-full.json
```

```
90
117
0
71
```

The counts are from one Mac and vary with the tools installed. `--probes` adds 72 probe records; each holds `id`, `status`, a `hash` and the `transcript`.

**Anonymization.** By default the capsule rewrites the home path to `~`, masks the account and host names, and marks the affected attributes `source: "env (anonymized)"`. Values whose names look like secrets (for example `CLOUDFLARE_API_TOKEN`) are stored as `<redacted>`.

```sh
drift capture --label demo | jq '[.attributes[] | select(.source | test("anonymized"))] | length'
drift capture --label demo | jq '[.attributes[] | select(.value == "<redacted>")] | length'
```

```
22
13
```

`--keep-identifiers` turns the first rule off and prints a warning on standard error:

```
warning: the capsule keeps your account, host name and home path
```

Use it only for a capsule that stays on your machine, for example to see which attribute holds a home path. Do not use it for a capsule that you commit, attach to an issue, put in a case, or hand to `reproduce --env-all`. The redactor knows paths and secret-looking names. It has no rule for email addresses or account ids, so those pass through in any mode.

Exit codes: 0 on success; 1 for a usage error such as an unknown flag (`Error: no such option --bogus`).

### Project Facts

`--project` reads the project files in the working directory and adds `project.<ecosystem>.<name>` attributes whose values are resolved version strings or constraints. It never runs a tool and never uses the network; a file that is missing or does not parse adds nothing. The source scan lists files with `find` (see [Source Facts](#source-facts)). `drift run --project` does the same, and `contract verify --capture` turns it on when an assertion path starts with `project.`. The Wasm build has no file access and adds none.

| Ecosystem | Files read | Keys |
| --- | --- | --- |
| Composer | `composer.json`, `composer.lock` | `project.composer.require.php`, `.require.ext-*` and `.require.lib-*` (the `require` constraint as written, such as `^8.1`), `project.composer.platform.php` and the other `.platform.*` keys (a concrete version from `config.platform`, absent when that is unset or a constraint), `project.composer.<vendor>/<package>` (locked version), `project.composer.json.config.policy` (`true` when `config.policy` exists), `project.composer.json.plugins.unlisted` (comma-separated `composer-plugin` packages in the lock that no `config.allow-plugins` entry matches; absent when none, when `allow-plugins` is `true`, or without both files) |
| npm | `package.json` and the first of `package-lock.json`, `npm-shrinkwrap.json`, `pnpm-lock.yaml`, `yarn.lock`, `bun.lock` | `project.npm.engines.node`, `project.npm.engines.npm`, `project.npm.<name>` (scoped names keep `@scope/name`), `project.npm.lockfile-version` (`lockfileVersion` of `package-lock.json` or `npm-shrinkwrap.json`), `project.npm.ts-entry` (`true` when `main` or a `bin` entry is a `.ts`, `.mts` or `.cts` file, or a script runs `node` on one) |
| Gradle | `gradle/libs.versions.toml`, `settings.gradle(.kts)`, `build.gradle(.kts)` of the root and of each included module (50 at most), `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/gradle-daemon-jvm.properties` | `project.gradle.<group>:<artifact>`, `project.gradle.plugin.<id>`, `project.gradle.wrapper`, `project.gradle.jdk.version`, `project.gradle.properties.<key>`, `project.gradle.kotlin.version`, `project.gradle.agp.version`, `project.gradle.kotlin.target.<name>`, `project.gradle.android.compile-sdk`, `.min-sdk` and `.target-sdk` (a number, `release(36)` forms and a `libs.versions` reference are read; `flutter.compileSdkVersion` and other expressions are not), `project.gradle.kotlin.jvm-target`, `.toolchain`, `.language-version`, `.annotation-default-target`, `project.gradle.kmp-android-module`, `project.gradle.dsl` (`kotlin` or `groovy`, from the first build file), `project.gradle.calls.<name>` (see below) |
| Maven | `pom.xml` | `project.maven.<group>:<artifact>` for the parent and the direct dependencies, `project.maven.property.<name>` for `java.version` and the `maven.compiler.*` properties |
| Python | `pyproject.toml`, then `uv.lock` or `poetry.lock`, then `requirements.txt` | `project.python.<name>` (lowercase, runs of `-`, `_` and `.` as one `-`), `project.python.requires-python` |
| Version files | the first line of `.nvmrc` or `.node-version`, and of `.python-version` | `project.pins.node`, `project.pins.python` (a leading `v` is dropped) |
| Checksum file | `SHA256SUMS`, lines of `<64 hex digits>  <path>` | `project.checksums.<path>` (the digest) |
| Go | `go.mod` | `project.go.version` (the `go` line), `project.go.toolchain`, `project.go.<module path>` |
| R | `renv.lock` | `project.r.r-version` (`R.Version`), `project.r.<package>` (every entry under `Packages`; renv records only packages the project code uses) |
| Dart | `pubspec.lock` | `project.dart.<package>` (not `sdk` packages), `project.dart.sdks.dart` and `.sdks.flutter` (the constraint as written), `project.dart.sdk-min` (the lower bound of `sdks.dart`); direct dependencies come first |
| Julia | `Manifest.toml`, `Project.toml` | `project.julia.julia-version`, `project.julia.manifest-format`, `project.julia.<package>` (entries with a `version`, so not standard libraries); `[deps]` of `Project.toml` come first |
| Elixir | `mix.lock` | `project.elixir.<package>` (`:hex` entries only; `:git` entries have no version) |

Each ecosystem adds at most 200 package keys: packages a rule reads first, then direct dependencies, then the rest by name; `project.<ecosystem>.omitted` holds the number left out. Only versions the files state literally are recorded. A dynamic range, a version from a BOM or a Maven parent, and a `requirements.txt` line that is not pinned with `==` add nothing. A lockfile that lists a package twice gives the version that the root project resolves for a direct dependency and the first listed version otherwise.

Some Gradle values are derived rather than copied:

- `project.gradle.plugin.<id>` holds the plugin version, or `true` when no file states one. It names plugins that a build file declares, applied or not. `kotlin("jvm")`, `alias(libs.plugins.x)` and `apply plugin: 'kotlin-android'` resolve to the full plugin id.
- `project.gradle.kotlin.version` is the version of the first `org.jetbrains.kotlin.*` plugin that has one, then of `kotlin-gradle-plugin`. `project.gradle.agp.version` is the same for `com.android.*` plugins and `com.android.tools.build:gradle`.
- `project.gradle.kotlin.target.<name>` is `true` for each Kotlin Multiplatform target a build file calls (`jvm`, `js`, `wasmJs`, `androidTarget`, `iosArm64` and the other native targets). A rule tests it with `present: 'true'`; an absent key means the target is not declared.
- `project.gradle.kmp-android-module` lists the modules that apply the Kotlin Multiplatform plugin together with `com.android.application` or `com.android.library`, and is absent when no module does.
- `project.gradle.properties.<key>` copies the `gradle.properties` keys that start with `android.`, `kotlin.` or `org.gradle.`; other keys, such as signing passwords, are not read.
- `project.gradle.jdk.version` comes from `toolchainVersion` in `gradle/gradle-daemon-jvm.properties`. Without that file the JDK that runs Gradle is unknown and the key is absent.

`project.gradle.calls.<name>` is `true` when a build script matches a fixed text pattern, and a rule tests it with `present: 'true'`: `compile-configuration` (`compile` or `runtime` dependencies), `archive-basename` (`baseName` set on an archive task), `convention-api` (`convention.` access), `project-exec` (`exec {` or `javaexec {` on the project) and `kotlin-dsl-quoted-notation` (`"name" {` or `"name"()` in a `.gradle.kts` file). The match is on text with comments removed, so a pattern inside a string still counts.

Names reserved for rules, with no scanner behind them yet: `project.build.c.std-flag`, `project.build.link.libs` and `project.build.target.triple`, and the source-level `project.java.*` keys the JDK rules name that [Source Facts](#source-facts) does not emit (`script-engines`, `keystore-type`, `javah-invocation`, `value-based-sync`, `dynamic-agent-load`). A rule that reads a reserved key stays `pending-scanner`.

### Source Facts

With `--project` the scan also reads source files and records what they import and a few call-site patterns. It runs `find` through the host (so it needs a Unix-like host that can run it; Windows and the Wasm build add nothing), skips `node_modules`, `vendor`, `build`, `.gradle`, `venv`, `.venv`, `target`, `dist`, `.git`, `.tox`, `__pycache__` and `site-packages`, and looks 12 directory levels deep. Matching is by regular expression on the text, with no parser, so an import inside a comment or a string counts.

| Ecosystem | Files | Imports | Calls |
| --- | --- | --- | --- |
| `python` | `.py` | `import a.b`, `from a.b import c` | `asyncio.get_event_loop`, `ast-removed-nodes`, `urllib-urlopener`, `ssl.wrap_socket`, `ssl.match_hostname`, `unittest-deprecated-aliases`, `annotation-readers`, `multiprocessing-start-method`, `open-without-encoding`, `copy.replace`, `itertools.batched`, `datetime.UTC`, `str.removeprefix`, `math.lcm`, `itertools.pairwise` (also `from itertools import pairwise`), `contextlib.chdir`, `hashlib.file_digest`, `asyncio.TaskGroup`, `typing.override` (also `from typing import override`), `warnings.deprecated` (also `from warnings import deprecated`) |
| `java` | `.java`, `.kt` | `import a.b.C`, `import a.b.*`, `import static`, Kotlin `as` | `System.setSecurityManager`, `virtual-threads`, `Math.clamp`, `sequenced-first-last` (`getFirst()` or `getLast()` with no arguments), `Files.readString`, `Files.writeString`, `Files.mismatch`, `Path.of`, `Predicate.not`, `Stream.mapMulti` |
| `php` | `.php` | `use A\B\C`, `use function`, grouped `use A\{B, C}`; `\` is written as `.` | `each`, `create_function`, `mcrypt`, `json_validate`, `array_is_list`, `str_contains`, `str_starts_with`, `str_ends_with`, `fdiv`, `get_debug_type`, `mb_trim`, `array_find`, `array_find_key`, `array_any`, `array_all` |
| `js` | `.js`, `.mjs`, `.cjs`, `.jsx`, `.ts`, `.mts`, `.cts`, `.tsx` | `import ... from`, `import 'x'`, `export ... from`, `require('x')`, `import('x')`; relative paths and URLs are skipped | `exitcode-string` (`process.exitCode` set to a string), `import-assert` (`assert { type: ... }` on an import), `Array.fromAsync`, `Object.groupBy`, `Promise.withResolvers`, `fs.globSync` |

Each import adds `project.<ecosystem>.imports.<name>: 'true'` for the name and for every dotted prefix of it, so `import sun.misc.Unsafe` gives `sun.misc.Unsafe`, `sun.misc` and `sun`. A JavaScript import adds the package (`@scope/name` or `name`, including `node:fs`) and, when the specifier has a subpath, the full specifier. A call pattern adds `project.<ecosystem>.calls.<name>: 'true'`; an absent key means the pattern was not found. `open-without-encoding` is a text-mode `open(` call whose arguments name neither `encoding=` nor a binary mode.

The caps are fixed: 500 files per ecosystem (the first 500 by sorted path; `project.<ecosystem>.source-omitted` holds the number left out), 200000 characters per file (larger files are skipped), 16000000 characters in all, and 500 import keys per ecosystem (names a rule reads first, then the rest by name; `project.<ecosystem>.imports-omitted` holds the number left out). Keys are sorted, so the same tree gives the same attributes on every target.

`network.tls-version-offered` describes the other end of a connection, not this machine: a space-separated list of the protocol names (`TLSv1.0 TLSv1.1 TLSv1.2 TLSv1.3`) that the peer of the failing connection offers or accepts. For a client-side rule (the JDK, Go client, Python and Node TLS defaults) the peer is the server; for a server-side rule (the Go 1.22 server default) it is the client. A rule tests membership with `has`. No scanner can read a peer from a capture, so the rules that use it stay `pending-scanner` until a run records a handshake.

### Tool and System Keys

`--tools` runs one version command per tool through the host. A tool that is not installed adds nothing, so a rule that tests for an absent key cannot tell "not installed" from "not captured". Output that no parser recognizes gives `tool.<name>.version` as `unparsed` and `tool.<name>.raw`.

| Tool | Command | Keys |
| --- | --- | --- |
| OpenSSL | `openssl version` | `tool.openssl.family` (`openssl` or `libressl`), `tool.openssl.version` (OpenSSL only, with its letter: `1.1.1w`, `1.0.2k-fips`), `tool.openssl.libressl-version` (LibreSSL only; it has no version key because its numbers do not follow OpenSSL's) |
| OpenSSL providers | `openssl list -providers` | `runtime.openssl.legacy-provider` (`true` or `false`); absent where the command does not exist (1.1.1, LibreSSL) |
| OpenSSH | `ssh -V` | `tool.ssh.version` (`9.6`), `tool.ssh.release` (`p1`) |
| sudo | `sudo --version` | `tool.sudo.flavor` (`ws` for sudo.ws, `rs` for sudo-rs), `tool.sudo.version`, `tool.sudo.release` (`p5`, sudo.ws only) |
| Composer | `composer --version` | `tool.composer.version` |
| Ruby | `ruby --version` | `tool.ruby.version`, `tool.ruby.release` (`p225`, when printed) |
| BIND | `named -v` | `tool.named.version` (the whole token, such as `9.18.39-0ubuntu0.22.04.6-Ubuntu`) |
| iptables | `iptables --version` | `tool.iptables.version`, `tool.iptables.backend` (`nft` or `legacy`, only when the version line names it) |
| curl | `curl --version` | `tool.curl.version` |
| R | `R --version` | `tool.r.version` |
| Dart | `dart --version` | `tool.dart.version`, `tool.dart.channel` (`stable`, `beta`, `dev`) |
| Julia | `julia --version` | `tool.julia.version` |
| Xcode | `xcodebuild -version` | `tool.xcode.version` (macOS with Xcode installed) |
| CocoaPods | `pod --version` | `tool.cocoapods.version` |
| Elixir | `elixir --version` | `tool.elixir.version`, `tool.elixir.compiled-otp` (the OTP release Elixir was compiled with, when printed) |
| Erlang/OTP | `erl -noshell -eval 'io:format("~s",[erlang:system_info(otp_release)]),halt().'` | `tool.erlang.otp` (the major release, such as `29`; the one the runtime reports, which can differ from `compiled-otp`) |

`tool.openssl.version` orders by the OpenSSL scheme: `1.1.1 < 1.1.1a < 1.1.1w < 3.0.0`, so a `ver` range on it can mix letter releases and numbered ones.

`--tools` also versions the tools that the working directory names. It takes command names from the package scripts, Makefile recipes, Dockerfile `RUN` lines, workflow `run:` steps and shebangs, and keeps only the names in a built-in list: `make`, `cmake`, `docker`, `podman`, `skopeo`, `pwsh`, `native-image`, `gradle`, `mvn`, `ant`, `bazel`, `cargo`, `dotnet`, `bundle`, `javac`, `kotlinc`, `swift`, `deno`, `bun`, `pnpm`, `yarn`, `pip`, `poetry`, `uv` and `clang`. A name outside that list is never run. A listed tool runs only when `PATH` resolves it to a file outside the working directory, and then it runs by that absolute path, with no shell, a 5 second timeout and a 64 KiB output cap. Output over the cap, or a timeout, records nothing. The lookup uses two fixed calls, `which NAME` and `pwd -P`. These tools are versioned with the same parsers as the table above. Over ssh no tool runs.

A normal capture also reads these facts, without `--tools`:

- `cgroup.version` is `v2` when every line of `/proc/self/cgroup` starts with `0::` and `v1` when any line names a v1 controller (a hybrid host counts as `v1`).
- `tz.system-zone` is the target of the `/etc/localtime` link below `zoneinfo/`, then the content of `/etc/timezone`. `Etc/UTC`, `Etc/UCT`, `Etc/Universal`, `Etc/Zulu`, `UCT`, `Universal` and `Zulu` are written as `UTC`; `Etc/GMT` stays as it is. It is absent on Windows and where neither source exists. `env.TZ` is separate.
- `runtime.jvm.options` joins `JAVA_TOOL_OPTIONS` and `JDK_JAVA_OPTIONS`, in that order, and is absent when both are empty. Flags on a `java` command line are not visible to a capture.
- `java.net.preferIPv6Addresses` is the last `-Djava.net.preferIPv6Addresses=` value in those options, and absent when they do not set it.
- `limits.<name>` is the soft and hard resource limit from `/proc/self/limits`, written `soft:hard` with each side a number or `unlimited`. The names are `cpu` (seconds), `fsize`, `data`, `stack`, `core`, `memlock` and `as` (bytes), `nproc` and `nofile`. The attributes are absent where the file does not exist, such as macOS and Windows, and a line that does not parse is skipped. The JVM and Node raise the soft open files limit to the hard one when they start, so the JVM and Wasm builds leave `limits.nofile` out; the Linux executables and `drift capture --ssh` record it.
- `fs.tmp.type` is the filesystem type (`tmpfs`, `ext4`, `overlay` and so on) of the mount that holds `/tmp`, and `fs.cwd.type` the same for the working directory. Both come from `/proc/self/mountinfo`: the longest mount point that holds the path wins, so a `--tmpfs /tmp` mount shows as `tmpfs` while the container root shows as `overlay`. The working directory is read with `readlink /proc/self/cwd`. Absent where mountinfo does not exist, such as macOS and Windows.
- `locale.installed` is the sorted, comma-separated list of locale names the system has, read from the names in `/usr/lib/locale/locale-archive` and the entries of `/usr/lib/locale`. A name the archive does not list is not added, so `C` and `POSIX` appear only where the system records them. Absent when neither source exists. `reproduce` generates a `LANG` or `LC_*` locale in its arm only when this list names it, so an arm does not hide a missing locale.
- `network.hosts.names` is the sorted list of the custom `/etc/hosts` names, one token per name: `name@address` when the address is a loopback one (`db.internal@127.0.0.1`) and the bare name for any other address, which is not recorded. The stock aliases (`localhost`, `ip6-*`, `broadcasthost`), the `127.0.1.1` machine entry and the machine's own `HOSTNAME` are left out, and the attribute is absent when nothing custom remains. `network.nsswitch.hosts` is the source list of the `hosts:` line in `/etc/nsswitch.conf`, such as `files dns`. `network.interfaces` is the sorted list of names in `/sys/class/net`, with `lo` included when the host has it.
- `cpu.affinity` is the number of CPUs in `Cpus_allowed_list` of `/proc/self/status`, which is the set the process may run on. `cpu.count` is still the number of CPUs online.
- `tool.sh.flavor` is the base name of the target of `readlink -f /bin/sh`, such as `dash`, `bash` or `busybox`. It is absent where `/bin/sh` is not a link (macOS, where it is a file) and on Windows.
- `sysctl.<name>` is the value of one of seven kernel settings read from `/proc/sys`: `vm.max_map_count`, `fs.inotify.max_user_watches`, `fs.inotify.max_user_instances`, `fs.file-max`, `kernel.pid_max`, `net.core.somaxconn` and `kernel.threads-max`. A value that is not a single word is left out. Nothing else under `/proc/sys` is read, apart from the AppArmor setting above.
- `clock.source` is the current clock source, such as `tsc` or `kvm-clock`, from `/sys/devices/system/clocksource/clocksource0/current_clocksource`. Linux only.
- `network.ipv6` is `true` when `/proc/net/if_inet6` lists an address other than `::1`, and `false` when it lists none. It is absent when the file does not exist. `network.ipv6.disabled` is `true` or `false` from `/proc/sys/net/ipv6/conf/all/disable_ipv6`, so `true` means the switch is off.
- `network.dns.nameservers` is the number of `nameserver` lines in `/etc/resolv.conf`. `network.dns.loopback` is `true` when every nameserver is a loopback address, such as a local stub resolver; it is absent when there are none. `network.dns.search` is the number of search domains on the last `search` or `domain` line, and `network.dns.options` is the sorted option names, such as `edns0,ndots`. No address or domain name is recorded, and the file is read only: no lookup or network call is made.
- `reproduce` and `drift solve` mirror these facts in the arm: a `cpu.affinity` below `cpu.count` becomes `--cpuset-cpus`, `network.interfaces` of `lo` alone becomes `--network none`, loopback `network.hosts.names` tokens become `--add-host`, and `network.nsswitch.hosts` rewrites the `hosts:` line of the image's `/etc/nsswitch.conf` (an Alpine image reads none, so the entry is partial). A hosts name with no recorded address is not mirrored.
- `drift capture --probes` also runs `resources.fs-tmp-fsync`. It writes a 4 KiB file in `/tmp`, syncs it with `fsync` 25 times, removes the file and records the median in one of `<1ms`, `1-10ms`, `10-100ms`, `100ms-1s` or `>1s` as `fs.tmp.fsync = <bucket>`. It is unavailable where the host has no write capability (Wasm) or `/tmp` cannot be written, and `--ssh` never runs it because a capture over ssh only reads.

The same capture in `drift-cisim:3` with `--tmpfs /tmp` (the filesystem facts, one line each):

```text
fs.tmp.type tmpfs
fs.cwd.type overlay
fs.tmp.fsync = <1ms
locale.installed C.utf8
network.interfaces eth0,lo
cpu.affinity 24
```

Without `--tmpfs`, `fs.tmp.type` is `overlay`. The fsync bucket on this host is `<1ms` for both mounts.

```sh
docker run --rm --ulimit nofile=1024:2048 --ulimit core=0 \
  --volume "$PWD/drift:/usr/local/bin/drift:ro" debian:trixie-slim \
  drift capture --label limits |
  jq -r '.attributes[] | select(.path | startswith("limits.")) | "\(.path) \(.value)"'
```

```
limits.as unlimited:unlimited
limits.core 0:0
limits.cpu unlimited:unlimited
limits.data unlimited:unlimited
limits.fsize unlimited:unlimited
limits.memlock 8388608:8388608
limits.nofile 1024:2048
limits.nproc unlimited:unlimited
limits.stack 8388608:unlimited
```

`reproduce` turns each of these into `--ulimit name=soft:hard` (`unlimited` becomes `-1`), and a Lab experiment that sets `limits.nofile` changes that flag. Docker has no `as` limit, so `limits.as` is listed as not mirrored. The `set-ulimit` intervention records a single number such as `2048` as `2048:2048`, because that is what a capture inside the container reports.

Keys that a capture of this machine cannot supply: `runtime.database.server.*`, `network.tls-version-offered` and `net.dns.aaaa.present` need a connection or a name lookup against a named target, and `browser.name` needs a browser. Rules that read them stay `pending-scanner`.

### Attribute Prefixes

A rule's `applies` list and the ranking use one of 14 dimensions. The dimension of an attribute path comes from the longest matching prefix in this table; a path that no prefix claims, such as `drift.platform`, has no dimension.

| Dimension | Prefixes |
| --- | --- |
| `os` | `os.name`, `os.version`, `os.release.`, `tool.libc.`, `libc.`, `windows.`, `fs.`, `crypto.`, `file.` |
| `kernel` | `kernel.`, `cgroup.` (except the two below), `sysctl.`, `clock.` |
| `cpu` | `os.arch`, `cpu.` |
| `cpu-limit` | `cgroup.cpu.` |
| `memory` | `cgroup.memory.`, `memory.` |
| `runtime` | `tool.java.`, `tool.go.`, `tool.node.`, `tool.python.`, `tool.php.`, `tool.rust.`, `tool.kotlin.`, `tool.dotnet.`, `tool.workerd.`, `runtime.` |
| `compiler` | `tool.cc.`, `tool.visualstudio.` |
| `build` | `tool.npm.`, `tool.gradle.`, `tool.pip.`, `ci.`, `deps.`, `project.` |
| `userland` | `tool.coreutils.`, `tool.git.`, `tool.make.`, every other `tool.` path, `pkg.`, `git.config.` |
| `env` | `env.` |
| `locale` | `env.LANG`, `env.LC_`, `env.TZ`, `tz.` |
| `limits` | `limits.` |
| `network` | `env.HTTP_PROXY`, `env.HTTPS_PROXY`, `env.NO_PROXY` and their lowercase forms, `env.REQUESTS_CA_BUNDLE`, `env.CURL_CA_BUNDLE`, `env.SSL_CERT_FILE`, `env.SSL_CERT_DIR`, `env.NODE_EXTRA_CA_CERTS`, `network.`, `net.`, `tls.`, `java.net.` |
| `browser` | `browser.` |

Every environment variable is captured as `env.<NAME>`, so no allowlist gates the proxy and certificate variables. Credentials in a proxy URL (`http://user:pass@host`) are masked before the capsule is written. A rule path may contain `/` and `@`, which package names need, in addition to letters, digits and `_ . : * -`.

## Diagnose

`drift diagnose GREEN RED` compares the capsule of a passing run with the capsule of a failing run, ranks the attributes that differ and proposes experiments. The default output is canonical JSON.

A `.driftcase` archive can stand for either capsule. Add `#passing` or `#failing` to the archive path to select the capsule in `environments/passing.json` or `environments/failing.json`:

```sh
drift diagnose case.driftcase#passing case.driftcase#failing
```

An archive without a selector is refused, because both capsules are in it and the command cannot tell which one you mean. The same selectors work on every command that reads a capsule.

```sh
G=fixtures/drangler/capsules/36702683742.json
R=fixtures/drangler/capsules/36995781138.json
drift diagnose $G $R > diag.json
jq -r '.relevant[0].path, .weights.calibrated, (.bundles[0].members | length)' diag.json
shasum -a 256 diag.json
```

```
tool.node.version
false
9
83c35a87e4f48696684ac6817e31b04e5b9b1bf3c37452d6cbc8c12a030c187c  diag.json
```

The top-level keys are `bundles`, `changed`, `competing`, `facts`, `relevant`, `unattached` and `weights`. `weights.calibrated: false` means the ranking uses hand-set weights, so the order of candidates is not a probability of being the cause.

`--detail` prints text instead, at three levels. It cannot be combined with `--json`.

```sh
drift diagnose --detail summary $G $R
```

```
9 changes differ with the same support, and tool.node.version ranks first only by hand-set weights,
so the data does not name one cause.
```

`--detail detail` adds the ranked candidates, the bundle and the next experiment (67 lines for this pair). `--detail full` adds every changed attribute and, for a ranking whose candidates all rest on attribute changes, each candidate's probability: its share against the other candidates, read through an isotonic map fit on the dev scenarios and not held out (350 lines for this pair, where all nine candidates read 0.104478). The text is plain ASCII, wrapped at 100 columns.

```sh
drift diagnose --detail detail $G $R | sed -n 4,10p
```

```
Candidates, best first. Scores come from hand-set weights (hand-set-1, uncalibrated); they order
  candidates and are not probabilities.
  1. tool.node.version (tier difference, basis inferred, in bundle-1)
      evidence: attribute tool.node.version; no run history behind it
  2. ci.provisioner.build-date (tier difference, basis inferred, in bundle-1)
      evidence: attribute ci.provisioner.build-date; no run history behind it
  3. ci.provisioner.version (tier difference, basis inferred, in bundle-1)
```

A bundle lists candidates the data cannot separate: "none is the cause on its own".

**Cases.** `--case DIR` writes a case directory (11 files: `case.json`, `manifest.json`, the two capsules, the observation chain, the ranking, the prior and the plan) and prints the usual output. The `wrote 11 files to DIR` line goes to standard error.

```sh
drift diagnose --case case --failures 3 --runs 5 --trial-minutes 4 --name drangler-nightly \
  --detail summary $G $R
ls case
jq -r .name case/case.json
```

```
wrote 11 files to case
9 changes differ with the same support, and tool.node.version ranks first only by hand-set weights,
so the data does not name one cause.
case.json
eliminations
environments
experiments
hypotheses
manifest.json
observations
results
drangler-nightly
```

| Flag | Effect |
| --- | --- |
| `--case DIR` | write a case directory |
| `--name TEXT` | case name (default `GREEN-LABEL to RED-LABEL`) |
| `--frame sha\|environment` | `sha` for two commits of one pipeline, `environment` for a local capture against a CI capture; recorded in `case.json` as `frame` |
| `--trial-minutes N` | minutes one trial takes; feeds the planner's cost (at least 1) |
| `--failures N`, `--runs N` | failing runs out of runs seen on the red side; `--failures` must be within `0..--runs` |

The planner's trial counts and costs depend on these three numbers. With `--failures 3 --runs 5 --trial-minutes 4` the first proposal is 18 trials at 9 minutes; with the defaults it is 14 trials at 10 minutes.

Exit codes: 0 whenever the command ran, including when it finds no cause. 1 for a missing or malformed capsule (`invalid capsule file broken.json: expected key at 2`), an unsupported schema (`unsupported capsule schema 2`), `--json` with `--detail`, a bad `--failures` or `--trial-minutes`, and a `--case` directory that already holds a case.

### Against a Baseline

`drift diagnose --against BASELINE [CURRENT]` compares a saved passing run with a failing one. `BASELINE` and `CURRENT` are capsule directories (see [Run](#run) and [Import](#import)) or capsule files. With no `CURRENT`, the command captures this machine, as `drift capture` does. Every `diagnose` flag works the same way.

```sh
drift run --out green-run --label green -- sh -c 'echo building; echo tests passed'
TZ=Asia/Tokyo drift run --out red-run --label red -- sh -c 'if [ "$TZ" != UTC ]; then echo "Error: unexpected timezone $TZ"; exit 3; fi; echo ok'
drift diagnose --against green-run red-run --detail summary
```

```
building
tests passed
drift: exit 0 after 8 ms; capsule in green-run
Error: unexpected timezone Asia/Tokyo
drift: exit 3 after 9 ms; capsule in red-run
env.TZ differs most (tier difference); this ranks differences and does not confirm a cause.
```

The two `drift:` lines go to standard error. The comparison reads the capsules only; the symptoms and steps stored beside them are not part of the ranking. Compare like with like: a capsule from `run` or `capture` holds attributes such as `os.name` and `kernel.release`, and a capsule from `import` holds the attributes a CI log prints (`ci.runner.image.version`), so a baseline from one and a current run from the other differ in most paths.

`--against` with two capsules fails with `--against takes at most one capsule`. Without `--against`, `diagnose` still takes exactly two capsules, and each may now be a directory.

### Over a Set of Capsules

`drift diagnose --set CAPSULE...` takes any number of capsules, some from passing runs and some from failing runs, and ranks the attribute values that split the two groups. It scores each value with the Ochiai spectrum that ranks a CI history, with the failing capsules as the failing runs. Only static attributes count, and a value that every capsule holds is left out because it splits nothing.

A capsule is labelled in one of two ways. End its path with `#pass` or `#fail`, or pass a capsule directory whose `run.json` records a pass or a fail (or whose `result.json` records the exit code of `drift run`). A `#pass` or `#fail` selector wins over a recorded outcome. `case.driftcase#passing` and `case.driftcase#failing` select an archive member and label it. Quote the selectors, since `#` starts a comment in most shells.

```sh
drift diagnose --set 'fixtures/set/toolchain/pass-1.json#pass' 'fixtures/set/toolchain/pass-2.json#pass' \
  'fixtures/set/toolchain/fail-1.json#fail' 'fixtures/set/toolchain/fail-2.json#fail' --detail detail
```

```
set: 2 failing, 2 passing, 1 separating
1. tool.node.version = 22.4.0  failing 2/2  passing 0/2  score 1.000000  separates
2. cpu.count = 16  failing 1/2  passing 0/2  score 0.707106
3. tool.npm.version = 10.8.1  failing 1/2  passing 0/2  score 0.707106
4. cpu.count = 8  failing 1/2  passing 1/2  score 0.500000
5. env.TZ = Asia/Tokyo  failing 1/2  passing 1/2  score 0.500000
6. env.TZ = UTC  failing 1/2  passing 1/2  score 0.500000
7. tool.npm.version = 10.2.4  failing 1/2  passing 2/2  score 0.408248
```

A value separates when every failing capsule holds it and no passing capsule does; its score is 1.000000. The other rows are partial splits, with the number of capsules on each side that hold the value. The default output is canonical JSON with the capsule names and outcomes, the two counts and one object per split. `--detail summary` prints the first five rows, and `detail` and `full` print every row.

Only the failing side is scored. When the failing capsules disagree on a key, each of their values ranks as a partial split, even if no passing capsule shares any of them. With two repeats of one benchmark scenario, `network-proxy-decoy`, the proxy variable that the failing runs add ranks above the `NO_PROXY` decoy whose value differs in each failing run:

```
set: 2 failing, 2 passing, 1 separating
1. env.HTTP_PROXY = http://127.0.0.1:9  failing 2/2  passing 0/2  score 1.000000  separates
2. env.NO_PROXY = corp.example,10.0.0.0/8  failing 1/2  passing 0/2  score 0.707106
3. env.NO_PROXY = internal.example  failing 1/2  passing 0/2  score 0.707106
```

The set needs at least two capsules, one passing and one failing. `--set` cannot be combined with `--against` or `--case`, and a capsule with no selector and no recorded outcome fails with `has no recorded outcome; end it with #pass or #fail`. The command opens no network connection. Studio has a Set tab that does the same with capsules chosen from files or from two sample sets.

### Browser Layout Capsules

`tools/browsers/layout.mjs` loads a page in Playwright's Chromium, Firefox and WebKit at several viewports and writes one capsule per engine and viewport, which `diagnose` compares like any other pair. It is a Node script and needs `npm ci` in `tools/browsers` and the Playwright browsers.

```sh
cd tools/browsers
node layout.mjs --dir ../../fixtures/sites/viewport --out ../../build/layout
```

`--dir` serves a directory and `--url` captures a page that is already served. `--engine` (repeatable) picks engines, `--viewport WxH[@DPR]` (repeatable) replaces the default 390x844, 768x1024 and 1280x800, and `--select ID=SELECTOR` (repeatable) replaces the default of every element with a `data-drift` attribute. The files are named `<engine>-<width>x<height>.json`.

Each capsule holds static attributes in the browser dimension: `browser.engine.name` and `browser.engine.version`, `browser.playwright.version`, `browser.viewport.width` and `.height`, `browser.dpr`, `browser.css.supports.<feature>` (the result of `CSS.supports` for five declarations, `true` or `false`), `browser.media.<name>` (`matchMedia` for seven queries) and `browser.layout.<id>.x`, `.y`, `.width` and `.height` for each selected element, from `getBoundingClientRect` in CSS pixels rounded to two decimals. An id with no matching element gets `missing`.

Two rules explain a pair of these capsules: `browser-css-interpolate-size-support-divergence` matches when `browser.css.supports.interpolate-size` differs, and `browser-viewport-width-changes-layout` matches when `browser.viewport.width` differs. On the fixture page, Chromium's `panel` is 45 px tall and Firefox's is 0 px at 1280x800, and the footer below it sits at `y` 221 and 176:

```sh
drift diagnose fixtures/sites/viewport/capsules/firefox-1280x800.json \
  fixtures/sites/viewport/capsules/chromium-1280x800.json --detail summary
```

```
browser.css.supports.interpolate-size differs most (tier known); this ranks differences and does not
confirm a cause.
```

Before the two rules existed, the same pair printed `6 changes differ with the same support, and browser.css.supports.corner-shape ranks first only by hand-set weights, so the data does not name one cause.` Firefox against WebKit at one viewport has identical boxes, and `diagnose` still says it does not name a cause. Of the 72 ordered pairs of the nine fixture capsules, the candidates in tier `known` were exactly the expected causes in all 72 (see `ViewportDiagnosisTest` and the Browser layout paragraph of the Technical Report).

The scripts capture the engines Playwright ships. Playwright's WebKit is not a Safari release: it supports `corner-shape`, which Browser Compat Data marks `preview` for Safari. Treat `CSS.supports` and the boxes as the measurement and Browser Compat Data as a citation for the property.

## Run

`drift run [--out DIR] [--label TEXT] [--tail-lines N] [--tools] [--project] [--force] -- COMMAND...` runs a command, captures the environment before it starts and writes a capsule directory. The command's output passes through to standard output as it arrives (standard error is merged into it), and the command's exit code is the exit code of `drift run`. One line goes to standard error: `drift: exit 3 after 9 ms; capsule in red-run`.

```sh
TZ=Asia/Tokyo drift run --out red-run --label red -- sh -c 'if [ "$TZ" != UTC ]; then echo "Error: unexpected timezone $TZ"; exit 3; fi; echo ok'
echo "exit $?"
ls red-run
cat red-run/result.json
cat red-run/tail.log
jq -r '.symptoms[] | "\(.kind) \(.value)"' red-run/run.json
```

```
Error: unexpected timezone Asia/Tokyo
drift: exit 3 after 9 ms; capsule in red-run
exit 3
capsule.json
result.json
run.json
tail.log
{"command":"sh -c \"if [ \"$TZ\" != UTC ]; then echo \"Error: unexpected timezone $TZ\"; exit 3; fi; echo ok\"","durationMs":9,"exitCode":3,"schema":1,"truncated":false}
Error: unexpected timezone Asia/Tokyo
signature Error: unexpected timezone Asia/Tokyo
```

A capsule directory holds:

| File | Holds |
| --- | --- |
| `capsule.json` | the capsule, as `drift capture` prints it |
| `run.json` | the run: outcome (`pass` for exit 0, `fail` otherwise), one step with the command line and its duration, and the symptoms read from the tail |
| `result.json` | the command line, exit code, duration in milliseconds and whether the tail was cut; written by `run` only |
| `tail.log` | the last `--tail-lines` lines of output (default 200), at most 64 KiB, with secret-looking values masked as `<redacted>`; written by `run` only |

Symptoms come from the tail, not from the whole output, and only the last 128 KiB of output is kept in memory while the command runs. The command line in `result.json` and `run.json` is masked the same way and shows an argument with spaces in double quotes. `drift run` refuses to write into a directory that already holds a `capsule.json` unless `--force` is given, and it checks that before it starts the command.

| Flag | Effect |
| --- | --- |
| `--out DIR` | capsule directory to write (default `drift-run`) |
| `--label TEXT` | name stored in the capsule and the run (default `run`) |
| `--tail-lines N` | lines kept in `tail.log` (default 200, at least 1) |
| `--tools` | also run the toolchain scanners, as `capture --tools` does |
| `--project` | also read the project facts, as `capture --project` does |
| `--force` | replace the files of an earlier run in `--out` |
| `--repeat N` | run the command N times and record every run (default 1, see Repeated Runs) |
| `--warmup W` | run the command W times first without recording them (default 0) |

Put `--` before the command so that its own flags are not read as `drift` flags; arguments after the first word of the command are passed through either way. A program that does not exist makes the shell report exit code 127, which `drift run` passes through. The command is started directly when the host can (the JVM build) and through `sh` on the native builds. The Windows build was not run for this guide.

### Repeated Runs

`--repeat N` runs the command N times after `--warmup W` unrecorded runs and stores one sample per recorded run in `run.json`: the exit code and the wall time in milliseconds, plus user CPU time, system CPU time and peak memory where the host measures them. A measurement the host cannot make is left out of the sample, never written as zero. Without `--repeat` (or with `--repeat 1`) nothing changes: no samples are stored and the output is the single line shown above.

```sh
drift run --out fast --label fast --repeat 10 --warmup 1 -- sh -c 'sleep 0.1'
GOMAXPROCS=1 drift run --out slow --label slow --repeat 10 --warmup 1 -- sh -c 'sleep 0.3'
drift diagnose fast slow --detail summary
```

```
drift: 10 runs, 10 passed (100%)
drift: wall time median 110 ms, min 100 ms, max 111 ms
drift: cpu time median 0 ms user, 0 ms system
drift: exit 0; capsule in fast
drift: 10 runs, 10 passed (100%)
drift: wall time median 302 ms, min 302 ms, max 302 ms
drift: cpu time median 0 ms user, 0 ms system
drift: exit 0; capsule in slow
env.GOMAXPROCS differs most (tier difference); this ranks differences and does not confirm a cause.

Wall time: the second run is 2.7 times slower than the first (median 302 ms over 10 samples against median 110 ms over 10 samples; the smallest second sample, 302 ms, is above the largest first sample, 111 ms).
```

That run is a demonstration of the output format. The two commands cost a known, different time, and the one environment difference between the two captures is a variable set for the demonstration, so the ranking says nothing about why a real program is slow. The numbers came from the Linux x64 build in a container; the 10 ms steps in the wall times are the host's polling interval.

With several runs the command's output is printed once, from the first failing run or else the last run, and the exit code is that of the first failing run. `tail.log` and the symptoms come from the same run, the step duration and `durationMs` in `result.json` are the median wall time, and the run's outcome is `fail` when any run failed.

| Host | Measured per run |
| --- | --- |
| Linux and macOS native builds | wall time, user and system CPU time, peak memory |
| JVM build, Wasm build | wall time only |

Peak memory comes from `wait4`, and a child that starts through `posix_spawn` begins with its parent's high-water mark. Drift therefore records a peak only when it is above its own peak resident size at the moment it started the command, and leaves it out otherwise. A small command that uses less memory than Drift itself has no peak sample. The macOS build applies the same rule, and it was not run for this guide.

#### Flaky Commands

When the recorded runs mix passes and failures, the summary names what `History` makes of them. `History` calls two or more changes of outcome `FLAKY`; one change (passes, then failures that never recover) is `REGRESSION` or `RECOVERED`, and the summary says it is not flaky by that rule.

```sh
drift run --out flaky --label flaky --repeat 20 -- sh -c 'n=$(cat count 2>/dev/null || echo 0); n=$((n+1)); echo $n > count; [ $((n % 3)) -ne 0 ]'
```

```
drift: 20 runs, 14 passed (70%), 6 failed; history state FLAKY
drift: wall time median 10 ms, min 10 ms, max 10 ms
drift: cpu time median 0 ms user, 0 ms system
drift: exit 1; capsule in flaky
```

`drift diagnose` over a directory with mixed samples says so, and without `--runs` and `--failures` it takes the sample count and the failed samples as the runs seen on the failing side:

```
The second run is intermittent: 6 of 20 samples failed (history state FLAKY). A difference between two captures can explain a failure rate, and it does not say which runs fail.
```

#### Slower and More Memory

`drift diagnose FIRST SECOND` reads the samples of both `run.json` files when both have them. Wall times differ only when the ranges do not overlap: the smallest sample of the larger side is above the largest sample of the smaller side. Then the second run gets a `slower` symptom whose value is the ratio of the two medians in tenths (`2.7 times slower`), and peak memory gets a `memory` symptom (`2.0 times more peak memory`) by the same rule when both sides measured it. The symptoms condition the ranking as the failure: the probe of the CPU limit counts as mechanistic evidence for `slower`, the probe of the memory limit for `memory`, and the attribute weights stay as they are. When the ranges overlap, the output says `no difference larger than the run to run spread` with both ranges and adds no symptom. A first run that is the slower one gets a line and no symptom. A side with fewer than two samples has no spread and is not compared. The JSON output carries a `performance` object, and only when a run has samples; its `ranges` list holds the minimum, median and maximum of each side. The Studio Investigation result draws them as range bars when a diagnosis carries them; the built-in sample investigations carry none.

Each limit below is printed only when it applies:

| Line | When |
| --- | --- |
| different hardware | the captures differ in `hw.cpu.model`, `cpu.count` or `os.arch` |
| fewer than 5 samples | either side has fewer than 5 samples |
| the command starts a JVM | the command starts with `java`, `javac`, `kotlin`, `kotlinc`, `gradle`, `gradlew`, `mvn`, `mvnw` or `sbt` and the shorter median is under 5000 ms |
| Wasm in Node or a browser | a capture is Wasm and the two captures differ in `runtime.platform` or in having `browser.` facts |

None of these lines names a cause. Drift does not count allocations, compare times across different hardware or model JIT behaviour; for a benchmark use `kotlinx-benchmark` or `hyperfine`.

### Compare Two Sets of Runs

`drift compare RUNA RUNB [--json]` reads the `run.json` of two capsule directories written by `drift run --repeat` and compares their stored samples. It runs offline and keeps nothing: the same two directories give the same bytes on every target. Each side needs at least two samples. For wall time, and for CPU time and peak memory where both sides measured them, it prints the sample count, the minimum, median and maximum of each side, the ratio of the medians (B over A), a Mann-Whitney U test, Cliff's delta, a bootstrap interval of the ratio and one verdict sentence. CPU time is left out when both medians are 0 ms.

```sh
drift run --out fast --label fast --repeat 10 --warmup 1 -- sh -c 'sleep 0.1'
drift run --out slow --label slow --repeat 10 --warmup 1 -- sh -c 'sleep 0.3'
drift compare fast slow
```

```
A: fast (fast)
B: slow (slow)
Wall time (ms): A 10 samples, B 10 samples
  A: min 100, median 110, max 110
  B: min 301, median 302, max 302
  ratio of medians, B over A: 2.7
  Mann-Whitney U 0; exact p below 0.0001
  Cliff's delta +1000 permille (large; positive means B is larger)
  bootstrap interval of the ratio, 2000 resamples, 95 percent: 2.7 to 2.7
  B is 2.7 times slower than A; exact p below 0.0001; the interval of the ratio of B to A is 2.7 to 2.7.
```

With the arguments swapped, the verdict reads `A is 2.7 times slower than B` and Cliff's delta is `-1000`. The numbers came from the Linux x64 build in a container. The 10 ms steps in the wall times are the polling interval of the native host.

The same command recorded twice gives the control:

```
A: fast (fast)
B: again (again)
Wall time (ms): A 10 samples, B 10 samples
  A: min 100, median 110, max 110
  B: min 110, median 110, max 111
  ratio of medians, B over A: 1.0
  Mann-Whitney U 22.5; exact p 0.0217
  Cliff's delta +550 permille (large; positive means B is larger)
  bootstrap interval of the ratio, 2000 resamples, 95 percent: 1.0 to 1.0
  No difference larger than the run to run spread (exact p 0.0217; the interval of the ratio of B to A is 1.0 to 1.0).
```

Here the rank test alone would have called a difference: one side has a few samples at 100 ms where the other has 110 ms, a single polling step. A verdict names a direction only when both the exact p-value is at most 0.05 and the interval of the ratio lies on one side of 1, so this pair has no verdict. Three repetitions of this control gave exact p-values of 0.25, 0.09 and 0.0217.

**The statistics.** All of them use whole numbers, so every target prints the same digits, and none reads a clock or a random source.

| Statistic | How it is computed |
| --- | --- |
| Mann-Whitney U | counted from pairs; a tie counts half; the line shows the smaller of the two U values |
| Exact two-sided p | every split of the pooled values into groups of the two sizes is counted in `Long` arithmetic, with ties taking the midrank of their group; a split counts when its rank sum is at least as far from the mean as the observed one |
| Cliff's delta | pairs where B is larger minus pairs where B is smaller, over all pairs, in permille; the labels are negligible below 147, small below 330, medium below 474 and large above, the thresholds published by Romano and others (2006) for the absolute value |
| Bootstrap interval | 2000 resamples of each side with replacement, the ratio of the resampled medians in permille, and the 51st and 1950th ratios in sorted order as a 95 percent interval |

The count of splits is `C(n+m, n)`, which fits in a `Long` only up to 30 samples per side. Above that the line says `exact test not computed above 30 samples per side`, and the verdict rests on the interval alone; there is no normal approximation. The bootstrap uses xorshift64 (shifts 13, 7 and 17) seeded from the first 16 hex digits of the SHA-256 of the sorted samples of both sides, so shuffling the samples within a side changes nothing. A ratio of 1.1 or more is written in times (`2.7 times slower`); a smaller one is written as the excess in percent (`0.5 percent slower`). When the two sides are too small for any split to reach 0.05, the verdict says what the lowest possible p-value is: with 3 samples on each side it is 0.1000.

The verdict is a statement about samples from one host. `drift diagnose` keeps its own rule, which names a `slower` symptom only when the ranges do not overlap, and `compare` does not change it. The two agree when the sides are clearly apart or clearly the same. They disagree with 3 samples a side that are completely separated (the range rule fires, and the exact test cannot go below 0.1) and with 10 samples a side that overlap but shift consistently, as in a synthetic shift of 5 percent (the exact test and the interval fire, the range rule does not). The same limits as in `diagnose` are printed under the metrics: different hardware, fewer than 5 samples, a short JVM run and Wasm in Node against a browser.

**Cgroup counters.** On Linux, `drift run --repeat` reads the counters of its own cgroup v2 before and after each recorded run: `nr_throttled` from `cpu.stat`, and `max`, `oom` and `oom_kill` from `memory.events`. The growth of each counter is stored in the sample as `cgroup` (`throttled`, `memoryMax`, `oom`, `oomKill`). A counter that cannot be read is left out, never written as zero. `compare` then says which side was throttled or short of memory:

```
CPU throttled 102 times in B, none in A.
Throttling by the container is a cause to check before blaming the code.
```

That pair came from a command that runs 4 busy loops against one that runs 24, in a container limited to 8 CPUs (`cpu.max` of `800000 100000`). The wall times differ by 0.5 percent because both commands stop after one second, the CPU time doubles and the second command was throttled in every run. The counters belong to the whole cgroup, so another process in the same container adds to them, and they say nothing outside a cgroup v2 host. With `--json`, the output carries `exactExtreme` and `exactTotal` for the p-value, `deltaPermille`, `intervalLowPermille` and `intervalHighPermille`, and `direction` as `second larger`, `first larger` or `none`.

## Import

`drift import LOG --format github|gitlab --out DIR [--summary FILE] [--job NAME] [--label TEXT] [--force]` turns a saved CI log into a capsule directory. It reads files only and never contacts GitHub or GitLab. The directory holds `capsule.json` and `run.json`, so it works as a baseline or as the current run for `diagnose --against`.

```sh
drift import fixtures/drangler/logs/36702683742.log --format github --job "Docker E2E" \
  --summary fixtures/drangler/runs/36702683742.json --label green --out green-import
drift import fixtures/drangler/logs/37114464625.log --format github --job "Docker E2E" \
  --summary fixtures/drangler/runs/37114464625.json --label red --out red-import
jq -r '.attributes[] | "\(.path) = \(.value)"' red-import/capsule.json
jq -r '.outcome, (.steps | length), ([.symptoms[].kind] | unique | join(","))' red-import/run.json
drift diagnose --against green-import red-import --detail summary
```

```
wrote 11 attributes and 0 symptoms to green-import
wrote 11 attributes and 9 symptoms to red-import
ci.provisioner.build-date = 2026-09-01T19:56:44Z
ci.provisioner.name = Hosted Compute Agent
ci.provisioner.version = 20260901.588
ci.runner.image = ubuntu-24.04
ci.runner.image.version = 20260927.320.1
ci.runner.region = westcentralus
ci.runner.version = 2.337.0
env.REQUIRE_DOCKER = 1
os.release.channel = LTS
os.release.name = Ubuntu
os.version = 24.04.5
fail
49
duration,exit,signature,step,transport
3 changes differ with the same support, and ci.provisioner.build-date ranks first only by hand-set
weights, so the data does not name one cause.
```

The first two lines go to standard error. The logs are the sanitized job logs in [`fixtures/drangler/logs`](./fixtures/drangler/logs); `gh run view --log` printed them and `gh run view --json` printed the summaries.

| Format | `LOG` | `--summary` | Attributes read |
| --- | --- | --- | --- |
| `github` | a job log, raw or as printed by `gh run view --log` | the output of `gh run view --json` | `os.release.name`, `os.version`, `os.release.channel` (the Operating System group), `ci.runner.image`, `ci.runner.image.version` (the Runner Image group), `ci.provisioner.*`, `ci.runner.region` (volatile), `ci.runner.version` and the `env:` block of the first `run:` step (a `***` value is skipped) |
| `gitlab` | a job trace | a pipeline object, an object with a `jobs` array, or a jobs array from the REST API | `ci.runner.version`, `ci.runner.executor`, `ci.runner.image`, `ci.runner.image.digest` |

Without `--summary` the outcome comes from the log: for GitHub `fail` when an error annotation is present and `unknown` otherwise, for GitLab `pass` on a `Job succeeded` line, `fail` on an `ERROR: Job failed` line and `unknown` otherwise. With `--summary` the outcome and the steps come from the summary; GitHub steps carry their job name and GitLab jobs appear as steps with the stage as the job. Symptoms (error signatures, exit codes, signals, transport errors, and for GitHub the failing step and its duration) come from the log text.

`--job` picks the job of a multi-job `gh run view --log` file. Without it, the first job with a Runner Image group is read. Values are masked the same way `capture` masks them.

`import` fails with exit code 1 and writes nothing when the log holds no runner environment, when a file cannot be read, when the summary is not valid JSON, and with `--job` for GitLab. Input cut off at any point never raises an error: the importer reads what is there. A GitLab example from the synthetic fixtures in [`fixtures/ci`](./fixtures/ci):

```sh
drift import fixtures/ci/gitlab-red.trace --format gitlab --summary fixtures/ci/gitlab-jobs.json --out gl
jq -r '.attributes[] | "\(.path) = \(.value)"' gl/capsule.json
jq -r '.outcome, (.steps[] | "\(.job)/\(.name) \(.conclusion) \(.durationMs)")' gl/run.json
```

```
wrote 4 attributes and 4 symptoms to gl
ci.runner.executor = docker
ci.runner.image = ruby:3.3
ci.runner.image.digest = ruby@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
ci.runner.version = 17.5.1
fail
build/build success 31512
test/rspec failure 192
deploy/deploy skipped null
```

The GitLab fixtures are written by hand in the shape of the real format; the field names of the pipeline and job JSON were checked against the GitLab REST documentation, not against a real pipeline.

## CI

`drift ci [--commit SHA | --run ID] [--good SHA|ID] [--job NAME] [--remote NAME] [--provider github|gitlab] [--out DIR] [--detail summary|detail|full] [--json]` answers "what is wrong with my CI" from inside a git checkout. With no flag it does what the [Import](#import) and [diagnose](#diagnose) steps do by hand:

1. Reads the checkout root, the `origin` remote (parsed to a host and an `owner/name` path, with any credentials in the URL dropped), the branch and `HEAD`. `github.com` and the host in `GH_HOST` are GitHub; `gitlab.com` and the host in `GITLAB_HOST` are GitLab. Any other host stops with a message that lists the two providers and the `drift import` route.
2. Takes the latest failed run on the current branch. `--commit` takes the failed run of that commit, `--run` names one. The passing run is the latest successful run of the same workflow that started before the failing one, on the same branch first and on any branch if there is none; `--good` takes a commit or a run id instead (nine or more digits is a run id).
3. Asks `gh` (`gh run list`, `gh run view --json`, `gh run view --log --job`) or `glab` (`glab api` on the pipelines, jobs and trace endpoints) for the two runs, so your own login does the authentication and Drift holds no token. It compares one job: the first failed job of the failing run and the job of the same name in the passing run. `--job` picks another.
4. Imports both logs with the code behind `drift import`, ranks the pair as `diagnose` does, and writes the case to `.drift/ci/<short-sha>` in the checkout root, with the two capsule directories in `capsules/passing` and `capsules/failing` below it. `--out` changes the directory. It never edits a tracked file or `.gitignore`; when git does not ignore the directory it prints the line to add.

Everything it does is available as the existing commands: `drift diagnose .drift/ci/<sha>/capsules/passing .drift/ci/<sha>/capsules/failing`, `drift case show .drift/ci/<sha>` and `drift next .drift/ci/<sha>` read what it wrote.

The output below replays recorded `git` and `gh` answers from public runs of this repository ([`fixtures/ci/live`](./fixtures/ci/live)); a live run prints the same layout for your own runs.

```sh
drift ci
```

```
drift ci: gmitch215/Drift, branch master, HEAD c775d8f

Failing  Build Project, run 40042665061 (c775d8f, 2026-10-23, branch master)
         https://github.com/gmitch215/Drift/actions/runs/40042665061
Passing  Build Project, run 38452229524 (da0505e, 2026-10-13, branch master)
         https://github.com/gmitch215/Drift/actions/runs/38452229524
Job      Test Project (linux-x64), failed in step Run Tests
         Other failed jobs: Test Project (macos-arm64), Test Project (windows-x64), Test Project
         (linux-arm64). Pass --job NAME to compare one of them.

Environment differences, best first (3 of 4):
  1. ci.provisioner.build-date: 2026-10-02T22:28:27Z -> 2026-10-12T17:48:21Z (tier difference)
  2. ci.provisioner.version: 20261002.596 -> 20261012.601 (tier difference)
  3. ci.runner.image.version: 20261004.327.1 -> 20261012.334.1 (tier difference)

Result: 4 changes differ with the same support, and ci.provisioner.build-date ranks first only by
hand-set weights, so the data does not name one cause.
Confidence: low, 4 changes tie and the data cannot separate them.

This compares the runner environment of the two jobs, not your code. It cannot find an ordinary code
regression, so a failure that comes from a code change shows up as no cause or a weak difference.
Read the code change next: git diff --stat da0505e c775d8f

Saved to .drift/ci/c775d8f
Next: drift case show .drift/ci/c775d8f
Then: drift next .drift/ci/c775d8f
...
```

In that run the log shows the `bench` tests failing, which is a code problem. The runner image did change between the two runs, and four environment attributes moved together, so nothing in the data ties one of them to the failure; the report says only that. The limits paragraph appears whenever the best candidate is tied, has no rule or probe behind it, or there is no difference at all. After it, `drift ci` prints the instrumented-rerun route (`drift run --tools --project` on both commits, with `TEST_COMMAND` where your test command goes, which Drift does not guess) and the line for `.gitignore`.

| Flag | Effect |
| --- | --- |
| `--commit SHA` | take the failed run of this commit (4 to 40 hex digits) |
| `--run ID` | take this run as the failing run; it must have failed |
| `--good SHA\|ID` | take the passing run of this commit, or this run id |
| `--job NAME` | compare this job in both runs (default: the first failed job) |
| `--remote NAME` | the git remote that names the repository (default `origin`) |
| `--provider github\|gitlab` | override the provider chosen from the host |
| `--out DIR` | write the case here (default `.drift/ci/<short-sha>` in the checkout root) |
| `--detail LEVEL` | also print the full ranking and plan, as `diagnose --detail` does |
| `--json` | print canonical JSON (the comparison and the ranking) instead of text |

When the failing and passing runs are for the same commit (a rerun that passed), the two capsule labels read `<sha>@<run id>` so they differ. GitLab jobs have no steps, so the Job line shows no step name.

Each failure ends in one sentence and exit code 1, and never prints a response from the service:

| Cause | Message says |
| --- | --- |
| `gh` or `glab` missing | where to install it, then to log in |
| not logged in | the login command for that host |
| no network, a timeout, rate limiting | check the connection, or wait and run again |
| no failed run, or HEAD detached | pass `--commit` or `--run` |
| no passing run | pass `--good`, or compare two `drift run --tools --project` captures by hand |
| log expired or deleted | choose a newer run |

`import` masks attribute values and symptom text with the same redaction `capture` uses before anything is written, and `drift ci` stores only the capsule and run files, never the log. Limits: the provider tools list at most 20 failed runs, 50 GitHub passing runs, 100 GitLab pipelines and 100 GitLab jobs per request; an `env:` block is read only from the first step of a GitHub log; and the GitLab pair in [`fixtures/ci/live/gitlab`](./fixtures/ci/live/gitlab) comes from the public `gitlab-org/cli` project because this repository has no GitLab pipelines.

## Handsoff

`drift handsoff plan [CHECKOUT]` reads a local checkout and prints what a hands-off run of it would do: which tools it uses, which commands CI ran, where they would run, and what the run would leave out. It is step one of a longer path and runs nothing. It reads files, starts no program from the repository and makes no network call. The one process it starts is `ls -1` on `.github/workflows` (a `Host` has no directory listing); `--workflow FILE` skips even that. The only file it writes is the one `--out` names.

`CHECKOUT` defaults to the current directory. The plan below is for this repository, with the job `lint` of `build.yml`. It ran on the Linux JVM build in a copy of the tree, with the checkout path shown as `.`.

```sh
drift handsoff plan . --workflow .github/workflows/build.yml --job lint
```

```
handsoff plan: .
files read: 5; nothing was run and no network call was made

result: ready

ecosystems
  jdk
    files        build.gradle.kts, settings.gradle.kts
    manager      gradle wrapper
    lockfiles    none
    pin          gradle 9.8.0 (gradle wrapper, gradle/wrapper/gradle-wrapper.properties:3)
    note         Gradle tasks and Maven goals are not read
not read: any ecosystem not listed above (for example Cargo, Gemfile, .NET project files)

ci
  files        .github/workflows/build.yml
  job          lint (.github/workflows/build.yml:15)
  runs on      ubuntu-24.04
  image        not set
  services     none
  actions, not run
    .github/workflows/build.yml:24  actions/checkout@v6
    .github/workflows/build.yml:25  actions/setup-java@v5  (distribution: temurin, java-version: 21, cache: gradle)
    .github/workflows/build.yml:31  actions/cache@v5  (path: ~/.konan/caches ~/.konan/dependencies ~/.konan/kotlin-native-prebuilt-*, key: ${{ runner.os }}-${{ runner.arch }}-konan-${{ github.job }}-${{ hashFiles('gradle/libs.versions.toml') }}, restore-keys: ${{ runner.os }}-${{ runner.arch }}-konan- ${{ runner.os }}-konan-)
    .github/workflows/build.yml:42  gradle/actions/wrapper-validation@v5
  steps, in order
    step 5 [Check Style and Coverage Rules]  ./gradlew spotlessCheck checkLineLength sharedRatio :build-core:check  (.github/workflows/build.yml:44)
    step 6 [Compile Every Target]  ./gradlew compileKotlinJvm compileKotlinLinuxX64 compileKotlinLinuxArm64 compileKotlinMingwX64 compileKotlinWasmJs  (.github/workflows/build.yml:46)
    step 7 [Stop Gradle]  ./gradlew --stop  (.github/workflows/build.yml:48)
  note         expressions other than matrix.* are shown as written and are not evaluated

plan
  would run, in order
    1. ./gradlew spotlessCheck checkLineLength sharedRatio :build-core:check  (.github/workflows/build.yml:44)
    2. ./gradlew compileKotlinJvm compileKotlinLinuxX64 compileKotlinLinuxArm64 compileKotlinMingwX64 compileKotlinWasmJs  (.github/workflows/build.yml:46)
    3. ./gradlew --stop  (.github/workflows/build.yml:48)
  where        a rootless container, never the host
  image        not chosen (--from-ci CASE builds one from the failing capsule)
  network      install step only; this plan makes no call and starts nothing
  will not
    pass secrets or the environment of this machine
    mount the home directory
    start the services the job names
    run anything on the host
  by hand
    ./gradlew spotlessCheck checkLineLength sharedRatio :build-core:check
    ./gradlew compileKotlinJvm compileKotlinLinuxX64 compileKotlinLinuxArm64 compileKotlinMingwX64 compileKotlinWasmJs
    ./gradlew --stop
```

The plan states what is known and prints `not set` or `not chosen` for the rest. The root build files of this repository pin no JDK (the workflow does), so without `--job` the same command stops on the runtime version. With a job chosen, its `setup-java` input counts as the pin. `--from-ci DIR` takes a case that [`drift ci`](#ci) wrote and uses the job that failed there; when that case holds a capsule, the image comes from the same code as [`drift reproduce`](#reproduce) and the plan says which attributes picked it.

### Stops

A stop is a place where the checkout is ambiguous. Each one prints what is ambiguous, the one question to answer and the flag that answers it, the plan section is left out, and the exit code is 1. The command never guesses past a stop. The test command rules run only when no other stop is left, because most of them depend on the answers to the others.

| Stop | Fires when | Answer with |
| --- | --- | --- |
| `two-lockfiles` | an ecosystem has more than one lockfile (`package-lock.json` with `yarn.lock`, `poetry.lock` with `uv.lock`) | `--lockfile FILE` |
| `no-lockfile` | `package.json` with dependencies, `composer.json` with packages, a `go.mod` with requirements, a Python manifest with dependencies, and no lockfile | `--accept-no-lockfile` |
| `package-manager` | `packageManager` names one tool and the lockfile belongs to another | `--lockfile FILE` |
| `runtime-version` | no version of the runtime is pinned, a pin is a name that moves (`lts/*`, `latest`, `stable`, `node`), or two pins disagree | `--runtime NAME=VERSION` |
| `monorepo` | `package.json` `workspaces`, `pnpm-workspace.yaml` or `go.work` declares several roots | `--root DIR` |
| `no-test-command` | the repository names no test command Drift can run, or the chosen job has no `run:` step | `--test-command COMMAND` |
| `several-test-commands` | the repository names more than one | `--test-command COMMAND` |
| `matrix-cell` | the chosen job has a matrix and `--cell` does not select exactly one cell, or the matrix is computed | `--cell KEY=VALUE` |
| `ci-config` | the job is not found, matches several jobs, the CI file uses YAML this reader does not follow, or `--from-ci` does not name a case | `--job NAME`, `--workflow FILE` |
| `nothing-found` | no manifest of a supported ecosystem is in the directory | the `CHECKOUT` argument or `--root DIR` |

Two pins disagree when both are exact versions that differ on the components both state (`18` and `20.11.0`), or when an exact version fails a constraint (`18` against `>=20`). Two constraints are not compared, and a form the comparer does not read (a caret on `0.x`, a name) is reported and not compared. `lts/*`, `latest`, `stable`, `node` and `system` move with the day or the machine, so they stop.

```
stop 1 of 1: two-lockfiles
  what: node has 2 lockfiles: package-lock.json, yarn.lock
  question: Which lockfile do installs follow? Pass --lockfile with one of them.
  answer with: --lockfile FILE
```

That excerpt comes from [`fixtures/handsoff/stop-two-lockfiles`](./fixtures/handsoff/stop-two-lockfiles). Each fixture directory holds a small `repo/` and, under `golden/`, the plan printed for it; the stop fixtures also hold the plan with the stop answered.

### Reading the CI file

Drift reads `.github/workflows/*.yml` and `.gitlab-ci.yml`. Without `--job` it lists the jobs it found and reads none. With `--job` (an id or a name) it prints the job's `run:` steps in order with the line of each, its `setup-*` actions and their `with:` values, the runner label or image, the `container:` image and the `services`. A GitLab job also gets its `before_script`, `script` and `after_script` as steps, `extends` resolved inside the file, `default:` values, and the `image`, `services` and `tags` it inherits.

A matrix is never expanded. The plan reads one cell: the one `--cell` selects, or the one whose job name matches the failing job of a `--from-ci` case. `include` and `exclude` follow the GitHub rules for the chosen cell, and GitLab `parallel: matrix` cells are read the same way. Only `matrix.*` expressions are replaced; any other `${{ }}` text stays as written, and the plan says so. Steps with an `if:` condition are listed with the condition, and the plan does not decide whether they run.

Drift ships no YAML library in common code, so a small reader handles block mappings and sequences, plain and quoted scalars, block scalars and one-line flow collections. Anchors, aliases, merge keys, tags, tab indentation, multi-line flow collections and a second document in one file are not read. A file that uses one is named in a note with the line, and a job that lives in it stops as `ci-config`. GitLab `include:` is not followed, and the plan says so.

### Ecosystems

| Ecosystem | Files read | Pins read | Lockfiles |
| --- | --- | --- | --- |
| node | `package.json`, `pnpm-workspace.yaml` | `.nvmrc`, `.node-version`, `.tool-versions`, `engines.node`, `devEngines.runtime`, `volta.node`, `packageManager` (names the manager) | `package-lock.json`, `npm-shrinkwrap.json`, `yarn.lock`, `pnpm-lock.yaml`, `bun.lock`, `bun.lockb` |
| python | `pyproject.toml`, `requirements.txt`, `Pipfile`, `setup.py`, `setup.cfg`, `tox.ini`, `noxfile.py` | `.python-version`, `.tool-versions`, `requires-python`, `python` under `[tool.poetry.dependencies]` | `poetry.lock`, `uv.lock`, `Pipfile.lock`, and `requirements.txt` when every line is pinned with `==` |
| jdk | `build.gradle.kts`, `build.gradle`, `settings.gradle.kts`, `settings.gradle`, `pom.xml` | `.java-version`, `.sdkmanrc`, `.tool-versions`, a Gradle toolchain (`jvmToolchain`, `JavaLanguageVersion.of`), a Maven compiler level (read as `>=N`), the Gradle and Maven wrapper versions | `gradle.lockfile` (no lockfile rule: Gradle and Maven lock only by opt-in) |
| php | `composer.json` | `require.php`, `config.platform.php`, `.tool-versions` | `composer.lock` |
| go | `go.mod`, `go.work` | the `go` line (read as `>=N`), `toolchain`, `.tool-versions` | `go.sum` |
| make, just | `Makefile`, `GNUmakefile`, `makefile`; `justfile`, `Justfile`, `.justfile` | none | none |

Not read: Cargo, Gemfile, .NET project files and solutions, Elixir, Dart, Deno, Swift, Scala, Julia and R. A root file that marks one of them is named in the output as found and not read. Subdirectories are not searched except for workspaces that a manifest declares, and `--root DIR` plans one directory at a time (its lockfile has to be in that directory too). Gradle tasks, Maven goals and Composer scripts are not read.

### Commands from the repository

The plan prefers a command the repository names: a CI `run:` step, a `package.json` script, a Makefile target, a justfile recipe, a nox session. For the rest it uses one default per lockfile, and only where an official page that was fetched documents the command. The pages were read through a summarizing fetch tool, not as full text.

| Source | Command | Page |
| --- | --- | --- |
| `package-lock.json`, `npm-shrinkwrap.json` | `npm ci` | https://docs.npmjs.com/cli/v10/commands/npm-ci |
| `pnpm-lock.yaml` | `pnpm install --frozen-lockfile` | https://pnpm.io/cli/install |
| `yarn.lock` with `__metadata:` | `yarn install --immutable` | https://yarnpkg.com/cli/install |
| `yarn.lock` with `# yarn lockfile v1` | `yarn install --frozen-lockfile` | https://classic.yarnpkg.com/en/docs/cli/install |
| `composer.lock` | `composer install` | https://getcomposer.org/doc/03-cli.md |
| `go.mod` | `go mod download` | https://go.dev/ref/mod |
| `requirements.txt` | `python -m pip install -r requirements.txt` | https://pip.pypa.io/en/stable/user_guide/ |
| `poetry.lock` | `poetry install` | https://python-poetry.org/docs/cli/ |
| `uv.lock` | `uv sync` | https://docs.astral.sh/uv/concepts/projects/sync/ |
| script `NAME` in `package.json` | `npm run NAME`, `pnpm run NAME` or `yarn run NAME`, by the manager | https://docs.npmjs.com/cli/v10/commands/npm-run-script, https://pnpm.io/cli/run, https://yarnpkg.com/cli/run |
| nox session | `nox -s SESSION` | https://nox.thea.codes/en/stable/usage.html |
| justfile recipe | `just RECIPE` | https://just.systems/man/en/ |
| Makefile target | `make TARGET` | https://www.gnu.org/software/make/manual/html_node/Goals.html |

Everything else prints as not detected. A tox environment is listed with the line it came from and no command, because the tox pages returned no text when fetched. `bun.lock` and `bun.lockb` count as lockfiles and have no install command, and Composer scripts and Go tests have no run command. A script, target or recipe named `test` is the test command (a nox session named `test` or `tests` also counts); any other name is listed and not planned.

### Handsoff Flags

| Flag | Effect |
| --- | --- |
| `CHECKOUT` | the directory to read (default `.`) |
| `--root DIR` | plan this directory of the checkout; the CI files are still read from the checkout root |
| `--job NAME` | the CI job, by id or name |
| `--workflow FILE` | the CI file, from the checkout root; starts no process |
| `--from-ci DIR` | a case written by `drift ci`; its failing job is the job (path relative to the working directory) |
| `--cell KEY=VALUE` | choose a matrix cell; repeat for each key |
| `--lockfile FILE` | the lockfile an install follows |
| `--accept-no-lockfile` | go ahead although a manifest has no lockfile |
| `--test-command COMMAND` | the test command, used as written; with a job chosen it is used only when the job has no `run:` step |
| `--runtime NAME=VERSION` | a version of `node`, `python`, `java`, `php` or `go`; it ends the version stop for that tool |
| `--json` | print canonical JSON instead of text |
| `--out FILE` | also write the report to this file |

The text and the JSON hold the same facts in the same order for the same checkout. Exit code 0 means a plan was printed; exit code 1 means a stop or a bad flag value.

Limits: this step plans and never runs, so it cannot say whether a command works. It does not expand a matrix, evaluate expressions other than `matrix.*`, follow GitLab `include:`, search subdirectories, read Rust, Ruby or .NET projects, or emulate services. Containers are the default place in the plan; running in one is a later step.

## Bisect

`drift bisect plan GREEN RED --out PLAN` lists the static attributes that differ between two capsules (files or directories) and writes a plan. `drift bisect apply PLAN --step N --green|--red` records what happened when you ran your test on step N, and prints the next step or the minimal set. Drift never runs the test: a step names attributes to set to their red values in the green environment, and you run whatever applies them (a container flag, a version pin, an environment variable).

```sh
drift bisect plan green-import red-import --out plan.json
drift bisect apply plan.json --step 1 --green
drift bisect apply plan.json --step 2 --red
drift bisect apply plan.json --step 3 --red
```

```
wrote a plan over 3 attributes to plan.json
step 1: in the green environment, set these attributes to their red values and run the test
  ci.provisioner.build-date: 2026-08-28T16:44:25Z -> 2026-09-01T19:56:44Z
then: drift bisect apply plan.json --step 1 --green (test passed) or --red (test failed)
step 2: in the green environment, set these attributes to their red values and run the test
  ci.provisioner.version: 20260828.587 -> 20260901.588
  ci.runner.image.version: 20260920.314.1 -> 20260927.320.1
then: drift bisect apply plan.json --step 2 --green (test passed) or --red (test failed)
step 3: in the green environment, set these attributes to their red values and run the test
  ci.provisioner.version: 20260828.587 -> 20260901.588
then: drift bisect apply plan.json --step 3 --green (test passed) or --red (test failed)
minimal set: 1 of 3 attributes after 3 tests; the test fails with all of them and passes when any one is left out
  ci.provisioner.version: 20260828.587 -> 20260901.588
```

The first line goes to standard error. The search is Zeller's ddmin. The full set of differences counts as failing (it is the red environment) and the empty set as passing (it is the green one); neither is ever a step. Attributes are ordered by the ranking, so the likeliest culprits sit in the first chunk. An attribute that exists on one side only is listed as `(unset)` on the other.

The plan file is canonical JSON and holds the whole state: the flips, the recorded trials, and the derived `status`, `next` and `minimal` fields. The same two capsules always give the same bytes (the plan above hashes to `c788ced1...e1c3` on every run). `apply` accepts only the pending step; repeating the last recorded outcome changes nothing, and a different outcome for a recorded step fails with `step 2 already holds red`. A test that cannot be judged has no third outcome: stop and rerun it rather than guessing.

Only static attributes are flipped. Volatile attributes and probe differences are left out, and a plan over capsules with no static difference fails with `the capsules have no static attribute differences to bisect`.

## Offline

Nothing in `run`, `import`, `diagnose --against` or `bisect` opens a network connection, and the default path of every command stays offline: `--ssh` on `capture` and `diagnose` is the one explicit opt-in that connects to another machine (see [Remote Capture over SSH](#remote-capture-over-ssh)). This run used the Linux arm64 executable in a container with `--network none`, whose network namespace holds the loopback device and the kernel's default tunnel devices, all `down` or `unknown` in `/sys/class/net/*/operstate`:

```sh
docker run --rm --network none -v "$PWD/n:/n" -w /n debian:bookworm-slim sh /n.sh
```

```
wrote 11 attributes and 0 symptoms to green
wrote 11 attributes and 9 symptoms to red
3 changes differ with the same support, and ci.provisioner.build-date ranks first only by hand-set
weights, so the data does not name one cause.
exit 0
building
tests passed
drift: exit 0 after 1 ms; capsule in base
Error: unexpected timezone Asia/Tokyo
drift: exit 3 after 1 ms; capsule in cur
exit 3
env.TZ differs most (tier difference); this ranks differences and does not confirm a cause.
exit 0
```

`n` held the executable, the two sanitized logs and their summaries; `n.sh` ran `import`, `diagnose --against`, two `drift run` calls and a second `diagnose --against` (exit codes echoed by the script).

**In a CI job.** Run the failing step through `drift run`, keep the capsule directory as an artifact, and keep the directory of the last green run as the baseline. A job that has no baseline yet can `import` a saved log of a green run instead:

```sh
drift run --out current --label "$GITHUB_RUN_ID" -- make test || status=$?
drift diagnose --against baseline current --detail summary
exit "${status:-0}"
```

This shape was not run in a CI service for this guide.

## Remote Capture over SSH

`drift capture --ssh HOST` captures another machine, and `drift diagnose --ssh A --ssh B` captures two and diagnoses them. Both are opt-in: every other command, and both of these without `--ssh`, stay offline (see [Offline](#offline)). The remote machine needs an SSH server, a POSIX shell and your key in its `authorized_keys`. Drift installs nothing there and writes nothing there.

```sh
drift capture --ssh build-02 --label build-02 > build-02.json
drift capture --ssh build-02 --out caps/build-02
drift diagnose --ssh ci-green --ssh ci-red --detail summary
```

None of the three was run for this guide, because no ssh connection was made; [Checks for This Section](#checks-for-this-section) says what was. HOST is a name from your own `~/.ssh/config`, or `[user@]name` with letters, digits, `.`, `_` and `-`. The first `--ssh` of `diagnose` is the passing machine and the second is the failing one, as with `drift diagnose A B`.

| Flag | Effect |
| --- | --- |
| `capture --ssh HOST` | capture HOST over ssh with read-only commands |
| `capture --out DIR` | write `capsule.json` into `DIR` instead of printing the capsule; works with or without `--ssh` |
| `diagnose --ssh HOST` | give it twice; capture both machines, write the two capsules and the case to `.drift/ssh/A-B/` in the working directory, and diagnose them with the same code as `drift diagnose A B` |
| `diagnose --case DIR` | with `--ssh`, write the capsules and the case to `DIR` instead (and refuse a `DIR` that already holds a case) |

The case frame is always `environment`, because two machines have no commits. `.drift/ssh/A-B/` takes each host text with every character outside letters, digits, `.`, `_` and `-` turned into `_`, and a second run over the same pair overwrites it. Drift prints the path on standard error, edits no tracked file, and prints a note after the result that says what a read-only capture cannot show:

```
note: this compares what a read-only capture shows on each machine: system files and the environment of a non-login ssh session. No repository code ran, so the ranking names differences and cannot confirm which one causes a failure.
```

### What Drift Runs

Drift starts the system `ssh` through the same process interface that `drift ci` uses for `gh`. It has no SSH library and no new dependency. The first command of a capture, as the tests record it, is this one (the last argument is one word that holds the quoted remote command; `mktemp -d /tmp/drift-ssh.XXXXXX` picks the control directory, and the test's stand-in answered `/tmp/drift-ssh.AbC123`):

```
ssh -T -n -o BatchMode=yes -o StrictHostKeyChecking=yes -o ForwardAgent=no -o ForwardX11=no -o ClearAllForwardings=yes -o ConnectTimeout=10 -o LogLevel=ERROR -o ControlMaster=auto -o ControlPersist=30 -o ControlPath=/tmp/drift-ssh.AbC123/m -- me@build-02 "'uname' '-sm'"
```

The remote side runs only the commands below, each with every argument in single quotes. Any other command is refused inside Drift with an error, and there is no flag to widen the list in this version.

| Remote command | Used for |
| --- | --- |
| `uname -sm` | the remote `os.name` and `os.arch`; also the first check that the connection works |
| `uname -r`, `getconf _NPROCESSORS_ONLN`, `sw_vers -productVersion` | `kernel.release`, `cpu.count`, and `os.version` on macOS |
| `readlink /etc/localtime`, `readlink /proc/self/cwd` | `tz.system-zone` and the mount of the working directory |
| `readlink -f /bin/sh` | `tool.sh.flavor`, the shell behind `/bin/sh` |
| `ls -1 /sys/class/net`, `ls -1 /usr/lib/locale` | `network.interfaces` and `locale.installed` |
| `env` | the `env.*` attributes |
| `cat` of one of: `/etc/hosts`, `/etc/nsswitch.conf`, `/etc/os-release`, `/etc/timezone`, `/etc/resolv.conf`, `/etc/crypto-policies/config`, `/proc/cmdline`, `/proc/net/if_inet6`, `/proc/self/cgroup`, `/proc/self/limits`, `/proc/self/mountinfo`, `/proc/self/status`, `/proc/sys/fs/file-max`, `/proc/sys/fs/inotify/max_user_instances`, `/proc/sys/fs/inotify/max_user_watches`, `/proc/sys/kernel/apparmor_restrict_unprivileged_userns`, `/proc/sys/kernel/pid_max`, `/proc/sys/kernel/threads-max`, `/proc/sys/net/core/somaxconn`, `/proc/sys/net/ipv6/conf/all/disable_ipv6`, `/proc/sys/vm/max_map_count`, `/sys/devices/system/clocksource/clocksource0/current_clocksource`, `/sys/fs/cgroup/cpu.max`, `/sys/fs/cgroup/memory.max`, `/sys/fs/cgroup/memory/memory.limit_in_bytes`, `/usr/lib/locale/locale-archive`, `/usr/share/zoneinfo/tzdata.zi`, `/usr/share/zoneinfo/+VERSION` | the OS release, cgroup, resource limit, mount, CPU affinity, locale, hosts, name service switch, resolver, kernel command line, crypto policy, time zone database, `sysctl.*`, clock source and IPv6 facts |

On a host whose `tzdata.zi` has a version line and whose `/etc/localtime` links into `zoneinfo/`, a Linux capture makes 37 remote commands, 35 of them distinct (`env` and `cat /sys/fs/cgroup/memory.max` each run twice). Two fallbacks each add one call and one distinct command: `cat /usr/share/zoneinfo/+VERSION` when `tzdata.zi` has no version line, and `cat /etc/timezone` when `/etc/localtime` does not link into `zoneinfo/`. The most a Linux capture runs is 39 calls and 37 distinct commands, which is every entry in the list above except `sw_vers`. The facts never run `df`, `mount` or `find`, run `ls` only on the two directories above, and never read a path outside the list.

### Safety Rules

Drift passes `-o BatchMode=yes`, so ssh never asks for a password, and it never calls `sshpass` or `sudo`. It passes `-o StrictHostKeyChecking=yes`, so a host key that is not already in your `known_hosts` fails the connection. Drift never runs `ssh-keyscan`, never edits `known_hosts`, and never uses `accept-new`, `StrictHostKeyChecking=no` or `UserKnownHostsFile=/dev/null`. Your own `~/.ssh/config`, agent and keys do the authentication, and Drift stores no credential and no host key. It passes `ForwardAgent=no`, `ForwardX11=no` and `ClearAllForwardings=yes` so your config cannot turn forwarding on.

The host text must match `[user@]name`, with letters, digits, `.`, `_` and `-` (or a bracketed IPv6 address after the `@`), no part may start with `-`, and Drift passes it after `--`. Anything else is refused before ssh runs, so a host argument cannot become an ssh option. A host argument in brackets reaches ssh without them.

Each remote command has a 30 second limit, ssh itself has `ConnectTimeout=10`, and Drift keeps at most 1 MiB of output from one command. A command that sends more stops the capture with an error. After any ssh failure, Drift runs no further remote command.

Before the first remote command Drift prints one line on standard error:

```
drift: running read-only fact commands on me@build-02 over ssh; it reads system files and the environment, runs no repository code, uses no sudo and stores no credentials
```

Drift reuses one connection for the whole capture. It makes a private directory with `mktemp -d` (mode 0700), puts the control socket in it, closes the connection with `ssh -O exit` at the end and removes the directory. If it cannot make the directory, or the local machine is Windows, it runs without connection reuse.

`--tools`, `--probes` and `--project` run many more commands, so `capture --ssh` refuses them. Both refusals below are real output from the JVM build in a Linux container (`drift capture --ssh build-02 --tools`, then `drift capture --ssh=-oProxyCommand=id`), and each exits 1 before any `ssh` starts:

```
Usage: drift [<options>] <command> [<args>]...

Error: --tools, --probes and --project run many more commands and are not available with --ssh in this version
```

```
Usage: drift [<options>] <command> [<args>]...

Error: -oProxyCommand=id is not a host name: use [user@]name with letters, digits, '.', '_' and '-', and no leading '-'
```

### What the Capsule Holds

The remote capsule comes from the same `Capture` code as a local one, reading the remote machine through the commands above, so it diffs the same way. Three things differ. It has no `kotlin.version`, `kotlin.target` or `drift.platform`, because those describe the Drift build on your machine. It has no `runtime.platform` unless the remote environment sets `DRIFT_RUNTIME_PLATFORM`. It has `capture.transport` with the value `ssh`, so a remote and a local capsule never look the same by accident. The `env.*` attributes come from a non-login ssh session, which loads less of the remote shell profile than a terminal does, and that can differ from what a test sees when you log in. The account name, host name and home path are anonymized as in a local capture; `--keep-identifiers` keeps them.

`os.name` comes from `uname -s` in the same vocabulary as a local capture (`linux`, `macos`, `freebsd`, and `unknown` for other names). A Windows remote, or a host that does not answer `uname`, is refused with a message. No hardware facts are read over ssh.

### When ssh Fails

Every failure exits 1, prints nothing on standard output, and prints a plain message. The left column is what OpenSSH prints, written from memory of its messages and not captured from a run for this guide.

| ssh says | Drift prints |
| --- | --- |
| `No ED25519 host key is known for HOST and you have requested strict checking.` | `HOST is not in your known_hosts, so Drift will not connect. Check its fingerprint with its owner and add the key yourself, for example by connecting once with ssh. Drift never adds host keys` |
| `WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!` | `the host key of HOST does not match your known_hosts, so Drift refuses to connect: the host may have been replaced or intercepted. Ask its owner before you change known_hosts` |
| `Permission denied (publickey).` | `ssh could not log in to HOST without a prompt. Drift never asks for a password; use a key or agent that your own ssh config selects for this host` |
| `Connection refused` | `HOST refused the connection; check the name, the port in your ssh config and that sshd is running` |
| `Could not resolve hostname` | `cannot resolve the name HOST; check it and your ssh config` |
| `Connection timed out`, or no answer in 30 seconds | `ssh to HOST timed out` |
| any other text | `ssh to HOST failed: ` and the first line, cut to 200 characters |

Two more stops have their own messages: `cannot start ssh; install an OpenSSH client and make sure it is on the path`, and, on a build that cannot start programs (the web and iOS builds), `this build cannot start other programs, so it cannot run ssh; use the desktop command line`. The Studio app does not call ssh.

### Risks

| Risk | Control | What is left |
| --- | --- | --- |
| First contact with an unknown host | `StrictHostKeyChecking=yes` against your `known_hosts`; Drift never adds a key | a key you add without checking its fingerprint |
| A changed host key | ssh refuses and Drift reports it without retrying | none beyond the line above |
| A secret in the capsule | `env` values are masked by name and by token shape, as in a local capture | a secret under a variable name that the masks do not match |
| Remote code runs | only the fixed commands above, no repository code, no `sudo` | each new flag that adds commands needs its own review |
| Option injection through the host text | strict pattern, `--`, every remote argument quoted | none known |
| Output flood from the remote | 1 MiB per command and a 30 second limit | a slow link can still take a while |
| Remote profile prints text on login | `uname -sm` uses its last line and `env` keeps only `NAME=value` lines | a banner in the middle of a file read can add a stray line to that file's text |

### Checks for This Section

The tests run the whole capture against a stand-in for `ssh` that records each argument list, so no test opens a connection. They check the exact command line above, that hostile host text and hostile quoted arguments (`;`, `$()`, backticks, quotes, newlines, a leading `-`, a 100,000 character string) never reach a remote shell unquoted, that every command outside the list is refused before ssh runs, the output bound, the timeout, each failure in the table above, the capsule without `kotlin.*` and with `capture.transport`, and the refusal of `--tools`, `--probes` and `--project`. No ssh connection was made for this guide. The one live check is `drift capture --ssh paisley-park --label paisley`, run on the Mac against a machine whose host key is already in `known_hosts`. It is read-only and changes nothing on the remote.

## Next and Case Show

`drift next CASE` prints the proposed experiments of a case. `drift case show CASE` prints what the case holds and whether it still checks out.

```sh
drift case show case
```

```
case: drangler-nightly
frame: sha, passing drangler-36702683742, failing drangler-36995781138
observations: 2, chain head 51c3d18ef57180a4ee97f29a1afd87d1584bc5f95e8239e0664786ab85621c3b
integrity: ok, 10 files match the manifest and the chain holds
rule sha256: 85d28ec6c5ccb9292603be15d730a5685942efb2690a8b0ead6143480948d588 (ingest names it as ruleSha256)

9 changes differ with the same support, and tool.node.version ranks first only by hand-set weights,
so the data does not name one cause.
```

`--detail summary|detail|full` applies to both. `next` at `detail` prints the best automatic experiment with the instructions for a workflow variant, then the manual ones (experiments Drift cannot run, such as a runner image the CI provider owns).

```sh
drift next --detail summary case
```

```
Next experiment (a proposal, nothing has been run):
  e9: drangler-36702683742 with deps.@napi-rs/keyring.version, deps.prettier-plugin-sh.version,
    deps.wrangler.version, tool.node.version, tool.npm.version set to drangler-36995781138 values
    automatic-ci; 9 min, 72 runner min, 18 trials
    why: highest information gain per cost among the experiments that can run

Manual experiments, which Drift cannot run (proposals, not results):
  m1: drangler-36702683742 with ci.provisioner.version set to drangler-36995781138 values
    manual; 192 min, 0 runner min, 18 trials
...
```

The expected gain printed by `detail` is "a prediction, not a measurement".

`case show` and `next` verify the case first. Change one character in an observation and both refuse:

```sh
cp -R case bad
sed -i '' 's/drangler-36995781138/drangler-edited/' bad/observations/0002.json
drift case show bad; echo "exit $?"
drift next bad; echo "exit $?"
```

```
case: drangler-nightly
frame: sha, passing drangler-36702683742, failing drangler-36995781138
observations: 2, chain head 51c3d18ef57180a4ee97f29a1afd87d1584bc5f95e8239e0664786ab85621c3b
integrity: 2 problem(s)
  observations/0002.json was changed: sha256 is 729d96b4..., the manifest has 06ad04b2...
  observation chain broken at entry 1 (observations/0002.json): content does not match its hash

exit 1
case bad failed its checks: observations/0002.json was changed: sha256 is 729d96b4..., the manifest has 06ad04b2...; observation chain broken at entry 1 (observations/0002.json): content does not match its hash
exit 1
```

The hashes in the example are shortened with `...`. A directory that is not a case gives `cannot read case nonexistent: missing case file: manifest.json`, exit 1.

## Reproduce

`drift reproduce CAPSULE --run COMMAND --out DIR` writes `Dockerfile`, `run.sh` and `manifest.json` that mirror a capsule as far as a container can. It never runs Docker and never reads the network. Both `--run` and `--out` are required.

```sh
drift reproduce fixtures/lab/node-alpine.json --run "npm test" --out repro
cat repro/Dockerfile
```

```
wrote 3 files to repro
23 attributes: 10 mirrored, 4 partially mirrored, 9 not mirrored; base image node:22.12.0-alpine3.20
This is not the original environment. Attributes that are not mirrored are listed with their reasons
  at --detail detail.
# drift reproduce: capsule 540e5c225a8ee48280f38e3c96a68e4d43ee1b2879e9d2d7d21f1c8fcb006531; manifest.json says what is not mirrored
FROM node:22.12.0-alpine3.20
RUN apk add --no-cache gcompat libgcc tzdata
RUN npm install -g npm@10.9.2
WORKDIR /work
```

`wrote 3 files` goes to standard error and the summary to standard output. Running the command twice produces identical files:

```sh
shasum -a 256 repro/*
```

```
f47eacf4b6acd22940547256bfcf3a0d34914a52bc0fd6b8254a6192797f7305  repro/Dockerfile
d58f19006418c668478e69e5b452646bd0491691c4a4d02721199cb50f8e3012  repro/manifest.json
2697a96f2b1eb65ec1ad9ebcd6294570fd47d3537312e18ecbdc145333dcef56  repro/run.sh
```

`run.sh` builds the image (tagged `drift-reproduce:<12 hex of the Dockerfile hash>`) and runs it with the flags the capsule implies:

```sh
sed -n 6,20p repro/run.sh
```

```
docker build --platform linux/arm64 --tag "$image" "$here" >&2
exec docker run --rm \
	--platform linux/arm64 \
	--user "$(id -u):$(id -g)" \
	--volume "$PWD:/work" \
	--env 'HOME=/tmp' \
	--memory 512m \
	--cpus 1.2 \
	--ulimit nofile=2048:2048 \
	--env 'LANG=en_GB.UTF-8' \
	--env 'TZ=Asia/Tokyo' \
	"$@" \
	"$image" sh -c 'npm test'
```

Arguments after `run.sh` land before the image name, so they override the flags above them.

**Naming the toolchain.** The base image comes from the toolchain the capsule records. `--run` decides which one when the capsule records several: the first word of the command that names a toolchain wins (a path prefix is ignored, so `./gradlew` counts as `gradlew`; `node`, `npm` and `python3` are other examples). With no match, a toolchain whose official image sets its version variable in the capsule (`PYTHON_VERSION`, `GCC_VERSION`, `R_BASE_VERSION` and the like) wins, because the capsule was taken inside that image; otherwise the first toolchain in table order is used. A distribution that records an alpine point release the registry publishes, such as 3.22.6, gets that exact `alpine` tag.

```sh
for r in "npm test" "sh test.sh" "python3 -m pytest"; do
  drift reproduce fixtures/lab/node.json --run "$r" --out t1 2>/dev/null | sed -n 2p
  jq -r '.image.basis' t1/manifest.json
  rm -r t1
done
```

```
  node:22.12.0-bookworm-slim
tool.node.version 22.12.0; the command names a node tool
  node:22.12.0-bookworm-slim
tool.node.version 22.12.0; the first of 2 toolchains in table order
  python:3.11.2-slim-bookworm
tool.python.version 3.11.2; the command names a python tool
```

`fixtures/lab/node.json` records both a Node and a Python toolchain. Per command:

| `--run` | Base image | `image.basis` |
| --- | --- | --- |
| `npm test` | `node:22.12.0-bookworm-slim` | the command names a node tool |
| `sh test.sh` | `node:22.12.0-bookworm-slim` | the first of 2 toolchains in table order |
| `python3 -m pytest` | `python:3.11.2-slim-bookworm` | the command names a python tool |

If `sh test.sh` really runs Python, name it: `--run "python3 test.py"`.

**Tags that do not exist.** The tag is composed from the capsule, and `core` carries the tags that Docker Hub publishes for the images it composes (`ImageTags.kt`, written by `./gradlew :tools:imageTags` and committed). When the composed tag is not on the list, the base is the same version on the nearest Debian release that has it, or else the nearest published version with the same suffix, and the toolchain version entry in `manifest.json` is `PARTIAL` with the replaced tag in its detail. An npm that its Node cannot run (for example npm 10.9.0 on Node 18.16.0) is not pinned; the image keeps its bundled npm.

**What is mirrored.** `--detail detail` lists every attribute that is not mirrored, by reason.

```sh
drift reproduce fixtures/lab/node-alpine.json --run "npm test" --out repro-detail --detail detail 2>/dev/null | sed -n 9,25p
```

```
Not mirrored (9), by reason:
  a container runs on the Docker host's CPU (model, cache, count, steal time)
    cpu.count
  installed by the test command from the lockfile in the mounted workspace
    deps.vitest.version
  redacted: the capsule holds no usable value
    env.API_TOKEN, env.DB_PASSWORD
  host identity: the container has its own
    env.HOME
  not allowlisted
    env.NODE_ENV
  a container runs on the Docker host's kernel (version, clocksource, scheduler)
    kernel.release
  the base image ships its own git, or none; the version is not pinned
    tool.git.version
  comes with the base image; its version is not known offline
    tool.libc.version
```

`manifest.json` holds the same data as JSON: `attributes` (path, `status` of `mirrored`, `partial` or `not-mirrored`, a `detail`, a `bundle` flag), `env`, `image`, `run`, `summary`, `conventions` and `unusedDigests`. Never mirrored in any capsule: `kernel.*`, `cpu.*`, `hw.*`, `ci.*`, `deps.*` and anything that describes the capturing binary (`drift.*`, `kotlin.*`, `runtime.*`). A fix that depends on one of those cannot show up in a container built from these files.

**Environment allowlist.** Only `LANG`, `LC_*` and `TZ` are copied from the capsule's `env.*` attributes. `--env NAME` (repeatable) adds one variable. `--env-all` copies every `env.*` value.

```sh
drift reproduce fixtures/lab/node-alpine.json --run "npm test" --out r3 --env NODE_ENV 2>/dev/null | sed -n 1p
grep -n NODE_ENV r3/run.sh
```

```
23 attributes: 11 mirrored, 4 partially mirrored, 8 not mirrored; base image node:22.12.0-alpine3.20
16:	--env 'NODE_ENV=production' \
```

A name the capsule does not hold is refused: `--env NOPE does not name an env.* attribute of the capsule`, exit 1. A name whose value is `<redacted>` is accepted and writes nothing (`grep -c API_TOKEN run.sh` gives 0), and the attribute stays "not mirrored".

`--env-all` is unsafe because the capsule's environment can hold identifiers and secrets that the redactor does not recognize, and `run.sh` and `manifest.json` are files you are likely to commit or share. It prints a warning on standard error:

```
warning: --env-all mirrors every env.* value; values may contain identifiers and secrets, and only secret-looking values and home paths are redacted
```

Home paths and the capsule's own account and host names are replaced in anything written; email addresses and account ids are not touched.

**Digests.** A tag can move. `--digests FILE` takes a JSON object that maps an image tag to a digest and pins the matching tag:

```sh
printf '{"node:22.12.0-alpine3.20":"sha256:%064d"}\n' 0 > digests.json
drift reproduce fixtures/lab/node-alpine.json --run "npm test" --out r7 --digests digests.json 2>/dev/null | sed -n 1,2p
sed -n 2p r7/Dockerfile
```

```
23 attributes: 10 mirrored, 4 partially mirrored, 9 not mirrored; base image
  node:22.12.0-alpine3.20@sha256:0000000000000000000000000000000000000000000000000000000000000000
FROM node:22.12.0-alpine3.20@sha256:0000000000000000000000000000000000000000000000000000000000000000
```

The digest in this example is zeros and does not exist; use a digest from your registry. Without `--digests`, `--detail detail` says `Pinned by tag only; a tag can move.` A digest file that does not exist gives `cannot read digests file: nofile.json`, exit 1.

`--out` must be a directory that holds no reproduction yet. If `Dockerfile`, `run.sh` or `manifest.json` is there, the command stops with `output directory already holds a reproduction: repro (Dockerfile, run.sh, manifest.json); pass --force to replace those files, nothing else in the directory is touched`, exit 1. `--force` replaces those three files and nothing else, prints `replacing Dockerfile, run.sh, manifest.json in repro` first, and refuses (`--force replaces only files drift reproduce wrote, and Dockerfile in repro is not one; nothing was replaced`) when one of them was not written by `drift reproduce`.

`--help` marks `--run` and `--out` `(required)` and shows the defaults of the other options. Exit codes: 0; 1 for a missing `--run` or `--out` (`Error: missing option --run`), an unreadable or malformed capsule, an unknown `--env` name, or a missing digests file.

## Solve

`drift solve GREEN RED --run COMMAND --fail-when TEST` runs counterfactual arms in containers on the local machine and writes a case directory with a certificate. It is the only command that needs a working Docker (`docker info` must succeed). Every container is built from `reproduce` files, so the arms are evidence about containers built from the capsules, not about the original host.

| Flag | Effect |
| --- | --- |
| `--run TEXT` | the failing command, run in each container (required) |
| `--fail-when TEXT` | `exit` (a nonzero exit fails) or a pattern that the output of a failing trial matches (required) |
| `--budget trials=N,minutes=M` | most trials over all arms and most minutes of container time (default `trials=300,minutes=45`) |
| `--case DIR` | case directory to write (default `drift-case`) |
| `--workspace DIR` | directory mounted at `/work` in each container (default `.`) |
| `--drift-binary PATH` | absolute path of a Linux `drift` binary; each arm is then captured inside its container |
| `--pilot N` | trials per baseline arm that check the failure shows (default 8) |
| `--trial-minutes N` | planner's minutes per trial (default 1) |
| `--timeout N` | seconds before one trial is cut off (default 120) |
| `--max-memory`, `--max-cpus` | `docker --memory` and `--cpus` for arms whose capsule sets no limit |
| `--gate permille=N,salt=S,env=NAME` | set `NAME` to 1 on `N` of every 1000 trials and to 0 on the rest, in every arm, so a command that fails only when it reads 1 fails intermittently; `salt` defaults to 0 and `env` to `DRIFT_GATE` |
| `--name TEXT` | case name |
| `--exit-status` | exit 0 `CONFIRMED`, 10 `CONFIRMED EFFECT (bundle)`, 11 `NARROWED`, 12 `STUCK`, 13 `NEEDS` (default: 0 for every verdict) |
| `--detail summary\|detail\|full` | how much to print |

**Naming the toolchain in `--run`.** `--run` also picks the base image of every arm (see [Reproduce](#reproduce)), so start the command with the tool that runs the test: `node test.js`, `python3 -m pytest`, `./gradlew test`. The command runs with the workspace at `/work` as the working directory.

**An example that ran.** The scenario is `locale-tz-date-2` from DriftBench: a program that compares the local calendar day with the UTC day. The green capsule has `TZ=UTC` and the red one `TZ=Pacific/Auckland`.

```sh
mkdir ws
cat > ws/prog.sh <<'EOF'
day=$(date -d @1700000000 +%F)
if [ "$day" != 2023-11-14 ]; then
  echo "report: date $day does not match the UTC batch day"
  exit 1
fi
echo ok
EOF
drift solve --run 'sh prog.sh' --fail-when 'does not match the UTC batch day' \
  --budget trials=80,minutes=15 --workspace ws --drift-binary /abs/path/to/drift-linux-x64 \
  --case solved --name tz-demo \
  bench/data/capsules/locale-tz-date-2.green.json bench/data/capsules/locale-tz-date-2.red.json
```

```
wrote 41 files to solved
CONFIRMED: this dimension causes the failure: env.TZ. Setting it to the failing values moved the
  failure count from 0 of 5 to 5 of 5 (exact one-sided p 0.003968) and back from 5 of 5 to 0 of 5
  when the failing side was set to the passing values (p 0.003968)
1 candidate changes, 2 experiments run in containers (36 trials, 36 seconds), not on the original
  host.
```

The capsule says `os.arch=x86_64`, so the arms ran as `linux/amd64` containers on an arm64 Mac (emulated), and `--drift-binary` had to be an x86_64 Linux binary (`cli/build/bin/linuxX64/releaseExecutable/drift.kexe`). The binary must run inside the arm's image. Without `--drift-binary` the same command also ended `CONFIRMED` and wrote 35 files; the 6 missing files are the capsules of the arms.

`wrote 41 files to solved` goes to standard error. The case has the files of a `diagnose` case plus `experiments/`, `arms/<id>-control` and `-treatment` (each with `Dockerfile`, `run.sh`, `manifest.json`, `capsule.json`), `results/NNNN.json` and `certificate.json`. The exit code is 0 for every verdict unless you pass `--exit-status`, which maps the verdict to an exit code (see [Exit Codes](#exit-codes)).

**Budgets.** `--budget` caps trials over all arms and container minutes together. If no trials-per-arm count up to 30 reaches power 0.8 inside the remaining budget, the run ends `NEEDS` with reason `budget`, names the shortfall and gives the command that would settle it. A bad item fails before any container starts: `bad --budget item: bogus`, or `--budget takes trials=N and minutes=M, not hours=2`.

**The verdicts.**

| Verdict | Meaning |
| --- | --- |
| `CONFIRMED` | a minimal set of attributes, all set directly, reproduces the failure forward and removes it in reverse |
| `CONFIRMED EFFECT (bundle)` | the same, but the arms also differ in attributes nobody set; each member is named. Members that an intervention can set alone get their own experiment (see below) |
| `CONFIRMED EFFECT (isolation not measured)` | the effect and its reverse or replicate agree, but an arm was not captured inside its container, so what differed between the arms is predicted and not measured |
| `NARROWED` | an experiment was supported or stayed inconclusive, but the conditions above are not met |
| `NEEDS` | no experiment was supported, and the certificate lists what would settle the cause; the reason is `not-reproduced`, `uncontrollable`, `no-candidates`, `budget` or `executor` |
| `STUCK` | the same, but no step exists; the statement says why (for example a pilot already at its 30 trials per arm that cannot reach the target power) |

**Member experiments.** When the verdict is a bundle, `solve` tries each member that its own intervention can set, one at a time, up to six arms per solve. Setting only that member on the passing side and reproducing the failure makes it `sufficient`. Otherwise reverting only that member on the failing side and removing the failure makes it `necessary`. A member counts only when the capture inside the arm shows that member changed and nothing else. Members that no arm sets alone (for example `tool.libc.version`, which comes with the base image) are listed as not tested alone, each with the reason. The verdict carries the two lists as `sufficient` and `necessary`, the certificate holds the arms under `conditions.removal`, and `verify` checks that each listed member has a supported experiment. Attributes that one intervention sets together, such as `os.release.ID`, `os.release.PRETTY_NAME` and `os.release.VERSION_ID`, stay together in the minimal set because no arm can tell them apart. On an Alpine base `set-userland gnu` installs the GNU coreutils, findutils, grep, sed and diffutils.

**Interventions the arms carry.** `set-shell` links `/bin/sh` to another shell (`tool.sh.flavor`; on a Debian base `bash` becomes `RUN ln -sf bash /bin/sh`, `dash` is the image's own). `set-pin` sets a project pin that the arm writes as a workspace file: `project.pins.node` as `.nvmrc`, `project.pins.python` as `.python-version`, `project.python.<name>` as a line of `requirements.txt` and `project.checksums.<path>` as a line of `SHA256SUMS`. An arm that carries pins mounts the workspace read-only at `/src`, a tmpfs at `/work`, and copies the workspace and the pin files into `/work` before the command, so the caller's workspace is not changed; the capture inside such an arm adds `--project`.

**Next steps.** A `NEEDS` verdict carries `steps`, each with `kind`, `text`, `command` and `reply`. `capture` is the capture ask that `drift why` prints, with the same `command` (`drift why --save there.json`) and the same piped form in `reply` (`drift why --save there.json && drift why --reply < there.json`); a step that is an ask also carries the ask's `family` and `piped`, `manual-experiment` points at `drift next CASE`, `longer-pilot` and `raise-budget` print the `drift solve` command with `--pilot 30` or a doubled `--budget`, `rerun` repeats the solve after the failing step is fixed, and `unmirrored` names what a container does not mirror.

A `NEEDS` run, with a failure pattern that the program never prints:

```sh
drift solve --run 'sh prog.sh' --fail-when 'no such text' --pilot 3 --budget trials=20,minutes=5 \
  --workspace ws --case stuck bench/data/capsules/locale-tz-date-2.green.json \
  bench/data/capsules/locale-tz-date-2.red.json
```

```
wrote 19 files to stuck
NEEDS: the failure does not reproduce in the container built from the failing capsule: 0 of 5 trials
  failed, so the failure rate is below 60% at 95%
1 candidate changes, 0 experiments run in containers (10 trials, 10 seconds), not on the original
  host.
  Next (capture): The container built from the capsule does not show the cause. Capture the failing
    machine with its tools and project files, which the capsule may lack, and compare it with the
    passing one.
    run: drift why --save there.json
    reply: drift why --save there.json && drift why --reply < there.json
  Next (unmirrored): A container does not mirror cpu.count, kernel.release, tool.coreutils.version,
    tool.libc.version. If one of them differs on the failing machine, change it there to the passing
    value and run the failing command again.
  Next (longer-pilot): The pilot of 3 trials per arm may be too short to see or size a failure that
    does not happen every time; run it at the most it allows, 30.
    run: drift solve bench/data/capsules/locale-tz-date-2.green.json
      bench/data/capsules/locale-tz-date-2.red.json --run 'sh prog.sh' --fail-when 'no such text'
      --pilot 30 --case stuck-next
```

The loop assumes one cause: the first supported experiment ends it. The fixtures in `fixtures/lab/cases` hold one real certificate per verdict class (see [Verify](#pack-report-and-verify)).

Errors before any container starts: `case directory already holds a case: solved`, `--fail-when needs exit or a pattern` (an empty value), `cannot read capsule file: nofile.json`. All exit 1.

## Ingest

`drift ingest CASE RESULTS` adds result files that someone else produced, for example a CI workflow variant that `drift next` described, to a case under the preregistered rule. `RESULTS` is a directory of `*.json` files. Each file names an experiment from the case's plan.

```json
{
  "schema": 1,
  "experiment": "e9",
  "ruleSha256": "<sha256 of the preregistered rule>",
  "arms": [
    { "id": "control", "failures": 0, "trials": 9 },
    { "id": "treatment", "failures": 8, "trials": 9 }
  ]
}
```

The trial count must equal the preregistered count (`trials.perArm` in `experiments/plan.json`; 9 for this case), and `ruleSha256` must equal the hash of the preregistered rule. Drift derives the rule from the plan and never from the result file. `case show` prints the hash on a `rule sha256:` line, and `next` prints it at `--detail detail` and `full`. `ingest --template` writes the files for you: one per experiment of the plan that has no result yet and is not rejected (the manual ones too), with the experiment id, both arm ids, the trial count and the rule hash filled in.

```sh
drift case show case | sed -n 5p
drift ingest case res --template
cat res/e9.json
```

```
rule sha256: 85d28ec6c5ccb9292603be15d730a5685942efb2690a8b0ead6143480948d588 (ingest names it as ruleSha256)
wrote 11 result files to res; set failures for each arm
e9.json
e8.json
e7.json
e6.json
e4.json
e1.json
e2.json
e3.json
e5.json
m1.json
m2.json
{"arms":[{"failures":null,"id":"control","trials":9},{"failures":null,"id":"treatment","trials":9}],"experiment":"e9","ruleSha256":"85d28ec6c5ccb9292603be15d730a5685942efb2690a8b0ead6143480948d588","schema":1}
```

The first line of the template output goes to standard error. `failures` starts as `null`, so a file you did not fill in is refused (`e9.json: control failures are not filled in`) instead of counting as zero failures. Delete the files of experiments you did not run, set `failures` for both arms of the others, and run `ingest` on the directory. `--template` refuses to write over an existing file (`results directory already holds e9.json`).

The counts below are made up to show the mechanics; they are not a measurement of the drangler pipeline.

```sh
mkdir res
printf '{"schema":1,"experiment":"e9","ruleSha256":"%s","arms":[{"id":"control","failures":0,"trials":9},{"id":"treatment","failures":8,"trials":9}]}\n' \
  85d28ec6c5ccb9292603be15d730a5685942efb2690a8b0ead6143480948d588 > res/e9.json
cp -R case icase
drift ingest icase res
```

```
wrote 13 files to icase
e9: supported; control 0 of 9, treatment 8 of 9, p 0.000205
NARROWED: the failure reproduces when deps.@napi-rs/keyring.version, deps.prettier-plugin-sh.version, deps.wrangler.version, tool.node.version, tool.npm.version are set to the failing values (ingested result e9), but it is not confirmed: the minimal-set search and the reverse arm are not in this case
```

The first line goes to standard error. `ingest` can narrow a case or rule a hypothesis out. It never confirms, because confirmation needs the minimal-set and reverse experiments, which are separate preregistered runs. A batch with one bad file applies nothing; `case show` then still reports `integrity: ok`. Refusals, all exit 1:

| Cause | Message |
| --- | --- |
| experiment already in the case | `e9.json: a result for e9 is already in the case` |
| wrong trial count | `e9.json: control has 8 trials, the preregistered count is 9` |
| wrong rule hash | `e9.json: result for e9 names rule 00, but the preregistered rule is 85d28ec6...` |
| experiment not in the plan | `e9.json: experiment e99 is not in the case's plan` |
| malformed JSON | `e9.json: not a result file: unexpected end` |
| no result files | `no *.json result files in empty` |
| directory missing | `cannot list results directory: nodir` |

Each refusal is prefixed with `nothing was ingested:` except the last two.

After an ingest, `drift next` leaves out every experiment that has a recorded result and says so (`Already has a recorded result, so not proposed: e9.`). It does not re-plan: the order of the remaining experiments is the order of the plan made before the results, and `next` says that too.

`ingest --exit-status` maps the verdict it prints to an exit code (see [Exit Codes](#exit-codes)); without it the exit code is 0 whenever the batch was accepted.

## Pack, Report, and Verify

`drift pack CASE --out FILE` packs a solved case directory into a deterministic `.driftcase`, a USTAR tar with sorted names, mode 0644, uid and gid 0, modification time 0 and no compression. The same case directory always produces the same bytes. `pack` refuses a directory without a `certificate.json` (`cannot pack case: not a solved case: no certificate.json`) and one that fails its own integrity check. `arms/` is stored as `reproduce/`.

The fixtures in `fixtures/lab/cases` are real archives. Extract one and pack it again:

```sh
mkdir ex
tar -xf fixtures/lab/cases/locale-tz-date-2.driftcase -C ex
drift pack ex --out packed.driftcase
cmp packed.driftcase fixtures/lab/cases/locale-tz-date-2.driftcase && echo identical
tar -tvf fixtures/lab/cases/locale-tz-date-2.driftcase | head -4
```

```
wrote packed.driftcase: 19 files, 50176 bytes, sha256 50fb8ab1675f74b767e4dcae42cffe96d35979906c0a6d351f125152152d6bc2
identical
-rw-r--r--  0 0      0         212 Dec 31  1969 case.json
-rw-r--r--  0 0      0        5895 Dec 31  1969 certificate.json
-rw-r--r--  0 0      0         436 Dec 31  1969 confirmed/minimal-set.json
-rw-r--r--  0 0      0          27 Dec 31  1969 eliminations/excluded.json
```

The `wrote` line of `pack` goes to standard output. System `tar` reads the archive. A solved case from a real run packs the same way:

```sh
drift pack solved --out solved.driftcase
drift verify solved.driftcase | tail -2
```

```
wrote solved.driftcase: 43 files, 326656 bytes, sha256 57f75f3f149d6e24989c19b05866da046c75f9b7ed9b7211038ec6bfd807fdd6
experiments are not re-run; the recorded results are taken as given
verify: ok
```

**Report.** `drift report FILE --out HTML` renders the static `report.html` that `pack` stored: no script, link, image or external resource, ASCII only, no timestamp.

```sh
drift report fixtures/lab/cases/locale-tz-date-2.driftcase --out report.html
shasum -a 256 report.html | cut -c1-16
```

```
wrote report.html: 7052 characters
29dfc13b8f4884e3
```

Running it twice writes the same file. A missing archive gives `cannot read archive: nofile`, exit 1.

**Verify.** `drift verify FILE` re-derives everything the archive records from its own data: it runs 14 checks in a fixed order and a failing check does not stop the later ones. Experiments are not re-run.

```sh
drift verify fixtures/lab/cases/locale-tz-date-2.driftcase
```

```
PASS tar: 19 entries, canonical
PASS manifest: 18 files match the manifest
PASS observation-chain: 2 observations chain to the head in case.json
PASS results-chain: 3 results continue the observation chain to the recorded head
PASS capsules: both capsules match their observations and the certificate chains from the same head
PASS counts: 3 experiments: every count is the tally of its recorded trials
PASS fisher: 3 exact one-sided p-values re-derived from the recorded counts
PASS rules: 2 preregistered rules match their spec hashes, the plan's alpha and the planned trials
PASS decision: 2 outcomes follow from the counts under the preregistered rules
PASS posteriors: 2 beliefs re-derived by Bayes over the recorded outcomes
PASS ranking: tiers and candidates re-derived from the two capsules
PASS plan: hypotheses, prior and plan re-derived from the ranking and the recorded options
PASS verdict: CONFIRMED follows from the recorded experiments
PASS report: report.html and the confirmed record are what the certificate generates
verdict: CONFIRMED: this dimension causes the failure: env.TZ. Setting it to the failing values moved the failure count from 0 of 5 to 5 of 5 (exact one-sided p 0.003968) and back from 5 of 5 to 0 of 5 when the failing side was set to the passing values (p 0.003968)
experiments are not re-run; the recorded results are taken as given
verify: ok
```

A check can also print `SKIP`: `report` on a plain directory, `ranking` and `plan` on history cases, `posteriors` when an ingested result is present.

`--json` prints the same report as canonical JSON with the keys `checks`, `firstFailure`, `format`, `note`, `ok`, `schema` and `verdict`. Each check holds `id`, `status`, `detail`, `expected` and `recorded`.

```sh
drift verify --json fixtures/lab/cases/limits-nofile-1.driftcase | jq -r '.firstFailure // "none"'
```

```
none
```

`--junit FILE` writes the same checks as a JUnit XML report. The report has one `testsuite` named `drift verify` and one `testcase` per check, named by the check id. A failed check gets a `failure` element whose message is the check line, and a skipped check gets a `skipped` element. The file is written before the command exits, so a failing archive still produces a report. The text or JSON output and the exit code do not change. CI systems that read JUnit XML, such as the GitHub, GitLab and Codecov test result views, can list each check as a test.

```sh
drift verify fixtures/lab/cases/locale-tz-date-2.driftcase --junit verify.xml
```

The text report still goes to standard output. `verify.xml` holds:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<testsuites>
  <testsuite name="drift verify" tests="14" failures="0" errors="0" skipped="0">
    <testcase name="tar" classname="drift verify"/>
    <testcase name="manifest" classname="drift verify"/>
    <testcase name="observation-chain" classname="drift verify"/>
    <testcase name="results-chain" classname="drift verify"/>
    <testcase name="capsules" classname="drift verify"/>
    <testcase name="counts" classname="drift verify"/>
    <testcase name="fisher" classname="drift verify"/>
    <testcase name="rules" classname="drift verify"/>
    <testcase name="decision" classname="drift verify"/>
    <testcase name="posteriors" classname="drift verify"/>
    <testcase name="ranking" classname="drift verify"/>
    <testcase name="plan" classname="drift verify"/>
    <testcase name="verdict" classname="drift verify"/>
    <testcase name="report" classname="drift verify"/>
  </testsuite>
</testsuites>
```

`drift contract verify` takes the same option. Its suite is named `drift contract verify` and each assertion is a testcase named `<kind> <id>`. A breaking assertion is a `failure`, and an unverifiable one is a `skipped` element.

The four archives in `fixtures/lab/cases` cover one verdict each:

| Archive | `verify` verdict line (cut) |
| --- | --- |
| `locale-tz-date-2` | `CONFIRMED: this dimension causes the failure: env.TZ` |
| `runtime-node-api-1` | `CONFIRMED EFFECT (bundle): the arms differ in 2 attributes, so the effect is the bundle's and not one member's: env.NODE_VERSION, tool.node.version` |
| `env-retries-flaky-plain-1` | `NARROWED: no experiment reached significance; 1 stayed inconclusive at 21 trials per arm: env.MAX_RETRIES` |
| `limits-nofile-1` | `STUCK: the failure does not reproduce in the container built from the failing capsule: 0 of 8 trials failed` |

**Tamper evidence.** Flip one recorded trial from `fail` to `pass`. The replacement has the same length, so the tar stays canonical and the check that sees it first is `manifest`:

```sh
python3 -c "
d = open('fixtures/lab/cases/locale-tz-date-2.driftcase', 'rb').read()
open('bt.driftcase', 'wb').write(d.replace(b'\"status\":\"fail\"', b'\"status\":\"pass\"', 1))"
drift verify bt.driftcase 2>/dev/null | grep '^FAIL\|^verify' | cut -c1-110
echo "exit ${pipestatus[1]}"
drift verify --json bt.driftcase 2>/dev/null | jq -r '.checks[] | select(.status != "pass") | .id' | tr '\n' ' '
```

```
FAIL manifest: results/0001.json was changed; expected 5f6997c647d89b96cd77b038f0ce6679a32ae6f68a4cdeceaae5320
FAIL results-chain: results chain broken at entry 0 (results/0001.json): content does not match its hash; expe
FAIL counts: experiment p0 treatment failures; expected 7, recorded 8
FAIL fisher: experiment p0 exact one-sided p; expected 8/11440 (699), recorded 1/12870 (77)
verify: failed at manifest: results/0001.json was changed; expected 5f6997c647d89b96cd77b038f0ce6679a32ae6f68a
exit 1
manifest results-chain counts fisher
```

(`${pipestatus[1]}` is zsh; in bash use `${PIPESTATUS[0]}`.) On failure the report goes to standard output and one extra line, `verification failed: FAIL manifest: ...`, goes to standard error.

`verify` shows that an archive agrees with itself. It does not show that the experiments happened. Someone who rewrites every file, both chains, the hashes, the certificate and the manifest consistently makes an archive that verifies; preregistration is bound by hash, not by time. The technical report describes a second edit that also reseals the chains and the manifest and is still caught by `counts` and `fisher`.

## Open

A `.driftcase` is a tar archive, so a text editor shows binary noise. Studio opens it and shows what `drift verify` finds: the check list, the first failure and the verdict. A tampered or truncated archive shows the failing check in the Case tab and does not crash Studio.

Studio Desktop takes a case three ways: as a launch argument (`Drift /path/x.driftcase`; Windows and Linux pass a double-clicked file this way, not run here), through the macOS open-file event (`java.awt.Desktop.setOpenFileHandler`), and through `drift open`. The packages declare the association for `.driftcase` (MIME type `application/x-driftcase`) with the `fileAssociation` call that the Compose Multiplatform Gradle plugin offers in its `macOS`, `windows` and `linux` blocks (`PlatformSettings.kt` in compose-gradle-plugin 1.12.1). On macOS the generated `Info.plist` of `createDistributable` carries a `CFBundleDocumentTypes` entry for the `driftcase` extension.

```sh
open fixtures/lab/cases/locale-tz-date-2.driftcase
```

With the Drift app registered, that command starts Studio on the Case tab. It was run here from the `createDistributable` app (not the dmg, and not on Windows or Linux).

### drift open

`drift open FILE` hands the archive to Studio and falls back to a report when Studio is not installed.

| Platform | Studio is launched with | Used when |
| --- | --- | --- |
| macOS | `open -a Drift FILE` | the app named Drift exists |
| Windows | `explorer.exe FILE` | `reg query HKCR\.driftcase` finds the association |
| Linux | `xdg-open FILE` | `xdg-mime query default application/x-driftcase` names a handler |

The path always reaches the launcher as one argument and never through `cmd`: a relative path is prefixed with `./` (`.\` on Windows), so a name such as `a&calc.driftcase` or `-x.driftcase` is a literal file name. When no launcher applies or the launch fails, `open` writes the report to `<name>.report.html` beside the case and prints the path. It refuses a case directory and an unreadable file the way `verify` and `report` do. `explorer.exe` reports exit code 1 on success, so on Windows the command counts a started launcher as success.

### From an Editor

No marketplace plugin is involved.

IntelliJ IDEA: under Settings, Editor, File Types, select "Files opened in associated applications" and add the pattern `*.driftcase`. The JetBrains documentation says matching files then open in the default application of the operating system, which is Studio once the package is installed.

VS Code 1.140.0 has no built-in way to open a file in the system application. `workbench.editorAssociations` only maps a file pattern to an editor that VS Code or an extension provides, and Open With lists editors; a search of the bundled workbench found no command for an external application. Three routes work without an extension:

- Run `drift open` from the integrated terminal.
- Choose Reveal in Finder (or Reveal in File Explorer) and double-click the file.
- Add a task that runs `drift open` on the current file. The variable `${file}` is documented for `command` and `args` in `tasks.json`; this route was not run here.

```json
{
  "version": "2.0.0",
  "tasks": [
    {
      "label": "Open Case in Studio",
      "type": "process",
      "command": "drift",
      "args": ["open", "--", "${file}"]
    }
  ]
}
```

A sideloaded `.vsix` is possible and not built. A custom editor for `*.driftcase` would read the archive, take the `report.html` entry from the tar (a USTAR reader of about 100 lines of TypeScript) and show it in a webview. The estimate is two source files and a package under 50 KB, shipped as a release asset. The Drift report is static HTML with no script, so the webview needs no remote content.

## Contract

A contract says what a program needs from an environment, as a list of assertions that every known green capsule satisfies. `drift contract create` writes one from capsules, and `drift contract verify` checks another environment against it. The contract is evidence from the capsules you give it. It does not claim to know what the program depends on.

The file is JSON with one assertion or capsule per line, so a person can read it, delete an assertion and review a change in a diff. It is JSON because the CLI reads only JSON at run time. Assertions come in two kinds:

| Kind | `id` | `expect` |
| --- | --- | --- |
| `attribute` | the attribute path, such as `os.arch`; only static attributes are used | the attribute's value |
| `probe` | the probe id, such as `text.sort`; only probes with status `ok` are used | the SHA-256 of the probe transcript |

Each assertion has a `role`. `discriminating` means a red capsule broke it and no green did. `coincidental` means every green agrees on the value and no red has shown that it matters. The `breaks` list names the red capsules a discriminating assertion tells apart from the greens.

### Create

```sh
C=fixtures/drangler/capsules
drift contract create \
  --from $C/35585969640.json $C/36232485230.json $C/36311117223.json \
         $C/36412389380.json $C/36702683742.json \
  --red $C/36995781138.json $C/37114464625.json $C/37195797966.json \
  -o drangler.contract.json
cat drangler.contract.json
shasum -a 256 drangler.contract.json
```

```
contract: 1 assertion(s) separate 5 green capsule(s) from 3 red capsule(s); no assertion can be removed (24 shared values, 5 ddmin tests)
wrote drangler.contract.json
{
  "assertions": [
    {"breaks":["drangler-36995781138","drangler-37114464625","drangler-37195797966"],"expect":"2026-08-28T16:44:25Z","id":"ci.provisioner.build-date","kind":"attribute","role":"discriminating"}
  ],
  "format": "drift-contract-1",
  "greens": [
    {"hash":"50255d41ca40855469fcef01b1d2f38c807451fa32551984adc5ed2d8d9d0809","label":"drangler-35585969640"},
    {"hash":"b15b8360a447f468ed98525d992ec320fb86d46db42005597c7f55ffdecba8b0","label":"drangler-36232485230"},
    ...
  ],
  "mode": "separating",
  "reds": [
    {"hash":"40380c709345110b5af7fd2c2af356b387b9143a25518407b6704b84091a4cc7","label":"drangler-36995781138"},
    ...
  ],
  "schema": 1
}
01e3954e9d78251503528308251d6dd706ff4edd88a1bc86e1007433497b8125  drangler.contract.json
```

The first two lines go to standard error. Without `-o` the contract goes to standard output.

Of the 24 values that all five greens share, `create` keeps a smallest set that still breaks every red. It uses the same ddmin routine as the minimal causal set. The result is 1-minimal: no assertion can be removed. It is not always the smallest possible set, and when several values separate equally it keeps one of them. Here four values (`ci.provisioner.build-date`, `ci.provisioner.version`, `deps.prettier-plugin-sh.version` and `deps.wrangler.version`) each break all three reds, and `create` kept `ci.provisioner.build-date`. Treat the kept assertion as one member of that group, not as the cause. The `greens` and `reds` lists record each capsule's label and hash. The same capsules in any order give the same bytes, and the macOS, JVM and wasm builds produce the same file.

With no `--red`, `create` lists every static attribute and probe that is the same in all the greens, each `coincidental`.

```sh
drift contract create --from $C/36232485230.json $C/36702683742.json -o greens.json
head -n 5 greens.json
```

```
contract: 25 values are constant across 2 green capsule(s); all are coincidental until a red capsule discriminates them
wrote greens.json
{
  "assertions": [
    {"expect":"2026-08-28T16:44:25Z","id":"ci.provisioner.build-date","kind":"attribute","role":"coincidental"},
    {"expect":"Hosted Compute Agent","id":"ci.provisioner.name","kind":"attribute","role":"coincidental"},
    {"expect":"20260828.587","id":"ci.provisioner.version","kind":"attribute","role":"coincidental"},
```

A red capsule breaks an assertion only when it has the attribute or probe and the value differs. A red that lacks the value breaks nothing. When a red breaks no assertion that every green satisfies, no set separates it, and `create` writes no contract and exits 3. This run passes a green (`36412389380`) as if it had failed:

```sh
drift contract create --from $C/36232485230.json $C/36702683742.json \
  --red $C/36995781138.json $C/36412389380.json
echo $?
```

```
no separating set: red capsule(s) drangler-36412389380 contradict none of the 25 values every green shares
3
```

| Flag | Effect |
| --- | --- |
| `--from CAPSULE...` | one or more capsules of passing runs (required) |
| `--red CAPSULE...` | one or more capsules of failing runs |
| `-o FILE`, `--out FILE` | write the contract to `FILE` instead of standard output |

### Verify

`drift contract verify CONTRACT CAPSULE` reports each assertion as `HOLDS`, `BREAKS` or `UNVERIFIABLE`. An assertion is unverifiable when the capsule has no such attribute, or the probe is missing or has status `unavailable`. The command exits 1 when any assertion breaks. Unverifiable assertions alone do not change the exit code.

```sh
drift contract verify drangler.contract.json $C/36412389380.json
echo $?
drift contract verify drangler.contract.json $C/36995781138.json
echo $?
```

```
HOLDS attribute ci.provisioner.build-date (discriminating): "2026-08-28T16:44:25Z"
contract: 1 hold, 0 break, 0 unverifiable
0
BREAKS attribute ci.provisioner.build-date (discriminating): expected "2026-08-28T16:44:25Z", found "2026-09-01T19:56:44Z"
contract: 0 hold, 1 break, 0 unverifiable
contract broken: attribute ci.provisioner.build-date
1
```

Long values are cut to 72 characters in the text report and shown in full in the JSON. `--json` prints canonical JSON with the keys `schema`, `format` (`drift-contract-verify-1`), `ok`, `holds`, `breaks`, `unverifiable` and `results`; each result has `kind`, `id`, `role`, `status`, `expect` and `found`. `found` is `null` for an unverifiable assertion and the transcript hash for a probe.

```sh
drift contract verify drangler.contract.json $C/37195797966.json --json
```

```
{"breaks":1,"format":"drift-contract-verify-1","holds":0,"ok":false,"results":[{"expect":"2026-08-28T16:44:25Z","found":"2026-09-01T19:56:44Z","id":"ci.provisioner.build-date","kind":"attribute","role":"discriminating","status":"breaks"}],"schema":1,"unverifiable":0}
```

`--capture` checks this machine instead of a capsule file. It captures the environment, runs the probes the contract names, and runs the toolchain scanners when an assertion's path starts with `tool.`. Give either a capsule or `--capture`, not both. A laptop has no CI runner attributes, so the drangler contract cannot be checked here:

```sh
drift contract verify drangler.contract.json --capture
echo $?
```

```
UNVERIFIABLE attribute ci.provisioner.build-date (discriminating): not in the capsule
contract: 0 hold, 0 break, 1 unverifiable
0
```

| Flag | Effect |
| --- | --- |
| `--capture` | check this machine instead of a capsule file |
| `--json` | print the report as canonical JSON |

Exit codes: `contract create` exits 0 when it wrote a contract and 3 when no set separates a red. `contract verify` exits 1 when an assertion breaks. Each exits 1 for a missing or malformed capsule or contract file. `contract create` also exits 1 when it cannot write the file, and `contract verify` exits 1 with neither or both of a capsule and `--capture`.

## Upgrade Check

`drift upgrade-check` answers "what breaks if we move to these versions" from one capsule of today's environment. It sets the target versions on the matching attributes, evaluates every rule with the capsule as side `a` and the changed copy as side `b`, and prints the rules the move triggers in groups. It runs offline with no model, and the same capsule and targets give the same bytes on the JVM and Native builds.

```sh
drift upgrade-check [--json] [--to <name=version,...>] [--to-file <file>] [<capsule>]
```

`<capsule>` is a capsule file or directory. Without it the command captures this machine with `--tools --project`. Capture the project with `drift capture --project` (see [Project Facts](#project-facts)) so the dependency versions are in the capsule; a capsule without them puts every dependency rule under Unknown.

| Option | Meaning |
| --- | --- |
| `--to` | versions to move to, as `php=8.3,drupal=11`; repeatable |
| `--to-file` | a YAML file of flat `name: version` lines (`#` starts a comment); a name in `--to` replaces the same name in the file |
| `--json` | print canonical JSON (`schema` 2) instead of text |

At least one target is required. A name is an alias from the table, or an attribute path (a name that contains a dot, such as `project.gradle.properties.android.builtInKotlin`), which accepts any value. Alias targets must parse as versions. A version without a patch number compares as `.0`, so `drupal=11` means 11.0.

| Alias | Attribute |
| --- | --- |
| `agp` | `project.gradle.agp.version` |
| `composer` | `tool.composer.version` |
| `dart` | `tool.dart.version` |
| `docker` | `tool.docker.version` |
| `drupal` | `project.composer.drupal/core` |
| `elixir` | `tool.elixir.version` |
| `git` | `tool.git.version` |
| `go` | `tool.go.version` |
| `gradle` | `project.gradle.wrapper` |
| `java`, `jdk` | `tool.java.version` |
| `julia` | `tool.julia.version` |
| `kotlin` | `project.gradle.kotlin.version` |
| `node` | `tool.node.version` |
| `npm` | `tool.npm.version` |
| `openssl` | `tool.openssl.version` |
| `php` | `tool.php.version` |
| `python` | `tool.python.version` |
| `r` | `tool.r.version` |
| `ruby` | `tool.ruby.version` |
| `symfony` | `project.composer.symfony/http-kernel` |
| `twig` | `project.composer.twig/twig` |
| `tzdata` | `tz.database.version` |

```sh
drift upgrade-check fixtures/upgrade-check/drupal-10-php-8-1.json --to php=8.3,drupal=11,symfony=7.1
```

```
upgrade-check: drupal-10-site
  drupal   project.composer.drupal/core          10.3.6 -> 11
  symfony  project.composer.symfony/http-kernel  v6.4.12 -> 7.1
  php      tool.php.version                      8.1.34 -> 8.3
84 of 429 rules read a target key

Will break (0)
  none

May break (3)
  1. [high, inferred] Drupal 11 minimum database versions are MySQL 8.0, MariaDB 10.6, PostgreSQL 16 and SQLite 3.45 (json1); Drupal 10 minimums are no longer enough
     rule: drupal-11-database-server-minimums
     boundary: project.composer.drupal/core 10.3.6 -> 11 (rule: was any, now >=11)
     needs: runtime.database.server.kind, runtime.database.server.version; the rule is pending-scanner
     mechanism: The Drupal 11.0.0 release notes list the database driver minimums as MySQL 8.0, MariaDB 10.6, PostgreSQL 16 and SQLite 3.45 with the json1 extension.
     source: https://www.drupal.org/project/drupal/releases/11.0.0
...

Version boundary crossed (2)
  These rules apply to the versions crossed; no project fact or other environment key in the capture matched them.
  1. [medium, verified] PHP 8.2 deprecates creating dynamic properties on classes that do not declare them
     rule: php-8-2-dynamic-properties-deprecated
     boundary: tool.php.version 8.1.34 -> 8.3 (rule: was <8.2.0, now >=8.2.0)
     mechanism: PHP 8.2 deprecates creating dynamic properties unless the class uses the #[\AllowDynamicProperties] attribute.
     source: https://www.php.net/manual/en/migration82.deprecated.php
  2. [medium, inferred] Drupal 10 uses Symfony 6.2; Drupal 11.0 moves core to Symfony 7.1.1, so Symfony 6 code that relies on changed APIs breaks
     rule: drupal-10-to-11-symfony-6-2-to-7-1
     boundary: project.composer.drupal/core 10.3.6 -> 11 (rule: was <11, now >=11)
     boundary: project.composer.symfony/http-kernel v6.4.12 -> 7.1 (rule: was >=6.2,<7, now >=7)
     mechanism: The Drupal 11.0.0 release notes state that Symfony has been upgraded to 7.1.1.
     source: https://www.drupal.org/project/drupal/releases/11.0.0
     source: https://www.drupal.org/project/drupal/releases/10.0.0

Unknown (0)
  none
```

The capsule is a real capture of [`fixtures/projects/composer`](./fixtures/projects/composer) inside the `php:8.1-cli-bookworm` image, saved as [`fixtures/upgrade-check/drupal-10-php-8-1.json`](./fixtures/upgrade-check/drupal-10-php-8-1.json). Each finding gives the severity and basis of the rule, the attributes the move changes with the condition the rule had on each side (`was` for side `a`, `now` for side `b`), what the rule still needs, the mechanism and its sources. Within a group, findings run from high to low severity, then verified before inferred, then by rule id.

The groups come from the three-valued verdict of the rule engine, not from a score:

| Group | Meaning |
| --- | --- |
| Will break | the rule matches, every fact it reads is known, the capsule alone does not already match it, and at least one clause that matched reads a project fact or an environment key that is not a target; the finding lists those keys under `evidence` |
| May break | the targets satisfy the rule's version condition, and the verdict is unknown because another fact it reads is missing from the capsule or is one no scanner emits yet (the output says which, and marks a `pending-scanner` rule) |
| Version boundary crossed | the rule matches, but every clause that matched reads only a target key, so the evidence is the version crossing and nothing about the project; the change applies to those versions and the project may not use the affected feature |
| Outside the tested range | the rule matches and every symptom of the rule has the basis `range`: a compatibility table or release note lists the supported versions and states no failure outside them (the Kotlin Multiplatform compatibility table, the AGP maximum API level and minimum Gradle version, the Gradle JVM range); the group is printed only when it has findings |
| Unknown | the capsule has no current value for a target key, or the target is not a version the rule can compare, so the boundary cannot be judged |

Only rules that read a target key on side `b` and give a different verdict for the targets than for the capsule alone are listed. The line `84 of 429 rules read a target key` counts the rules that read one before that filter. Probe results and run facts describe today's environment, so side `b` has none, and a rule that reads one is unknown.

Locked packages are checked apart from the rules. When a target is `php`, `node` or `python`, `upgrade-check` reads the runtime constraint each locked package records and lists a package whose constraint allows today's runtime and excludes the target under "Will break: locked packages", with the package, its version, the lockfile and the constraint. `drift capture --project` records the constraint as `project.<ecosystem>.<package>.requires.<runtime>` for the formats that carry one, and no rule names the package:

| Lockfile | Constraint read | Key |
| --- | --- | --- |
| `composer.lock` | `require.php` of each package | `project.composer.<package>.requires.php` |
| `package-lock.json` | `engines.node` of each top-level `node_modules` entry | `project.npm.<package>.requires.node` |
| `pnpm-lock.yaml` | `engines: {node: ...}` of each `packages` entry | `project.npm.<package>.requires.node` |
| `poetry.lock` | `python-versions` of each package | `project.python.<package>.requires.python` |

`yarn.lock`, `bun.lock`, `uv.lock`, `requirements.txt` and the Gradle and Maven files record no per-package constraint, so a move to a Node, Python or Java target lists them under "Unchecked package constraints"; the registry or the artifact would have the value and Drift does not fetch it. A constraint that is not a range Drift reads (`dev-master`) is listed there too, and so is every excluding package when the capture lacks today's runtime version. A constraint of `*` adds no key, and the keys of at most 200 packages per ecosystem are kept, like the version keys.

`--json` prints the same findings, with `will-break-packages` (`ecosystem`, `package`, `version`, `runtime`, `constraint`, `from`, `to`, `file`) and `unchecked-packages` (`file`, `runtime`, `reason`, `packages`). A finding holds `rule`, `title`, `severity`, `basis`, `status`, `mechanism`, `crossings` (`key`, `from`, `to`, `was`, `now`), `missing`, `evidence` and `sources`, under the keys `will-break`, `may-break`, `version-boundary`, `outside-range` and `unknown`, next to `targets`, `rules` and `touched`:

```sh
drift upgrade-check fixtures/upgrade-check/drupal-10-php-8-1.json --to php=8.3,drupal=11,symfony=7.1 --json \
  | jq -c '{rules, touched, willBreak: [."will-break"[] | {rule}], versionBoundary: [."version-boundary"[] | {rule, crossings: [.crossings[] | {key, from, to}]}]}'
```

```
{"rules":429,"touched":84,"willBreak":[],"versionBoundary":[{"rule":"php-8-2-dynamic-properties-deprecated","crossings":[{"key":"tool.php.version","from":"8.1.34","to":"8.3"}]},{"rule":"drupal-10-to-11-symfony-6-2-to-7-1","crossings":[{"key":"project.composer.drupal/core","from":"10.3.6","to":"11"},{"key":"project.composer.symfony/http-kernel","from":"v6.4.12","to":"7.1"}]}]}
```

The targets can sit in a file instead. This file gives the same result:

```yaml
php: 8.3
drupal: 11
symfony: '7.1'
```

```sh
drift upgrade-check fixtures/upgrade-check/drupal-10-php-8-1.json --to-file stack.yml --json | jq -c '[."version-boundary"[].rule]'
```

```
["php-8-2-dynamic-properties-deprecated","drupal-10-to-11-symfony-6-2-to-7-1"]
```

A rule that reads a project fact or another environment key lands under Will break and names it. Moving the same Drupal 10 capsule down to PHP 7.4 breaks two rules on the Drupal and Symfony versions the capsule holds, and one rule that reads only the PHP version stays under Version boundary crossed:

```sh
drift upgrade-check fixtures/upgrade-check/drupal-10-php-8-1.json --to php=7.4
```

```
upgrade-check: drupal-10-site
  php  tool.php.version  8.1.34 -> 7.4
57 of 429 rules read a target key

Will break (2)
  1. [high, inferred] Drupal 10 cannot run on PHP 8.0 or earlier; PHP 8.0 sites must move to 8.1 before upgrading core
     rule: drupal-10-php-8-1-minimum
     boundary: tool.php.version 8.1.34 -> 7.4 (rule: was >=8.1, now <8.1)
     evidence: project.composer.drupal/core
     mechanism: The Drupal 10.0.0 release notes state that Drupal 10 requires PHP 8.1 or higher, and the Drupal 10 minimum PHP change record states that it will not be possible to run Drupal 10 on PHP 8.0 or earlier.
     source: https://www.drupal.org/project/drupal/releases/10.0.0
     source: https://www.drupal.org/node/3264830
  2. [high, inferred] Symfony 6.1 declares PHP 8.1 or higher, so a PHP 8.0 host cannot install it
     rule: symfony-6-1-php-8-1-minimum
     boundary: tool.php.version 8.1.34 -> 7.4 (rule: was >=8.1, now <8.1)
     evidence: project.composer.symfony/http-kernel
     mechanism: Symfony 6.1.0 declares require.php as >=8.1 in its composer.json, while 6.0.0 declares >=8.0.2.
     source: https://raw.githubusercontent.com/symfony/symfony/v6.1.0/composer.json
     source: https://raw.githubusercontent.com/symfony/symfony/v6.0.0/composer.json

May break (3)
  1. [high, verified] Laravel 10 declares PHP 8.1 or higher, so a PHP below 8.1 host cannot install it
     rule: laravel-10-php-8-1-minimum
     boundary: tool.php.version 8.1.34 -> 7.4 (rule: was >=8.1, now <8.1)
     needs: project.composer.laravel/framework
     mechanism: The composer.json of Laravel 10 at tag v10.0.0 requires php ^8.1.
     source: https://raw.githubusercontent.com/laravel/framework/v10.0.0/composer.json
  2. [high, verified] Laravel 9 declares PHP 8.0.2 or higher, so a PHP below 8.0.2 host cannot install it
     rule: laravel-9-php-8-0-2-minimum
     boundary: tool.php.version 8.1.34 -> 7.4 (rule: was >=8.0.2, now <8.0.2)
     needs: project.composer.laravel/framework
     mechanism: The composer.json of Laravel 9 at tag v9.0.0 requires php ^8.0.2.
     source: https://raw.githubusercontent.com/laravel/framework/v9.0.0/composer.json
  3. [high, verified] TYPO3 12 declares PHP 8.1 or higher, so a PHP below 8.1 host cannot install it
     rule: typo3-12-php-8-1-minimum
     boundary: tool.php.version 8.1.34 -> 7.4 (rule: was >=8.1, now <8.1)
     needs: project.composer.typo3/cms-core
     mechanism: The composer.json of TYPO3 12 at tag v12.0.0 requires php ^8.1.
     source: https://raw.githubusercontent.com/TYPO3/typo3/v12.0.0/typo3/sysext/core/composer.json

Version boundary crossed (1)
  These rules apply to the versions crossed; no project fact or other environment key in the capture matched them.
  1. [medium, verified] PHP 8 compares a number with a non-numeric string as strings
     rule: php-8-string-number-comparison
     boundary: tool.php.version 8.1.34 -> 7.4 (rule: was any, now differs on >=8.0.0)
     mechanism: PHP 8 casts the number to a string and compares strings when the other operand is non-numeric, so loose comparisons that were true in PHP 7 are false.
     source: https://github.com/php/doc-en/blob/master/appendices/migration80/incompatible.xml

Unknown (0)
  none
```

A capsule that lacks the key a target replaces lists the dependency rules under Unknown. This is the PHP 7.4 capsule from [`bench/data/capsules`](./bench/data/capsules), which has no Drupal facts:

```sh
drift upgrade-check bench/data/capsules/migration-php-json-validate-1.red.json --to drupal=11
```

```
upgrade-check: red
  drupal  project.composer.drupal/core  unknown -> 11
21 of 429 rules read a target key

Will break (0)
  none

May break (0)
  none

Version boundary crossed (0)
  none

Unknown (10)
  1. [high, inferred] Drupal 10 minimum database versions are MySQL 5.7.8, MariaDB 10.3.7, PostgreSQL 12 (pg_trgm) and SQLite 3.26 (JSON1)
     rule: drupal-10-database-server-minimums
     boundary: project.composer.drupal/core unknown -> 11 (rule: was any, now >=10)
     needs: project.composer.drupal/core, runtime.database.server.kind, runtime.database.server.version; the rule is pending-scanner
     mechanism: The Drupal 10.0.0 release notes list the minimum supported database versions as MySQL or Percona 5.7.8, MariaDB 10.3.7, PostgreSQL 12 with the pg_trgm extension, and SQLite 3.26 with the JSON1 extension.
     source: https://www.drupal.org/project/drupal/releases/10.0.0
...
```

A bad target stops the command with exit code 1 and no output on standard output: `php=zzz is not a version`, `unknown target 'rust'` (the message lists the aliases), a name given twice with different values (`java=21,jdk=17`), and an unreadable `--to-file`.

### Upgrade Check Limits

- The rules were written as a passing side and a failing side, so many describe a move in one direction only. A rule that says "side `a` is JDK 20 or newer and side `b` is older" fires for a downgrade and not for `java=17` to `java=21`. A move that triggers no rule shows nothing under Will break; it does not show that the move is safe.
- A rule under Version boundary crossed says the version change is documented, not that the project is affected. The capsule held no source fact for it to read, so check whether the project uses the feature the rule names.
- Targets are independent. `drupal=11` does not set `symfony=7.1` or `php=8.3`; a rule that needs those lists them under May break or Unknown until they are given.
- Everything the targets do not name stays at its captured value on side `b`, including the operating system and the tools.
- A rule that reads source facts (`project.java.*`, `project.symfony.source.*`) or other keys no scanner emits stays under May break, with the keys listed under `needs`. The rule catalog has no rule for APIs that a version only adds, such as `json_validate()` in PHP 8.3, so adding an API is never reported.
- Group counts change when the rule catalog changes. The counts above are from this checkout.

## Atlas

The Atlas asks, per probe, whether a Kotlin program prints the same thing on every target. It holds 38 probes. `atlas record` runs them on this target, `atlas build` aggregates transcripts into a dataset, `atlas compare` places this device against it, `atlas show` prints the matrix, the oracle or a draft for one probe and `atlas diff` lists what changed between two Kotlin versions.

**Columns.** `fixtures/atlas/` holds 16 recorded columns on Kotlin 2.4.20, and `fixtures/atlas/2.5.0-beta1/` holds 5 on 2.5.0-Beta1 (`jvm`, `macos`, `wasm`, `android`, `ios`). [`atlas/columns.yml`](./atlas/columns.yml) says how each was measured.

| Column | Where it was recorded |
| --- | --- |
| `jvm` | the CLI JVM build on Temurin 21, macOS arm64 |
| `jvm-17`, `jvm-21`, `jvm-25` | the same classes (JVM target 17) on Temurin 17, 21 and 25, x86-64 Linux containers |
| `linux`, `linux-arm64` | Kotlin/Native release executables linked on macOS, run in Debian containers |
| `macos` | Kotlin/Native on macOS arm64 |
| `wasm` | Kotlin/Wasm on Node, macOS arm64 |
| `wasm-chromium`, `wasm-firefox`, `wasm-webkit` | the Studio web build in each browser, driven by Playwright in a Linux container (WebKit here is not Safari) |
| `cloudflare-wasm`, `cloudflare-jvm` | Workers on workerd under `wrangler dev`, macOS arm64 (see [Probe](#probe)) |
| `android` | the debug build on an arm64 emulator (Pixel 7 profile, API 37) |
| `ios` | the simulator build on an iPhone 17 Pro simulator |

`mingw` and `jvm-windows` are recorded by the `atlas.yml` workflow and are not in the fixtures yet. `fixtures/atlas-extra/` holds three more transcripts that stay out of the dataset; its README says why. `fixtures/atlas-variants/` holds the Ubuntu-linked `linux` and `linux-arm64` transcripts, which differ from the committed columns on three math lines.

**Record.** `--out DIR` writes `DIR/<target>.txt`. Without `--out` the transcript goes to standard output and the summary line to standard error. `--target LABEL` sets the label in the header; the default is the platform (`macos`). A label may use letters, digits, `.`, `_` and `-`.

```sh
drift atlas record --out tr
cmp tr/macos.txt fixtures/atlas/macos.txt && echo identical-to-fixture
head -3 tr/macos.txt
drift atlas record --target "bad label"
```

```
wrote 38 probes for macos on kotlin 2.4.20 to tr/macos.txt
identical-to-fixture
# kotlin.version = 2.4.20
# kotlin.target = macos
@@ kotlin.char.bmp-case-mapping
--target may only use letters, digits, '.', '_' and '-': bad label
```

A transcript starts with `# kotlin.version = ...` and `# kotlin.target = ...`, then one `@@ <probe id>` section per probe. The target label and the Kotlin version name a column, `macos@2.4.20`, and divergence is judged inside one version only. The version is the Kotlin version the executable was built with, and a pre-release qualifier is not kept (a build on 2.5.0-Beta1 writes `2.5.0`), so the Beta fixtures carry the header `2.5.0-Beta1` by hand.

**Build.** `atlas build TRANSCRIPT... --out FILE` writes the dataset as canonical JSON. Pass files, not directories. The first command builds three columns, and the second takes every committed column through a shell glob.

```sh
drift atlas build fixtures/atlas/jvm.txt fixtures/atlas/macos.txt fixtures/atlas/wasm.txt --out atlas.json
drift atlas build fixtures/atlas/*.txt fixtures/atlas/2.5.0-beta1/*.txt --out atlas-all.json
```

```
dataset: 38 probes, 3 columns, 23 divergent, sha256 30fad6394bf85d3f8687b7932531cc6a544afebad6af469fe551ffe41a382c93
dataset: 38 probes, 21 columns, 32 divergent, sha256 17f82fefbd865c8521439dbc320bb817780254806b134505711fefd3cca268c1
```

The summary goes to standard error. The sha256 is the hash of the dataset body; the file on disk has one more trailing newline. Building twice gives identical files, and the order of the arguments does not change the bytes. `--out` is required. `build` refuses a transcript that lacks a probe of the catalog, names an unknown probe, lacks a header (`bad transcript header in badt.txt: missing kotlin.target`) or duplicates a column (`fixtures/atlas/jvm.txt and fixtures/atlas/jvm.txt both record jvm@2.4.20`).

**Show.** Without `--probe`, `atlas show` prints the matrix: one row per probe, one letter per distinct output, and a repeated letter on a row marks a byte-identical transcript.

```sh
drift atlas show atlas.json | sed -n 1,6p
drift atlas show atlas.json | tail -5
```

```
atlas: 38 probes, 3 columns (jvm@2.4.20, macos@2.4.20, wasm@2.4.20)

probe                                     classification    jvm@2.4.20  macos@2.4.20  wasm@2.4.20
kotlin.char.bmp-case-mapping              platform-defined  A           B             B
kotlin.char.bmp-categories                platform-defined  A           B             B
kotlin.char.case-special                  unclassified      A           A             A
a letter repeated on a row marks a byte-identical transcript
divergent probes: 23 of 38
  documented: 3 of 10
  platform-defined: 2 of 4
  unclassified: 18 of 24
```

With more than one Kotlin version in the dataset the summary adds a line per version:

```sh
drift atlas show atlas-all.json | tail -3
```

```
divergent probes per kotlin version:
  2.4.20: 32
  2.5.0-Beta1: 18
```

**Classification.** Every probe carries one of three labels, written by hand from primary sources in [`atlas/classification.yml`](./atlas/classification.yml) and embedded in the dataset:

| Label | Meaning | Probes |
| --- | --- | --- |
| `documented` | a Kotlin documentation page states the behavior | 10 |
| `platform-defined` | a JDK or ECMAScript page does | 4 |
| `unclassified` | no explanation was found; the entry records what was searched | 24 |

Each entry also has a `basis` (`verified` when the cited page states the contract, `inferred` when the page implies it), the references with a short quote, and the linked issue ids with the state read on the research date. `unclassified` is not a finding. The 24 divergent unclassified probes each have a repro draft under [`atlas/repros/`](./atlas/repros/).

**Oracle.** `atlas show DATASET --oracle` gives every divergent line one kind. A divergent line is a line of one probe whose transcript differs between targets on one Kotlin version.

| Kind | Meaning | Reportable |
| --- | --- | --- |
| `documented-contract` | a reference quote that names the line states the expected value | yes |
| `cross-version-change` | a target prints another value on another Kotlin version, and no quote decides which is right | no |
| `crash` | a column failed instead of printing values | yes |
| `no-correctness-claim` | none of the above | no |

A value that changed between versions is a cross-version change and is never called a regression. A regression needs a documented contract that says which value is right, and the oracle holds the quote before it uses that kind. Each reference in `atlas/classification.yml` can list the transcript lines its quote decides under `decides`; a quote that only allows the difference leaves the list empty. A failed column makes one `crash` entry for the probe, except when the research notes the whole probe as a documented or platform-defined limit. The Kotlin/JS reflection page says `KClass` is limited there, so the js-ir failure on `kotlin.collections.tostring-forms` is a `no-correctness-claim`.

```sh
drift atlas show atlas-all.json --oracle | sed -n 7,17p
drift atlas show atlas-all.json --oracle | sed -n '/^reportable at kotlin 2.5.0-Beta1/,/^  0.49999999999999994/p'
```

```
kotlin 2.4.20: 258 divergent lines
  documented-contract: 15
  cross-version-change: 31
  crash: 4
  no-correctness-claim: 208

kotlin 2.5.0-Beta1: 94 divergent lines
  documented-contract: 4
  cross-version-change: 4
  crash: 0
  no-correctness-claim: 86
reportable at kotlin 2.5.0-Beta1:
kotlin.math.pow-special-cases
  documented-contract (reportable): 1^nan
    expected: "b.pow(NaN) is NaN"
    source: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.math/pow.html
  1^nan:
    android, ios, macos: 1^nan = 1.0
    jvm, wasm: 1^nan = NaN
kotlin.math.rounding
  documented-contract (reportable): 0.49999999999999994, 4503599627370497, float-8388609
    expected: "Rounds this Double value to the nearest integer and converts the result to Int"
    source: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.math/round-to-int.html
  0.49999999999999994:
```

The listing then prints every other divergent line grouped by probe and kind under `no report offered`. The negated NaN line of `kotlin.double.nan-bits` (`neg-nan-raw`) is one of them: Native 2.4.20 and 2.5.0-Beta1 print the same value, no quote names the line, so it carries no claim. In Studio, the probe detail lists the same kinds under `oracle on kotlin VERSION`. `--oracle` cannot be combined with `--probe`.

**Compare.** `atlas compare DATASET TRANSCRIPT` places this device against each recorded column. `--detail summary` prints counts; `detail` adds the probes that differ.

```sh
drift atlas compare atlas.json tr/macos.txt
drift atlas compare atlas-all.json tr/macos.txt | tail -21
```

```
this device: macos, kotlin 2.4.20, 38 probes measured
dataset: 38 probes, 3 columns: jvm@2.4.20, macos@2.4.20, wasm@2.4.20
matches every column: 15
matches some columns: 23
matches no column: 0
not measured here: 0
per column:
  jvm@2.4.20: 21 of 38 probes match
  macos@2.4.20: 38 of 38 probes match
  wasm@2.4.20: 25 of 38 probes match
per column:
  android@2.4.20: 20 of 38 probes match
  android@2.5.0-Beta1: 20 of 38 probes match
  cloudflare-jvm@2.4.20: 15 of 38 probes match
  cloudflare-wasm@2.4.20: 25 of 38 probes match
  ios@2.4.20: 38 of 38 probes match
  ios@2.5.0-Beta1: 37 of 38 probes match
  js-ir@2.4.20: 11 of 38 probes match
  jvm@2.4.20: 21 of 38 probes match
  jvm@2.5.0-Beta1: 21 of 38 probes match
  jvm-17@2.4.20: 22 of 38 probes match
  jvm-21@2.4.20: 21 of 38 probes match
  jvm-25@2.4.20: 21 of 38 probes match
  linux@2.4.20: 36 of 38 probes match
  linux-arm64@2.4.20: 37 of 38 probes match
  macos@2.4.20: 38 of 38 probes match
  macos@2.5.0-Beta1: 37 of 38 probes match
  wasm@2.4.20: 25 of 38 probes match
  wasm@2.5.0-Beta1: 32 of 38 probes match
  wasm-chromium@2.4.20: 25 of 38 probes match
  wasm-firefox@2.4.20: 25 of 38 probes match
  wasm-webkit@2.4.20: 25 of 38 probes match
```

The Mac that recorded the transcript matches its own column on 38 of 38 probes, which makes a useful check that a fresh recording is unchanged. A column from another machine can differ for hardware reasons (for example the sign bit of a NaN), and a Linux executable linked on Ubuntu instead of macOS prints different last digits for three lines of `kotlin.math.transcendental-bits`. A mismatch against a column recorded elsewhere is not a Kotlin finding by itself.

**Here.** `atlas here` runs the 38 probes on this machine and compares the result with the recorded columns for this build's Kotlin version. `--version V` compares with another version in the dataset. The command makes no network call, and the device label holds the platform and the OS family only.

```sh
drift atlas here
drift atlas here --out device.txt
drift atlas here --exit-status
```

The output starts with the summary that `atlas compare` prints. Each probe that does not match every column follows, with the columns it matches, the columns it differs from and each differing line. A line gets one oracle kind: `documented-contract` when a quoted reference names the line, the dataset's own kind for the line when the recorded columns disagree on it, and `no-correctness-claim` otherwise. A probe that did not run on the device is reported as `no-correctness-claim (the probe did not run here)`.

`--out FILE` writes this device as a transcript in the format `atlas build` reads, so it can be attached to an issue. `--exit-status` exits with 10 when a probe that ran matches no recorded column. A probe that did not run does not count.

The JVM build of the CLI labels this device `jvm-linux`. Studio adds the Java feature version on the JVM (`jvm-21-linux`), and the CLI does not yet, so the label can differ from Studio's on the same machine. Output below is from the Linux x64 lane, the JVM build on kotlin 2.4.20, trimmed to the summary and one probe block (the full run prints 1,884 lines):

```
this device: jvm-linux, kotlin 2.4.20, 38 probes measured
dataset: 38 probes, 16 columns: android@2.4.20, cloudflare-jvm@2.4.20, ..., wasm-webkit@2.4.20
matches every column: 6
matches some columns: 31
matches no column: 1
not measured here: 0
per column:
  android@2.4.20: 28 of 38 probes match
  ...
  jvm-21@2.4.20: 37 of 38 probes match
  ...

kotlin.double.nan-bits [unclassified] matches no column
  matches: none
  differs: android@2.4.20, cloudflare-jvm@2.4.20, cloudflare-wasm@2.4.20, ios@2.4.20, js-ir@2.4.20, jvm@2.4.20, jvm-17@2.4.20, jvm-21@2.4.20, jvm-25@2.4.20, linux@2.4.20, linux-arm64@2.4.20, macos@2.4.20, wasm@2.4.20, wasm-chromium@2.4.20, wasm-firefox@2.4.20, wasm-webkit@2.4.20
  line 2 (this device): 0/0-raw = fff8000000000000
  line 2 (android@2.4.20, cloudflare-jvm@2.4.20, cloudflare-wasm@2.4.20, jvm@2.4.20, wasm@2.4.20): 0/0-raw = 7ff8000000000000
  kind: no-correctness-claim
  ...
```

The `kotlin.double.nan-bits` block is the sign-bit difference that the compare example above describes. The probe has no classification, so no oracle claim applies.

**Version changes.** `atlas diff DATASET --from X --to Y [--target T]` lists the probes whose transcript differs between two Kotlin versions on the targets recorded for both. A target recorded for only one of the two versions is named and left out. `--detail summary` prints counts, `detail` (the default) adds each changed probe with the differing lines, and `full` adds the probes that did not change. Versions match ignoring case. The ladder dataset is `fixtures/atlas` plus `fixtures/atlas-ladder` without its duplicate `2.4.20/linux.txt`: `./gradlew atlasLadderDataset` writes it to `build/atlas-work/ladder.json`, and `atlasSite` publishes it as `data/ladder.json` and as the `versions.html` page, which lists the change between each pair of consecutive versions per target.

```sh
drift atlas diff ladder.json --from 2.4.21 --to 2.5.0-Beta1 --target js-ir
```

```
kotlin 2.4.21 to 2.5.0-Beta1: 1 of 38 probes change
targets compared: js-ir
measured on 2.5.0-Beta1 only: android, ios, wasm
per target:
  js-ir: 1 of 38 change (0 became available, 0 became unavailable)

kotlin.exceptions.messages [unclassified]
  js-ir: changed
    line 8 (2.4.21): long-div-zero = err:Exception:division by zero
    line 8 (2.5.0-Beta1): long-div-zero = err:ArithmeticException:/ by zero
```

A probe that did not compile on the older version and runs on the newer one shows as `became available` with the first compiler error, so an API that appears is separate from a value that changes.

**Repro drafts.** `atlas show DATASET --probe ID` prints a draft for one probe. It begins `GENERATED DRAFT, NOT REVIEWED`.

```sh
drift atlas show atlas.json --probe kotlin.collections.iterator-modification | sed -n 1,13p
drift atlas show atlas.json --probe kotlin.collections.iterator-modification \
  | sed -n '/^kotlin 2.4.20: 2 different/,/^controls/p'
```

```
GENERATED DRAFT, NOT REVIEWED
drift atlas show wrote this from recorded transcripts. A person must read it, rerun the source by
hand and rewrite it in their own words before anything is filed. Drift does not file, post or
contact anyone.

probe: kotlin.collections.iterator-modification
question: Which structural changes during iteration throw, and which pass silently?
classification: platform-defined
basis: verified
reference: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/ArrayList.html "the fail-fast behavior of an iterator cannot be guaranteed"
reference: https://developer.android.com/reference/java/util/ArrayList "the fail-fast behavior of an iterator cannot be guaranteed"
linked issues: KT-88775 (Open when read), KT-89031 (To be discussed when read)
measured: kotlin 2.4.20 on jvm, macos, wasm
...
kotlin 2.4.20: 2 different results
differing lines: 1 of 14
  line 4
    jvm: list-remove-last = err:ConcurrentModificationException
    macos, wasm: list-remove-last = ok:[1, 2]
controls: 13 lines are the same on every target
```

For an `unclassified` probe the draft adds a `searched:` entry that lists the tracker queries and the pages read.

```sh
drift atlas show atlas-all.json --probe kotlin.double.nan-bits | sed -n 8,17p | cut -c1-110
```

```
classification: unclassified
basis: verified
reference: https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.2.3 "IEEE 754 allows multiple d
reference: https://webassembly.github.io/spec/core/exec/numerics.html "Some operators are non-deterministic, b
reference: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/to-raw-bits.html "preserving NaN values exact 
reference: https://tc39.es/ecma262/#sec-ecmascript-language-types-number-type "are represented in ECMAScript a
linked issues: KT-53258 (In Progress when read)
  searched: YouTrack: "NaN toRawBits", "Double toBits NaN canonical", "NaN unaryMinus sign Native",
  "Double.NaN raw bits negative", "unaryMinus NaN", "NaN sign bit", "toRawBits". Pages read in full:
  JLS 4.2.3, WebAssembly numerics appendix, Kotlin toRawBits and unaryMinus pages. 0/0 and sqrt
  follow the CPU; no page, issue or commit explains the Native result for neg-nan-raw.
```

`cut` shortens the reference lines here. The rest of a draft holds the probe source, the research notes, which are also unreviewed, and a `before filing` checklist: search the tracker, check the reference page, rerun the source on the newest release and on each target by hand, and write the report yourself. The issue ids come from the probe notes, and the tracker is not queried. Review the draft before it goes anywhere: it states what the transcripts show, not that the behavior is a bug. An unknown id gives `no probe nope in the dataset`, exit 1.

**Dataset and site from Gradle.** Two tasks build the same data without calling the executable yourself.

```sh
./gradlew atlasDataset
./gradlew atlasSite
```

`atlasDataset` runs the JVM CLI's `atlas build` over every `fixtures/atlas/**/*.txt` file and writes `build/atlas-work/dataset.json`. The file is byte-identical to the `atlas-all.json` built above (`cmp` found no difference). `atlasSite` builds the Studio web distribution and writes a static site to `build/atlas-site`: Studio at `index.html`, one page per probe in `probe/` (38 pages and an index), `data/dataset.json`, `data/classification.json` and the transcripts in `data/transcripts/`. Serve it like any build:

```sh
drift serve --dir build/atlas-site --port 0 --quiet
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' http://localhost:PORT/probe/kotlin.char.case-special.html
```

```
200 text/html; charset=utf-8
```

`./gradlew docsSite` runs `dokkaGenerate` and `atlasSite` first, then assembles `build/site`: `README.md` as `index.html`, `ADVANCED_USAGE.md` as `advanced-usage.html`, `TECHNICAL_REPORT.md` as `technical-report.html`, `MANUAL.md`, and the guides in `docs/`, each rendered into `assets/site-template.html`, the Atlas site under `atlas/`, the Dokka site under `engine/`, the icons from `assets/`, `CNAME` and `.nojekyll`. A relative link in these Markdown files to a repository path that does not exist fails the render, and a link to one that does exist becomes a GitHub URL on `master`. `checkSiteLinks` runs after it and fails on a relative `href` or `src` that does not resolve inside `build/site`. It finds no unresolved link outside `engine/`; Dokka's unresolved links there are counted and printed and do not fail it.

The `docs.yml` workflow runs `./gradlew dokkaGenerate atlasSite docsSite` and pushes `build/site` to the `gh-pages` branch. It builds on pushes to `master`, `ver/*`, `feat/*` and `renovate/*` and deploys only from `master`, with a `CNAME` file for `drift.gmitch215.dev`, where the site is live.

## Probe

`probe cloudflare` runs the Kotlin probes in a Cloudflare Worker and writes the transcript in the Atlas format, the same file `atlas record` writes. The default run is local: `wrangler dev --local` serves the Worker on workerd at `127.0.0.1`, so it needs no network and no Cloudflare account. It needs Node and `wrangler` (on `PATH`, or through `npx wrangler`), and a directory that holds a built Worker.

Drift has no HTTP client and cannot stop a process it started, so it hands the work to Node. It runs `node -e` with a short script that starts `wrangler dev`, waits for the `Ready on` line, requests the Worker once, prints the response between two marker lines and stops wrangler. Drift reads the output, checks that the text is a transcript, rewrites the `kotlin.target` header to `--target` and writes `<out>/<target>.txt`.

| Option | Meaning |
| --- | --- |
| `--worker DIR` | the built Worker: the directory that holds `wrangler.jsonc` (required) |
| `--target LABEL` | the column label (default `cloudflare`) |
| `--out DIR` | write `DIR/<target>.txt`; without it the transcript goes to standard output |
| `--wrangler CMD` | the wrangler command, for example `npx wrangler@4` (default: `wrangler` if it runs, else `npx wrangler`) |
| `--port N` | the port for `wrangler dev`; `0` picks a free one |
| `--timeout S` | seconds to wait for wrangler and for the Worker (default 120) |
| `--deploy` | deploy to your Cloudflare account instead of running locally |
| `--dry-run` | with `--deploy`, run `wrangler deploy --dry-run`: bundle only, nothing is uploaded |

**Building the Workers.** `tools/cloudflare` is a standalone Gradle build that Drift's own build does not include. It compiles the probe sources from `scan` and `core` into two Workers:

```sh
cd tools/cloudflare
../../gradlew :wasm:stageWorker :jvm:buildWorker
(cd jvm/build/bytebox/worker && npm install)
```

`wasm/build/worker` is the Kotlin/Wasm (`wasmJs`) probe module with a 14-line JavaScript entry that replaces Kotlin's loader, which does not run on workerd. `jvm/build/bytebox/worker` is the probes compiled as Kotlin/JVM and turned into a WasmGC Worker by the [bytebox](https://github.com/gmitch215/bytebox) 1.0.3 Gradle plugin, which uses TeaVM. bytebox is an external build dependency of that directory only; nothing else in Drift refers to it.

```sh
drift probe cloudflare --worker tools/cloudflare/wasm/build/worker --target cloudflare-wasm --out tr
drift probe cloudflare --worker tools/cloudflare/jvm/build/bytebox/worker --target cloudflare-jvm --out tr
```

```
wrangler dev ready on http://127.0.0.1:64193
wrote 38 probes for cloudflare-wasm on kotlin 2.4.20, 0 unavailable to tr/cloudflare-wasm.txt
wrangler dev ready on http://127.0.0.1:64228
wrote 38 probes for cloudflare-jvm on kotlin 2.4.20, 4 unavailable to tr/cloudflare-jvm.txt
```

Both runs used wrangler 4.146.0 and workerd 1.20261001.1 on macOS arm64. `cloudflare-wasm` is byte-identical to the `wasm` column apart from the target line. `cloudflare-jvm` runs TeaVM's class library, not a JDK, and it differs from every other column on 18 probes. Four probes are recorded as `[UNAVAILABLE]`:

| Probe | Why |
| --- | --- |
| `kotlin.exceptions.messages`, `kotlin.int.overflow` | the compiled module traps on integer division (`divide by zero`, `divide result unrepresentable`) and a trap aborts the whole request, so the Worker skips both probes and records the reason |
| `kotlin.exceptions.tostring-cause` | the probe fails with a `NullPointerException`; the cause inside TeaVM's class library was not isolated |
| `kotlin.regex.anchors-lines` | the build refuses `java.util.regex.Matcher.useAnchoringBounds`, which `Regex.matchAt` calls, so the `matchAt` lines are replaced by a thrown exception in the Worker's copy of the source; TeaVM 0.15.0 declares that method in `TMatcher`, so the cause of the refusal is not confirmed |

kotlinx-coroutines does not build through bytebox, and the Kotlin probe family has no probe that needs it, so none is refused for that reason.

**Deploying.** `--deploy` runs `wrangler deploy` against your account, requests the new Worker and writes the transcript. Before it deploys it prints a price estimate from the Workers pricing page:

```sh
drift probe cloudflare --worker tools/cloudflare/wasm/build/worker --deploy --dry-run
```

```
Deploying sends one request to a new Worker on your Cloudflare account, and up to 10 more while it propagates.
Free plan: 100,000 requests per day and 10 ms of CPU per invocation at no charge; a run that needs more CPU than that fails there.
Paid plan (5 USD a month minimum): 10 million requests and 30 million CPU ms a month included, then 0.30 USD per million requests and 0.02 USD per million CPU ms.
The Worker stays deployed until you delete it with wrangler delete.
Source: https://developers.cloudflare.com/workers/platform/pricing/, read 2026-10-12.
⛅️ wrangler 4.146.0 (update available 4.149.0)
───────────────────────────────────────────────
Total Upload: 372.15 KiB / gzip: 138.15 KiB
No bindings found.
--dry-run: exiting now.
```

The figures are the ones on the pricing page on the date shown; the command does not fetch the page again. The bundle of the bytebox Worker uploads 1107.00 KiB in the same dry run. A real `--deploy` ran once for each Worker on a free account (38 probes each, 4 unavailable on `cloudflare-jvm`, uploads of 372.15 KiB and 1107.00 KiB, no charge). The deployed output matched the local fixtures except for the sign bit of NaN results: `0/0-raw` printed `fff8000000000000` deployed against `7ff8000000000000` locally on both Workers, and `sqrt-neg-raw` the same on the JVM Worker. Both Workers were deleted afterwards.

## Triage

`drift triage` reads the text of an issue and says what it can about the reporter's environment: facts as Drift attribute keys, the rules that apply, statements that contradict what Drift knows, and a draft rule when the report suggests a new one. It runs offline with no model. The same text gives the same bytes on the JVM, Native and Wasm builds, because the arithmetic is integer and the text scanning has no regular expressions.

```sh
drift triage [--json] [--publish-threshold <0 to 1>] [--learned <dir>] [--confidence <table.json>] [--author <login>] [--date <yyyy-mm-dd>] <issue>
drift triage calibrate --corpus <file.json|file.json.zst> --out <table.json> [--seed <text>] [--min-facts <n>]
```

`<issue>` is a Markdown file, or `-` for standard input (the build reads `/dev/stdin`, so `-` does not work on Windows). The input is an issue body as the host renders it: a GitHub issue form (`### Label`, then the value), a GitLab description template (`## Label` or `**Label**`), `Key: value` lines, tables, pasted tool output, or free text. Text longer than 262,144 characters is cut there and the result says so.

| Option | Meaning |
| --- | --- |
| `--json` | print canonical JSON (`schema` 1) instead of text |
| `--publish-threshold` | confidence from 0 to 1 at which a draft rule is published instead of drafted; without it every rule stays a draft |
| `--learned` | a directory holding the learned-rule store; its rules are matched with the shipped ones, and a published draft is added to it |
| `--confidence` | a table written by `triage calibrate`; it replaces the starting confidence of each path it names |
| `--author` | the reporter's login; only a hash of it is stored |
| `--date` | the day the report was read, written into the draft rule's provenance; a rule is never published without it |

```sh
drift triage fixtures/triage/s03-python-distutils.md
```

```
Triage of issue text e55da05dc5b3

Facts (6)
  os.name                linux                                             failing  0.855  section      7:1
  os.release.ID          debian                                            failing  0.950  section      7:1
  os.release.VERSION_ID  12                                                failing  0.950  section      7:8
  run:signature          ModuleNotFoundError: No module named 'distutils'  failing  0.900  tool-output  16:1
  tool.python.version    3.12.1                                            failing  0.985  section      3:1
  tool.python.version    3.11.7                                            passing  0.700  pattern      11:44

Rules (2)
  python-3-12-distutils-removed              match    plausible  0.700  high
  python-3-12-asynchat-asyncore-imp-removed  symptom  -          0.490  high
  python-3-12-distutils-removed: facts tool.python.version; text "ModuleNotFoundError"
  python-3-12-asynchat-asyncore-imp-removed: facts tool.python.version; text "ModuleNotFoundError"

Claims (0)

Draft rule: none
```

The input is [`fixtures/triage/s03-python-distutils.md`](./fixtures/triage/s03-python-distutils.md), an invented issue form. Each fact names the attribute key, the value, which environment it describes (`failing` or `passing`), a confidence, how it was found and the line and column of the value. `3.12.1` reached 0.985 because the form field and the sentence below it agree.

### Facts and Confidence

A confidence is a number from 0 to 1 printed with three decimals. The starting value depends on how the value was found:

| Path | Where the value comes from | Start |
| --- | --- | --- |
| `section` | the first line under a heading or bold label that names the field, as an issue form renders it | 0.950 |
| `tool-output` | a verbatim tool line: `openjdk version "21.0.2"`, `Python 3.12.1`, a `uname -a` line, a Dockerfile `FROM`, the line after a `$ node -v` echo, an error line in a code block | 0.900 |
| `key-value` | `Key: value`, a table row, `NAME=value`, a Gradle or npm dependency line | 0.490 |
| `pattern` | a known name followed by a version in running text | 0.700, or 0.800 under an environment heading |

Three adjustments follow, in this order:

1. Spans that give the same value agree. Independent confidences combine as the chance that at least one is right, capped at 0.990. `17` and `17.0.9` agree, and the longer value is kept.
2. A different value for the same key and side is a conflict. The stronger value keeps 0.6 of its confidence and the others are listed as alternatives.
3. A hedge on the line (`maybe`, `probably`, `i think`, `not sure`) scales the start by 0.7. A distribution name that implies Linux gives `os.name` at 0.9 of the distribution's confidence.

The side comes from the words around the value. The nearest verdict word before it in the clause decides: `works on Node 18` is `passing`, `fails on Node 20` is `failing`. A verdict word right after it wins (`Node 18 works`). `upgraded from Node 18 to Node 20` reads 18 as `passing`. With no verdict word, a value is `failing`.

The keys are the ones the rules read: `os.name`, `os.version`, `os.arch`, `os.release.ID`, `os.release.VERSION_ID`, `tool.<name>.version` for Java, Kotlin, Gradle, Node, npm, Python, PHP, Go, Rust, Git, .NET, Docker, Composer, OpenSSL, Ruby, Podman, curl, Xcode, CocoaPods, Dart, workerd, pip, `tool.cc.*` and `tool.libc.*`, `project.gradle.kotlin.version`, `project.gradle.agp.version`, `project.<ecosystem>.<package>` for dependencies pasted from a build file, an install command or pip output, `browser.name`, `browser.version`, `ci.provider`, `ci.runner.label`, `runtime.platform`, `runtime.container.image`, `runtime.jvm.options`, `env.LANG`, `env.TZ`, and `run:signature` for the first error line, normalized the way a failing step is. The next section lists the keys that come from logs and configuration.

### Facts From Logs and Configuration

Some text carries a fact in a shape that only that kind of output has. Each reader below needs that shape; a bare word never produces a fact.

| Key | Read from |
| --- | --- |
| `tool.<name>.version` for any `<name>` | a name the text itself shows as a tool: the first word of a prompt line (`$ skopeo --version`), a package in an `apt`, `apk`, `dnf`, `yum`, `brew`, `choco`, `winget`, `scoop` or `snap` install line, or a bare `name --version` line. A mention of that name then needs a dotted version or the word `version` before it, and four numbers joined by dots read as an address |
| `tool.<name>.required` | a requirement on a line that names the tool: `requires PHP >= 8.1.0`, `Expected version ">=18"`, `3.8.10 not in '>=3.9'`, `Minimum supported Gradle version is 8.5`. The operator stays in the value, because a minimum is not the version of any environment |
| `tool.<name>.version` (failing) | the version found on that same line: `You are running 7.4.33`, `Got "16.20.0"`, `Current version is 8.2` |
| `project.python.<package>` | `Successfully installed name-1.2.3`, a wheel or sdist file name, `Requirement already satisfied`, `pip install name==1.2.3` and a requirements line |
| `project.python.pyqt`, `pyside`, `tool.qt.version`, `tool.gtk.version` | a binding name with one digit, such as `PyQt6`; the digit is the value |
| `runtime.container.image` | a registry path (`ghcr.io/acme/tool:1.4`), `docker://name:tag` or the image after `docker pull`, `run` or `create`; a digest is dropped |
| `env.<NAME>` | an `environment:` block, `-e NAME=value`, `ENV`, `export` and `set`; a name with `key`, `token`, `secret`, `pass`, `auth`, `cred`, `private`, `salt`, `hash` or `sign` in it is never read |
| `runtime.platform`, `tool.<platform>.version` | a server platform (Paper, Spigot, Purpur, Velocity, Waterfall and the like) in a `Server Version` style field, a `running <name> version` log line, `<name> version <v>` or a jar name such as `spigot-api-1.19-R0.1-SNAPSHOT.jar` |
| `tool.docker.version`, `kernel.release` | the `Client:` and `Server:` blocks of `docker version` and the `Server Version:` and `Kernel Version:` lines of `docker info` |
| `limits.disk-quota`, `limits.nofile` | `Disk quota exceeded` and `Too many open files`; the value is `exceeded` |

A phrase such as `does not have this problem` after a value puts it on the passing side.

`tool-output`, `key-value` and `pattern` were set from a seeded half of the triage corpus (see Corpus below). `section` keeps 0.950: the corpus takes its truth from form sections, so that path cannot be scored against it. On the other half of the corpus the facts on form-labelled keys score a precision of 0.783 and a Brier score of 0.1486 over 1,376 facts. `key-value` is the weakest path: 9 held-out facts, precision 0.444.

### Rules

A rule applies when its detect tree holds on the stated facts. A tree that reads `a` and `b` sides is evaluated with the passing facts as `a` and the failing facts as `b`.

| Grade | Meaning |
| --- | --- |
| `match` | every condition holds on facts the issue states |
| `failing-side` | every failing-side condition holds and the passing side is not stated; reported only when the issue also carries the rule's symptom text, since almost every rule about a newer version would apply otherwise |
| `symptom` | the failing-side conditions that can be checked hold, some are not stated, and the issue carries the rule's symptom text |

Only a `match` has a tier: `known` when every symptom of the rule is verified and `plausible` otherwise, the ranker's own policy. The other grades lack the two-sided difference a tier describes, so none is shown. The confidence of a rule is the weakest fact it rests on, times 0.7 for a `symptom`. It is not the chance that the rule is the cause. Rules with status `pending-scanner` are matched too, since the reporter can state attributes no scanner emits yet.

Symptom text comes from the rule's `symptoms`: quoted strings, `ERR_CODE` names and `...Error` names.

### Claims

The claim check is experimental. On 67 labelled maintainer-correction threads it flags none, and on the 222,707-issue corpus it fires once, on a sentence that is not a claim (details in `TECHNICAL_REPORT.md`). Treat a flagged sentence as a lead to read, not as a finding.

A claim check flags a sentence that contradicts something Drift knows. Each claim names its source: a rule, or two columns of an Atlas probe. A test checks that every rule exists and that the two Atlas columns print different results.

```sh
drift triage fixtures/triage/c06-distutils-still-available.md
```

```
Triage of issue text ce4d64e01c01

Facts (1)
  tool.python.version  3.12.1  failing  0.700  pattern  3:9

Rules (0)

Claims (1)
  line 5 (0.490): distutils is still available on this build.
    says: distutils is part of the standard library
    Drift: Python 3.12 deletes the distutils package from the standard library and venv no longer pre-installs setuptools, which still provides distutils when installed.
    source: rule:python-3-12-distutils-removed

Draft rule: none
```

| Claim | Contradicted by |
| --- | --- |
| `hash-order-same` | Atlas `kotlin.collections.hash-order`: JVM and Linux differ |
| `double-tostring-same` | Atlas `kotlin.double.tostring-boundaries`: JVM and Wasm differ |
| `regex-same` | Atlas `kotlin.regex.syntax-support`: JVM and Wasm differ |
| `cloudflare-jvm-like-jvm` | Atlas `kotlin.collections.hash-order`: JVM and `cloudflare-jvm` differ |
| `nashorn-available` | rule `jdk-15-nashorn-removed`, with Java 15 or newer stated |
| `cms-collector-works` | rule `jdk-14-cms-collector-removed`, with Java 14 or newer stated |
| `distutils-available` | rule `python-3-12-distutils-removed`, with Python 3.12 or newer stated |
| `jaxb-in-jdk` | rule `jdk-11-java-ee-modules-removed`, with Java 11 or newer stated |
| `security-manager-enable` | rule `jdk-24-security-manager-disabled`, with Java 24 or newer stated |

A claim fires on a sentence outside code blocks that has a phrase from each of its word groups and no negation, question mark or sign that the sentence is not an assertion (`deprecat`, `slated`, `instead`, `alternativ`, `would`, `please`, `overflow`, `non-`). A claim that depends on the environment stays silent when the fact is missing. The confidence is 0.700, times the weakest fact the claim depends on.

### Draft Rules

A draft needs a symptom signature, one key stated on both sides, and no rule that already holds on that key. For a version key (`tool.<name>.version` or any `project.*` key) the passing version must be below the failing one. For `runtime.container.image`, `ci.runner.label` and `runtime.platform` the two values must differ, and the rule compares them for equality. Its detect tree is what each side stated plus the signature:

```sh
drift triage fixtures/triage/f11-node-typeerror.md --publish-threshold 0.5 --date 2026-10-13
```

```
Triage of issue text 3ba2ef819767

Facts (3)
  run:signature      TypeError: path must be a string  failing  0.700  pattern  1:63
  tool.node.version  20.11.1                           failing  0.700  pattern  1:49
  tool.node.version  18.19.0                           passing  0.700  pattern  1:19

Rules (0)

Claims (0)

Draft rule triage-tool-node-version-3ba2ef81: publish (confidence 0.700)
schema: 1
id: triage-tool-node-version-3ba2ef81
title: 'Reported: TypeError: path must be a string after tool.node.version 18.19.0 to 20.11.1'
applies:
  - runtime
difference: 'the failing side has tool.node.version 20.11.1 or newer and the passing side has 18.19.0 or older'
symptoms:
  - text: 'TypeError: path must be a string'
    basis: inferred
mechanism: 'Not established: drafted from one issue report that nobody has reproduced.'
detect:
  all:
    - a: tool.node.version
      ver: '<=18.19.0'
    - b: tool.node.version
      ver: '>=20.11.1'
    - b: run:signature
      has: 'TypeError: path must be a string'
...
status: active
```

The YAML has the schema of `rules/`. A draft carries `status: draft`, which is not a rule status, so it cannot load into the catalog by accident. `kind: report` marks the source as an unreproduced report, and the mechanism says so. The confidence of a draft is the weakest of the two versions and the signature.

A draft is published when a threshold is set, its confidence reaches it, `--date` is given and the rule passes `RuleCompiler.validate` and its own fixtures. A published rule has status `active` when an active rule already reads the key and `pending-scanner` otherwise. Without all four it stays a draft, and `notes` says which condition failed.

### Auto-Publish

With `--publish-threshold` and `--learned <dir>`, a published draft is added to `<dir>/learned.json`; below the threshold the draft is only printed, and without `--publish-threshold` every draft stays a draft. Nothing is written into `rules/`. The store is one canonical JSON file, `{"schema":1,"rules":[...]}`, in the schema of `rules/`. Every read runs `RuleCompiler` over each rule and refuses a rule whose id a shipped rule already uses. Rules are kept sorted by id and a rule with the same id is replaced, so publishing the same issue twice leaves the file as it was; the id carries the hash of the redacted issue text. The command reports the addition on standard error.

```sh
drift triage issue.md --publish-threshold 0.7 --date 2026-10-13 --learned .drift/learned
drift diagnose green.json red.json --learned .drift/learned
drift upgrade-check capsule.json --to node=20 --learned .drift/learned
```

`diagnose`, `upgrade-check` and `triage` read the store from `--learned` and rank or match its rules with the shipped ones. A missing store adds nothing; a store that does not compile is an error. The Action's `publish-threshold` input only changes the decision shown in the comment, since the Action passes no store.

### Redaction

Secrets are masked before anything is stored or printed, with the same redactor as `capture`. Other users' home directories become `.../user`. An `@login` mention becomes `@` and a hash, `u` followed by ten hex digits of the SHA-256 of the lowercase login; `--author` is hashed the same way. A mention inside a code block stays, since it is usually an annotation. The result keeps the SHA-256 of the redacted text and the line and column of each value, never the text.

### CI Logs and Version Banners

`Triage.clean` turns a GitHub Actions log into one fenced block: when at least three lines and at least half of the non-blank lines carry a `job<TAB>step<TAB>timestamp` or `timestamp` prefix, the prefixes and colour codes are removed and the lines are wrapped in a code fence (spans refer to that text; any other text is untouched). The extractors then read the lines as output: a runtime banner is the `tool.X.version` fact (a Ruby `p107` patch level is skipped, so `ruby 3.0.2p107` is 3.0.2), a setup input such as `python-version: 3.9` is a stated version at the confidence of any stated version (700), a banner printed by the log (990) beats it, and the last error line becomes `run:signature`. `Asks.runtimes` also reads a `SyntaxError` under a Python `File "...", line N` frame as Python. In the plain answer the tool versions come before settings and the system, and a report that states a tool version no rule explains gets that version as the failing-side answer of its version check: the session starts with that check answered and the next step is the works-side step. No fact key and no rule was added for this.

### Not Environment

When the text has a program-side signal and no environment fact on the failing side, the result adds a `notEnvironment` object: `signal` (`frame`, `mismatch`, `regression`, `crash`, `logic`, `null-deref`, `bounds`, `memory`, `not-shown`, `located` or `clean-run`), `line` (1-based), `frame` (the project's `file:line` for a frame, otherwise `null`) and `text` (the line, clipped to 160 characters). `drift triage` prints it as a line such as `Not environment (frame) at line 3: at test.js:3:8`. The object is absent when a rule matches or a failing-side fact is stated, so output for other reports does not change. `frame`, `mismatch` and `regression` follow the stated-fact rule. `crash` is an exception: a line with the word crash, in a text that also shows an exception, a traceback, a segmentation fault, an access violation or a stack trace, counts when the only facts stated on the failing side are `os.*` ones, since every bug template asks for the system. It ranks below the other three. The six program statements (`logic`, `null-deref`, `bounds`, `memory`, `not-shown`, `located`) are the other exception. They are phrase lists in `core/.../triage/ProgramFault.kt` and apply when the text tells no story of change (no passing-side fact, no `.required` fact, no limit that was hit, no "works on", "worked before", "since upgrading" or similar phrase) and no rule or draft rule matches. They may stand beside tool versions and the system. A Markdown heading or a bold label does not count as a mismatch. Frames in `node_modules`, `site-packages`, the JDK and `/usr` do not count. `clean-run` comes only from `drift why --reply`: a clean run printed the same failure as the report, `line` is the report line that holds it and `text` is that line. The rule is in [docs/why.md](./docs/why.md#when-it-is-not-the-environment).

`drift why` also withholds the reading when an error phrase names a runtime, module, tool, limit or locale. `drift triage` has no such phrase table, so a report that names a missing runtime feature and gives no version can carry the reading there.

### Ask

When no rule matches, no draft follows and no program-side reading applies, `drift triage` adds an `ask` list (`--json`) and an `Ask (n)` section (text). Each item has `family` (the attribute family the check reads: `tool.node`, `limits.nofile`, `os.release`, `os.version`, `kernel.release`, `env.TZ`, `env.LANG`, `capture` or `clean-run`), `command` and `piped`, which is the command with its output fed to `drift why --reply`. There are at most three, most useful first: a limit the text says was hit, the tool the text names most (a name inside a path, a URL or a package name does not count, and `go` counts only as a Go command), the whole-machine capture when the text says it works elsewhere, other tools, the system, then time zone and locale. A family the text already states on the failing side is never asked for. A runtime the text names only through its error vocabulary is asked for like a named tool (`tool.python`, `tool.node`, `tool.php`, `tool.java`, `tool.go`, `tool.rust`, `tool.ruby`, `tool.dotnet`; the phrases are in `Asks.runtimes`: a Python exception name or traceback header, a Node `Cannot find module`, `ERR_` code or `is not a function` with an `at` frame or `node:`, a PHP `Fatal error: Uncaught` or `Call to undefined function`, a JVM `java.lang.` name, `Exception in thread` or `UnsupportedClassVersionError`, a Go `panic:` with `goroutine` or `cannot find package`, a rustc `error[E` or `thread 'main' panicked`, a Ruby `NoMethodError` or `LoadError`, a .NET `Unhandled exception. System.`). A path or a generic word never counts, and nothing is asked for a tool whose version the text states. `bash: git: command not found`, `command not found: git` and `No such file or directory: 'git'` ask `command -v git` (family `tool.git`, reply fact `tool.git.installed` = `yes` or `no`; an empty answer is `no`); a name with a dot or a slash is a file and is not asked for. `TriageOptions(worksElsewhere = true)`, which `drift why --error TEXT` sets when it falls back to reading the error text, makes no program-side reading from the text alone and adds the capture ask; the session file then carries `"elsewhere": true` so the replies read the report the same way. The `clean-run` ask comes beside those three, never instead of one: it is first when the text states no failing-side fact and last otherwise. It needs a failure signature in the text (an error line that `SymptomExtractor` reads, or the `run:signature` fact), a command and a toolchain with an official image in `ImageTable`. A command is a `$`, `>` or `%` line in a code block (`>` and `%` only when the program is known), the one command of a code block that precedes the block with the error, or an inline code span after a run verb (run, ran, running, executing, typing, `command:`) whose program is one the ask table or `ToolRegistry` knows. A report with a command and no signature gets the `error-text` ask instead (`COMMAND 2>&1 | drift why --reply`); the reply is added to the report. The printed command mounts the working directory read-only at `/src`, copies it into a tmpfs `/w`, drops all capabilities and new privileges, runs as the user, limits processes (256) and memory (1 GB) and adds `--network none` unless the report shows network use or the command is a package or build manager; the command is the only text from the report and is printed inside single quotes, and no ask is printed for a command that is empty, over 400 characters, holds a control or non-ASCII character, a backtick, `$(` or a masked secret, or ends in a backslash. The image tag is a published tag from `ImageTags`: the version the text states for that toolchain (the newest published tag with that prefix, or the nearest one of the same major), or the newest published tag when it states none. Drift never runs the command. The reply is compared with the failure in the report: the same failure (after paths, numbers and times are normalised) ends in the not-environment reading, no error in the output means the command works on a clean machine and the next ask is the capture, and another error keeps the ask open with one sentence on what differed. The session file keeps only the fact `run:clean` with `same` or `works`. Every command comes from a table in `core/.../triage/Asks.kt`; the tool commands are the ones `drift capture --tools` runs, and a test in `scan` keeps the two in step. The key is absent when there is nothing to ask, so other output does not change.

`drift triage` takes no replies. `drift why` writes the session and reads them: see [What To Run Next](./docs/why.md#what-to-run-next). The session file `.drift/why/session.json` is canonical JSON with `schema` 1, `report` (the redacted text) and `asks`, each with `family`, `argv`, `command`, `why`, `reply` (`null` while open, else `{facts: [{key, value}]}`, the facts of the machine where it fails) and, once `drift why --reply --works` has run, `works` (the same shape, from the machine where it works; absent otherwise, so a session without it reads as before). `drift why --reply --works` takes standard input as the answer of a check from the machine where the program works. With both sides it builds two capsules from the facts and runs the comparison `--there` uses (same ranking, same error-text pointers and wording, "where it works" and "where it fails" as the sides), so a differing fact is named; an equal fact is reported as not differing and the next check follows. After a first reply that no rule answers, the next step is the works-side step, before the other checks and the capture. The session lives in the folder where the first `drift why` ran, so the step has two forms: run the check where it works, copy the output and paste it where the session is (`drift why --reply --works`, then Ctrl-D, or Ctrl-Z and Enter on Windows), or pipe it over ssh (`ssh USER@HOST CHECK | drift why --reply --works`); in `plain.ask` each check that can run on both machines carries `piped` (the failing-side pipe), `pipedWorks` (the works-side pipe) and `pasteWorks` (the paste sentence). `--reply` or `--works` in a folder with no session is not a dead end: it reads the answer (it must parse as the output of one of the checks in the ask table, else exit 1 and a plain sentence), redacts it, exits 0 and prints "There is no session here, so I cannot compare yet. The session is in the folder where you ran drift why --error. Run this there:" and one command, `printf '%s\n' 'LINE' ... | drift why --reply [--works]`, with the redacted reply as single-quoted arguments (control characters dropped, non-ASCII replaced by `?`, `--session FILE` only when it was given; over 2 KiB it says to copy the output into a file and use `< FILE`). Run on the machine that holds the session, that command gives the comparison. The `printenv TZ` reply now has to look like a zone name (`Europe/Paris`, `UTC`, `EST5EDT`) so that an arbitrary line is not taken for it. Drift does not detect a terminal, so it prints no prompt before it reads. When the comparison names a difference and other differences exist that the error text does not point at, an evidence line gives their number and names the ones the text points at.

### Fixtures and Scores

[`fixtures/triage/`](./fixtures/triage/) holds 78 issue bodies, each with an expectation file. The first 52 are strict forms (10), forms with blank issues (8), free text (12), sparse or wrong beginner reports (10) and mixed phrasing (12); 18 cover false positives of the key-value path and claim wording, and 8 cover the readers in Facts From Logs and Configuration, one of them with only lookalikes. The bodies are invented for this repository; none is copied from an issue. The expectation lists every fact the text states, the rules and claims it should trigger and whether a draft follows. `TriageTest` scores the extractor on them and prints the result:

| | |
| --- | --- |
| facts extracted | 207 |
| precision, recall | 0.990, 0.980 (205 true positives, 2 false positives, 4 false negatives) |
| Brier score | 0.0848 |

The misses are the intended hard cases: a form field that still holds the old version (`b07-form-wrong-version`), a fix reported as `upgraded to node 20 ... went away` (`h05-form-then-fix`), `Go 1.22 tutorials` read as an environment (`h11-lookalike-prose`) and `on my mac` not read as macOS (`b01-form-no-response`). The test fails when precision drops below 0.95, recall below 0.93 or the Brier score above 0.12, and when any path's mean confidence exceeds its accuracy by more than 0.05. The scores are an upper bound for real issues.

### Limits

A version with no name next to it is not read (`since upgrading to 3.13.0`), except the version found on a line that states a requirement for one tool. One value is kept per key and side. A form field that holds the wrong value is read as written, with 0.950. The text of a pasted log is read only for its first error line. Claims cover nine statements. The draft rule generalizes from one report and is a starting point for a person, not a finding. `-` was not run on Windows.

### Corpus

`triage/corpus.yml` lists the trackers the extractor is measured on: 43 repositories on GitHub and GitLab, 15 large and 28 small (50 to 1,000 stars), with their issue template policy. `./gradlew :tools:buildCorpus` pages every repo newest first with `gh` or `glab` (a 100-issue page per call, resumable, at most 25,000 issues per repo, none created after the manifest cutoff), redacts titles and bodies with the log sanitizer, replaces every login with `u` and twelve hex digits of an HMAC-SHA256 keyed with the manifest salt, sorts by repo and issue number, and writes one JSON file in `tools/build/corpus` and its `zstd -19` copy. The same input gives the same bytes. The file is not in the repository: the manifest pins its SHA-256 and size under `asset:`, and `./gradlew :tools:downloadCorpus` downloads it from that URL and refuses a file that does not match.

`./gradlew :tools:evalTriage` runs `drift triage` over the corpus. It is not part of `build`. A form body (`### Label` sections from a repo whose template is a form) states its environment under labelled headings, and the facts read from those sections are the truth. The sections that gave a fact are cut out and the extractor runs again on the rest, so the tool-output, key-value and pattern paths are scored against what the reporter typed into the form. Only failing-side facts on keys the form labelled are scored, and `8.1` agrees with `8.1.0`. Recall is the share of form values found again elsewhere in the body. Issues are split by SHA-256 of a seed, repo and number; bases are set on the dev half and reported on the held-out half. Bodies from repos without forms have no truth and only report coverage. `-Pdrift.corpus=<file>` uses a local file instead of the download.

### Calibrating on Your Own Issues

`drift triage calibrate` repeats the dev-half recalibration on a corpus of your own. Build the corpus with `buildCorpus` from your manifest, then:

```sh
drift triage calibrate --corpus corpus.json.zst --out table.json
drift triage issue.md --confidence table.json
```

Issues are split by SHA-256 of `--seed` (default `drift-triage-split-v1`), repo and number. Form bodies (`### Label` sections) give the truth as described above. On the dev half each path's base is scaled by its accuracy over its mean confidence, rounded to 10 and kept between 100 and 990; a path with fewer than `--min-facts` dev facts (default 100) keeps its default. The held-out half is scored twice, with the default bases and with the new ones, and the command prints both Brier scores and writes them into the table next to the bases. The same corpus, seed and build give the same table. `.json.zst` is read by running your `zstd -dc`; the command fails and says so when `zstd` is not on the path, and a plain `.json` needs no program. The whole file is read into memory, so keep team corpora well below the size of the public one. A body that does not start with `### ` is not scored, whatever its repository.

The corpus used for the numbers above holds 222,707 issues. Held-out results after recalibration and the extractor fixes, over 1,376 scored facts (`./gradlew :tools:evalTriage`, rerun on the final tree): precision 0.783 and Brier 0.1486 overall, 0.854 and 0.1301 in repos with blank issues allowed next to forms, 0.741 and 0.1595 in repos with strict forms. A fact was found in 75% of the issues of large strict-form repos, 63% of large form repos with blank issues, 60% and 40% of large markdown-template repos (strict, blank), 36% and 49% of small form repos, and 21% of small repos with no template.

Small repositories are worse on every measure that has a number: a fact is found in 21% to 49% of their issues against 40% to 75% in large repositories, and 3 of the 9 scored small facts are right. The scored sample is large trackers: PHP, Node, .NET and Python account for most of it.

## Install and Uninstall

`drift install` copies the executable you are running into a directory and records every change in a receipt. `drift uninstall` reads the receipt and reverses it. Only a native build can do this. From the JVM build, `drift install` prints `error: the JVM distribution cannot install itself; install the native executable` and exits 2.

| Flag | Effect |
| --- | --- |
| `--user` | install for the current user (the default) |
| `--global` | install for every user; never uses `sudo` |
| `--dir PATH` | install into this absolute directory |
| `--no-modify-path` | leave shell profiles and the registry alone (same as `DRIFT_NO_MODIFY_PATH=1`) |
| `--dry-run` | print every change without making any |

`uninstall` takes `--dry-run` only.

**Dry run.** `--dry-run` prints `dry run: nothing is changed`, then each action the real run would take, and changes nothing. This example ran with a scratch home that holds an empty `.profile` and a `.zshenv` with one line, and with `SHELL=/bin/zsh`:

```sh
drift install --dry-run
```

```
dry run: nothing is changed
scope: user
create directory /home/you/.local
create directory /home/you/.local/bin
write /home/you/.local/bin/drift (mode 0755, 9866776 bytes)
create directory /home/you/.config
create directory /home/you/.config/drift
append to /home/you/.profile:
  # >>> drift >>>
  case ":${PATH}:" in
  	*":$HOME/.local/bin:"*) ;;
  	*) export PATH="$HOME/.local/bin:$PATH" ;;
  esac
  # <<< drift <<<
append to /home/you/.zshenv:
  # >>> drift >>>
  case ":${PATH}:" in
  	*":$HOME/.local/bin:"*) ;;
  	*) export PATH="$HOME/.local/bin:$PATH" ;;
  esac
  # <<< drift <<<
write receipt /home/you/.config/drift/install-receipt.json
restart your shell or run: . "/home/you/.profile"
```

The block is a POSIX `case` guard, so sourcing the file twice leaves one entry on `PATH`. The real run prints the same lines, with `installing drift 1.0.0` first and `installed drift 1.0.0 to <path>` last. Two dry runs print the same bytes.

**Scope and directory.** The default scope is `--user`, unless the process is root or elevated, and then it is `--global`. The user directory is `$XDG_BIN_HOME` when that is an absolute path, else `~/.local/bin`; on Windows it is `%LOCALAPPDATA%\Programs\Drift`. The global directory is `/usr/local/bin`, or `%ProgramFiles%\Drift` on Windows. `--global` never runs `sudo`. When the directory is not writable it exits 1 and prints the command to run yourself, such as `sudo "<path>/drift" install --global`; that message is quoted from the installer's test run in a Debian container, because `/usr/local/bin` is writable on the Mac used for this guide. A relative `--dir` is refused.

**Path edits.** Nothing is edited when the target directory is on `PATH` already. Otherwise, for a user install on Unix:

- every existing file among `~/.profile`, `~/.bash_profile` and `~/.bashrc` gets the marked block;
- zsh gets `$ZDOTDIR/.zshenv` if it exists, else `.zprofile` if it exists, else a new `.zshenv` when `SHELL` is zsh;
- fish gets `~/.config/fish/conf.d/drift.fish` when `~/.config/fish` exists or `SHELL` is fish;
- `~/.profile` is created when none of those apply.

A global install edits no profile and says that the directory is not on `PATH`. On Windows the directory is appended to the `Path` value in `HKCU\Environment` (user) or the machine environment key (global), the value type is kept, and the change is broadcast. The Windows behavior comes from a recorded run under Wine, not from Windows. `--no-modify-path` skips every edit.

```sh
drift install | tail -3
sh -c '. ~/.profile; . ~/.profile; echo "$PATH" | tr : "\n"; drift --version'
```

```
write receipt /home/you/.config/drift/install-receipt.json
installed drift 1.0.0 to /home/you/.local/bin/drift
restart your shell or run: . "/home/you/.profile"
/home/you/.local/bin
/usr/bin
/bin
drift version 1.0.0 (macos)
```

Run the same command again and it changes nothing:

```sh
~/.local/bin/drift install
```

```
installing drift 1.0.0
scope: user
/home/you/.local/bin/drift is up to date
/home/you/.profile already has the drift block
/home/you/.zshenv already has the drift block
/home/you/.config/drift/install-receipt.json is up to date
installed drift 1.0.0 to /home/you/.local/bin/drift
restart your shell for the PATH change to apply
```

**Receipt.** `install-receipt.json` is canonical JSON in `$XDG_CONFIG_HOME/drift` (an absolute path) or `~/.config/drift`, and in `%LOCALAPPDATA%\Drift` on Windows. It holds the schema, the version, the scope, the install directory, the binary path, the directories the install created, leftovers, and every path edit with the exact block or registry entry. A reinstall into the same directory carries the old edits forward. A reinstall into a different directory, or with a different scope, is refused until you run `drift uninstall`.

**Uninstall.** `drift uninstall` reverses the edits in reverse order. A profile block is removed and the file is restored byte for byte, including the newline the install added. A profile that `install` created is deleted if it is empty afterwards. Then it removes the binary, the receipt and the directories the install created, if they are empty. It removes nothing else: a block you edited by hand is kept and reported.

```sh
drift uninstall --dry-run
drift uninstall
```

```
dry run: nothing is changed
remove the drift block from /home/you/.zshenv
remove the drift block from /home/you/.profile
remove /home/you/.local/bin/drift
remove /home/you/.config/drift/install-receipt.json
remove directory /home/you/.config/drift if it is empty
remove directory /home/you/.config if it is empty
remove directory /home/you/.local/bin if it is empty
remove directory /home/you/.local if it is empty
uninstalling drift 1.0.0
remove the drift block from /home/you/.zshenv
remove the drift block from /home/you/.profile
remove /home/you/.local/bin/drift
remove /home/you/.config/drift/install-receipt.json
remove directory /home/you/.config/drift
remove directory /home/you/.config
remove directory /home/you/.local/bin
remove directory /home/you/.local
uninstalled drift
```

Afterwards the home directory holds only what it held before. `.profile` is the empty file it was, and `.zshenv` holds its one line:

```sh
ls -A ~
wc -c < ~/.profile
cat ~/.zshenv
```

```
.profile
.zshenv
0
export FOO=1
```

A second `drift uninstall` finds no receipt, says so and exits 0:

```
no install receipt at /home/you/.config/drift/install-receipt.json; nothing removed
```

**Other directories and no path edit.** `--dir` installs somewhere else. With `--no-modify-path` or `DRIFT_NO_MODIFY_PATH=1`, no profile or registry value changes, and the receipt records no path edit.

```sh
drift install --dir /opt/tools --no-modify-path
drift uninstall
```

```
installing drift 1.0.0
scope: user
create directory /opt
create directory /opt/tools
write /opt/tools/drift (mode 0755, 9866776 bytes)
create directory /home/you/.config
create directory /home/you/.config/drift
PATH is not modified
write receipt /home/you/.config/drift/install-receipt.json
installed drift 1.0.0 to /opt/tools/drift
uninstalling drift 1.0.0
remove /opt/tools/drift
remove /home/you/.config/drift/install-receipt.json
remove directory /home/you/.config/drift
remove directory /home/you/.config
remove directory /opt/tools
remove directory /opt
uninstalled drift
```

```sh
DRIFT_NO_MODIFY_PATH=1 drift install
```

```
installing drift 1.0.0
scope: user
create directory /home/you/.local
create directory /home/you/.local/bin
write /home/you/.local/bin/drift (mode 0755, 9866776 bytes)
create directory /home/you/.config
create directory /home/you/.config/drift
PATH is not modified
write receipt /home/you/.config/drift/install-receipt.json
installed drift 1.0.0 to /home/you/.local/bin/drift
```

**Refusals.**

| Command | Output | Exit |
| --- | --- | --- |
| `drift install --user --global` | `error: --user and --global cannot be combined` | 2 |
| `drift install --dir relative/bin` | `error: --dir must be an absolute path: relative/bin` | 2 |
| `drift install --dir /ro/bin` on a read-only parent | `error: cannot write to /ro` | 1 |
| `drift install --dir /b` after an install into `/a` | `error: drift is already installed in /a (user); run drift uninstall first` | 1 |
| `drift install` or `drift uninstall` with a corrupt receipt | `error: cannot read the install receipt at <path> (expected key at 1); nothing changed` | 1 |
| `drift install --bogus` | `Error: no such option --bogus` | 1 |
| `drift uninstall` with no receipt | `no install receipt at <path>; nothing removed` | 0 |

A step that fails after the binary landed still writes the receipt, so `uninstall` can undo the part that happened. On Windows a running `drift.exe` cannot be overwritten or deleted, so the old file is renamed to `drift.old` and removed at the next install or, for an uninstall, by a detached helper after the process exits. That path comes from the recorded Wine run.

## Serve

`drift serve` starts a small web server for the Studio web build, so you can open Studio in a browser without a hosting step. It binds to the loopback interface only, answers `GET` and `HEAD`, and serves files from one directory. It does not need a network connection.

```sh
drift serve --dir studio --port 0
```

```
drift serve 1.0.0: serving /path/to/studio
http://localhost:56990/
http://127.0.0.1:56990/
http://[::1]:56990/
press Ctrl+C to stop
```

The port in the example is the one the operating system picked for `--port 0`. Without `--quiet` the server prints one line per request, `METHOD PATH STATUS BYTES`, and `stopped` after Ctrl+C.

Build the directory with `./gradlew :cli:stageStudio`. It runs `:studio:wasmJsBrowserDistribution` and copies the result, without source maps, to `cli/build/serve/studio` (about 12 MB). The `build/atlas-site` directory from [`atlasSite`](#atlas) serves the same way.

### Where the Studio Build Comes From

`--dir` names the directory, which must hold an `index.html`. Without it, `drift serve` looks for `studio` next to the executable and then for `../share/drift/studio`. If neither exists it exits with status 1 and lists both places:

```
error: no Studio web build found; pass --dir <dist> (build one with ./gradlew :studio:wasmJsBrowserDistribution)
searched: /path/bin/studio
searched: /path/share/drift/studio
```

A `--dir` that exists without an `index.html` gives `error: no Studio web build in nodir: index.html not found`, exit 1. The executable does not embed the build. Storing a gzip of the 12.4 MB build as base64 text grew a macOS test executable by 12.1 MB, and `drift` is 6.7 MB without it; that measurement is about embedding as a Kotlin string, not about every way to embed.

### Requests

The checks below ran against the macOS release executable.

```sh
curl -s -D - -o /dev/null http://localhost:56990/
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

Files with a content hash in the name are cached as immutable, and everything else is `no-cache`. The `.wasm` file answers with `Content-Type: application/wasm` and `Cache-Control: public, max-age=31536000, immutable`. The server sends no `Access-Control-*` header, even when the request carries an `Origin`, and no COOP or COEP header (Studio loads without cross-origin isolation).

| Request | Status |
| --- | --- |
| `GET /` and `GET /index.html` | 200 |
| `GET /some/route` with `Accept: text/html` | 200 (the `index.html` fallback, for navigation only) |
| `GET /missing.js` | 404 |
| `GET /etc/passwd` | 404 (the path is looked up inside the directory) |
| `GET /../../etc/passwd` and `GET /%2e%2e/etc/passwd` (`curl --path-as-is`) | 400 |
| `Host: evil.example` | 403 |
| `POST` with a body | 400 |
| `PUT`, and `OPTIONS` | 405 with `Allow: GET, HEAD` |

The rest of the path rules are in [Security](#security).

### HTTP and HTTPS

Browsers treat `http://localhost` and `http://127.0.0.1` as secure contexts, and Studio loads over both in Chromium, Firefox and WebKit (a headless Firefox without a GL context fails, which is Firefox, not `serve`). Plain HTTP is the default and needs no setup. The default port is 8080 for HTTP and 8443 for HTTPS. `--port 0` takes a free port and prints it, and a port outside 0 to 65535 gives `error: --port must be between 0 and 65535`.

HTTPS needs a certificate that your browser trusts. Give `drift serve` a PEM pair:

```sh
drift serve --dir studio --cert leaf.pem --key leaf-key.pem --port 0
```

```
drift serve 1.0.0: serving /path/to/studio
https://localhost:57034/
https://127.0.0.1:57034/
https://[::1]:57034/
```

`--tls` selects HTTPS and needs `--cert` and `--key` or `--mkcert`; `--cert` and `--key` imply it. The pair in these examples came from a scratch mkcert CA that was never installed in any trust store, so `curl` trusts it through `--cacert`:

```sh
curl -s --cacert ca.pem -o /dev/null -w '%{http_code} verify=%{ssl_verify_result}\n' https://localhost:57034/
curl -s -o /dev/null -w '%{http_code}\n' https://localhost:57034/
curl -s --cacert ca.pem --tls-max 1.1 -o /dev/null -w '%{http_code}\n' https://localhost:57034/
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:57034/
curl -s --cacert ca.pem -H 'Host: evil.com' -o /dev/null -w '%{http_code}\n' https://localhost:57034/
```

```
200 verify=0
000
000
000
403
```

The second line is `curl` without the CA (the certificate is untrusted), the third is a client limited to TLS 1.1 (TLS 1.2 is the minimum), and the fourth is plain HTTP to the TLS port. A `--key` file that other users can read prints `warning: leaf-key.pem is readable by other users; run: chmod 600 leaf-key.pem`. A missing file gives `error: cannot read nokey.pem`, exit 1.

The JVM build parses a PKCS#8 key (`BEGIN PRIVATE KEY`). The macOS and Linux executables open the system OpenSSL at run time (nothing is linked or bundled): on macOS Homebrew's `openssl@3` at `/opt/homebrew/opt/openssl@3` or `/usr/local/opt/openssl@3` (the system LibreSSL is not used), and on Linux `libssl.so.3`, then `libssl.so.1.1`. Without a library, `--tls`, `--cert` and `--mkcert` fail before listening and exit 1; they never fall back to HTTP. The missing-library error was covered by a test with a stand-in, and was not run without OpenSSL.

| Build | HTTPS | How |
| --- | --- | --- |
| JVM | yes | JDK `SSLServerSocket` |
| macOS and Linux executables | yes, when OpenSSL 3 or 1.1 is installed | `libssl` opened at run time |
| Windows executable | no | serve HTTP, or put a TLS proxy that you install in front |

On Windows, `--tls` and `--cert` print `this build cannot serve https: the Windows build has no TLS library` and exit 1. That message and the Windows HTTP serving come from a run under Wine, not from Windows.

### mkcert

`--mkcert` gets the certificate from [mkcert](https://github.com/FiloSottile/mkcert) instead of a pair you made. `drift serve` runs `mkcert -CAROOT` and continues only if that directory already holds a CA that the system trust store has accepted. It then writes a certificate for `localhost`, `127.0.0.1`, `::1` and every `--domain` into the drift configuration directory, with mode 0600 for the key and 0700 for the directories. It never runs `mkcert -install`, because that edits your system trust store. If mkcert is missing, has no CA, or reports its CA as not installed, `drift serve` says so, prints the commands to run and serves plain HTTP.

```sh
drift serve --dir studio --port 0 --quiet --mkcert
```

```
warning: the mkcert local CA is not installed in the system trust store; serving plain http instead
for https, run once (this edits your system trust store, so drift never runs it):
  mkcert -install
then start drift serve --mkcert again (mkcert: https://github.com/FiloSottile/mkcert)
drift serve 1.0.0: serving /path/to/studio
http://localhost:57067/
http://127.0.0.1:57067/
http://[::1]:57067/
press Ctrl+C to stop
```

That ran with `CAROOT` pointing at a scratch CA, so mkcert reported that the CA was not installed. With no `mkcert` on `PATH` the first line is `warning: mkcert was not found on PATH; serving plain http instead`. The path where the CA is installed was not run on this machine, because it needs `mkcert -install`; a test with a stand-in mkcert covers it. mkcert 1.4.4 exits without writing a certificate when `JAVA_HOME` points at a JDK that has no `lib/security/cacerts` (Homebrew's OpenJDK), so on Unix `drift serve` starts mkcert through `env -u JAVA_HOME`.

### Local Names

`--domain` adds names that `drift serve` answers to, for example `--domain drift.studio --domain drift.local --domain '*.drift.local'`. It cannot make a name resolve, because that changes your system. It checks each name with `getaddrinfo`, says where it points, and prints the exact command for you to run:

```sh
drift serve --dir studio --port 0 --quiet --domain drift.studio --domain '*.drift.local'
```

```
drift serve 1.0.0: serving /path/to/studio
http://localhost:57064/
http://127.0.0.1:57064/
http://[::1]:57064/
http://drift.studio:57064/ (resolves to 13.248.169.48, 76.223.54.146, which is not loopback)
http://*.drift.local:57064/ (wildcard; a hosts file cannot hold wildcards)
to make a name point here, run this yourself (drift never edits /etc/hosts):
  echo '127.0.0.1 drift.studio' | sudo tee -a /etc/hosts
for https on these names, run: mkcert -install, then
  mkcert -cert-file drift.pem -key-file drift-key.pem localhost 127.0.0.1 ::1 drift.studio "*.drift.local"
and start drift serve --cert drift.pem --key drift-key.pem
press Ctrl+C to stop
```

The addresses are what `drift.studio` resolved to on the day of the run. `.studio` is a real top-level domain, so `drift.studio` points at public addresses until you add the hosts line, and the line hides the public site on that machine. On Windows the line goes into `C:\Windows\System32\drivers\etc\hosts` from an elevated PowerShell. With a certificate that covers the names, the same flags work over HTTPS (a `curl --resolve drift.studio:57034:127.0.0.1 --cacert ca.pem` request returned 200 with `verify=0`).

`--mdns` advertises the first `.local` name while the server runs, with `dns-sd -P` on macOS or `avahi-publish` on Linux, so `drift.local` resolves to 127.0.0.1 with no hosts edit. Other machines on the network see a record that points at 127.0.0.1, which is their own loopback, for as long as the server runs. `drift serve` stops the helper on exit. Windows is refused with exit 2. Without a `.local` name the command prints `error: --mdns needs a --domain that ends in .local` and exits 2. The advertisement itself was not rerun for this guide, because it publishes a record to the local network. A recorded run on macOS is the source: `dns-sd -G v4 drift.local` returned `127.0.0.1`, `curl http://drift.local:18769/` returned 200, and after Ctrl+C the helper was gone and the name stopped resolving.

### Security

- It listens on `127.0.0.1` and `::1` and nothing else (`lsof` showed `127.0.0.1:56990` and `[::1]:56990`). `--unsafe-bind-all` listens on every interface, and any IP address in the Host header is accepted too:

  ```sh
  drift serve --dir studio --port 0 --quiet --unsafe-bind-all
  ```

  ```
  drift serve 1.0.0: serving /path/to/studio
  http://localhost:57160/
  http://0.0.0.0:57160/
  http://[::]:57160/
  warning: listening on every network interface; anyone who can reach this machine can read this build. Only loopback names, IP addresses and --domain names get an answer.
  press Ctrl+C to stop
  ```

- The Host header must be `localhost`, `127.0.0.1`, `[::1]` or a `--domain` name (ports are ignored). Anything else gets 403, which stops DNS rebinding.
- Only `GET` and `HEAD`. Other methods get 405 with `Allow: GET, HEAD`, and a request with a body gets 400.
- A path with `..`, a backslash, a colon, a control character, bad percent encoding or a trailing dot or space gets 400. Names that start with a dot get 404. Every path is resolved with symlinks followed and must stay inside the directory, so a symlink that leaves it gets 404. There is no directory listing.
- A page route that is not a file falls back to `index.html` only for a navigation: `Accept` includes `text/html`, `Sec-Fetch-Mode` is absent or `navigate`, and the last segment has no file extension.
- Responses carry no CORS headers, so another origin cannot read them, and carry `Cross-Origin-Resource-Policy: same-origin` and `X-Content-Type-Options: nosniff`.
- One request per connection. The request head is limited to 16 KiB (431), the request line to 8 KiB (414), headers to 64, the head to 10 seconds (408), an idle read to 5 seconds, and open connections to 128 (503). A 9000 character path returned 414 and a 20000 character header returned 431 here; the 408 and 503 paths come from the server's tests.
- Ctrl+C or SIGTERM stops accepting and waits up to 5 seconds for open requests. The native executables print `stopped` and exit 0 (run here with both signals). The JVM build also prints `stopped` and exits 130, the JVM's status for SIGINT; that is from a recorded `:cli:jvmRun` session.

Not covered: slow-loris variants beyond the head timeout, a symlink swapped after the path is resolved, and Windows alternate data streams beyond the refused colon.

### Options

| Option | Meaning |
| --- | --- |
| `--dir PATH` | directory with the Studio web build |
| `--port N` | port; 0 picks a free one (default 8080, or 8443 with TLS) |
| `--tls` | serve HTTPS; needs `--cert` and `--key`, or `--mkcert` |
| `--cert PEM`, `--key PEM` | certificate chain and private key |
| `--mkcert` | certificate from an existing mkcert CA; never installs it |
| `--domain NAME` | also answer to this name; repeatable; `*.name` allowed |
| `--mdns` | advertise the first `.local` name |
| `--open` | open the default browser |
| `--unsafe-bind-all` | listen on every interface |
| `--quiet` | no line per request |

`--open` was not run against a real browser for this guide; a test with a stand-in launcher covers it.

Exit status is 0 after a normal stop. It is 1 when no build is found, a file cannot be read, a port is busy (`error: cannot listen on port 57111: Address already in use; pass --port 0 to take a free port`), or the build cannot serve TLS, and 2 for a bad combination of options (`error: --tls needs --cert and --key, or --mkcert`, `error: --cert and --key go together`, `--mdns` without a `.local` name).

### Serve Limits

The server reads each file whole into memory for each request, so a few large concurrent downloads of the 8.6 MB WebAssembly file cost that much memory each. It does not support range requests or compression. The Windows executable has run under Wine and serves HTTP there; it has not been run on Windows. Safari was not tested; Chromium, Firefox and WebKit ran Studio through Playwright in a Linux container.

## Doctor

`drift doctor` checks the host that runs Drift and says what to fix. It makes no network calls, reads no secrets and prints no user name, home path or host name. Each probe that starts a process waits at most two seconds.

```sh
drift doctor [--json]
```

| Check | ok | warn | fail |
| --- | --- | --- | --- |
| `platform` | the OS and architecture are a release target: macOS arm64, Windows x64, Linux x64 or arm64 | any other OS or architecture, such as an Intel Mac | the OS is not recognised |
| `process` | a child process starts and exits with 0 | the probe timed out | the host cannot start a process, or the probe exits non-zero |
| `working directory` | `sh -c 'test -w .'` succeeds | not writable, or no `sh` to run the test | no fail state |
| `docker` | `docker version` gets an answer from the daemon | not on PATH, no answer in time, or the daemon is unreachable | no fail state |
| `git` | `git --version` exits 0 | not on PATH, no answer in time, or a non-zero exit | no fail state |

Text output prints each check as a status line, a cause and a fix. This run was on the lane container, which has no Docker:

```text
ok    platform
      cause: linux amd64 is a release target
      fix: none
ok    process
      cause: a child process starts and exits
      fix: none
ok    working directory
      cause: the working directory is writable
      fix: none
warn  docker
      cause: docker is not on PATH
      fix: install Docker; reproduce and solve need it
ok    git
      cause: git answers
      fix: none
```

`--json` prints the same checks as canonical JSON, with `failed` and `warned` counts:

```json
{"checks":[{"cause":"linux amd64 is a release target","fix":"none","name":"platform","status":"ok"},{"cause":"a child process starts and exits","fix":"none","name":"process","status":"ok"},{"cause":"the working directory is writable","fix":"none","name":"working directory","status":"ok"},{"cause":"docker is not on PATH","fix":"install Docker; reproduce and solve need it","name":"docker","status":"warn"},{"cause":"git answers","fix":"none","name":"git","status":"ok"}],"failed":0,"warned":1}
```

The command exits 0 when no check fails, and 1 when one does. Warnings do not change the exit code. The report prints in both cases.

Docker is a warning because only `reproduce` and `solve` need a reachable daemon. Git is a warning because it only supplies a version field to `triage` and `upgrade-check`. The working directory check runs `sh`, so on Windows it warns unless a POSIX shell is on `PATH`.

## Help and Completion

`drift --help` opens with one sentence on what Drift does, the three commands to start with (`ci`, `doctor`, `diagnose`) and the commands grouped by task, then lists every command. `drift COMMAND --help` ends with an `Examples:` block of one or two runnable lines that use files from `fixtures/`.

```sh
drift ci --help | tail -4
```

```text
Examples:
drift ci
drift ci --commit c775d8f --detail summary
```

`drift help TERM` prints the plain definition of capsule, diagnose, solve, certificate, verify, Atlas or probe, in any letter case. `drift help` with no term prints all seven. The definitions are the README Terms table, held in `core` as `Glossary`.

```sh
drift help capsule
```

```text
Capsule: A file that records one environment: the operating system, tool versions, environment variables (secret values hidden) and the results of small checks.
```

An unknown term lists the known ones on standard error and exits 1:

```text
unknown term "flux"; the terms are capsule, diagnose, solve, certificate, verify, atlas, probe
```

`drift completion bash|zsh|fish` prints a completion script on standard output. It comes from Clikt and covers commands, subcommands and options. Load it for the current shell, or save it where your shell reads completions:

```sh
source <(drift completion bash)
drift completion zsh > "${fpath[1]}/_drift"
drift completion fish > ~/.config/fish/completions/drift.fish
```

A usage error, such as a missing option, still exits 1, the same code as a failed command.

## Install Scripts

`install.sh` (POSIX `sh`) and `install.ps1` (PowerShell 5.1 and 7) download a release archive, verify its checksum, check that the executable runs and hand over to `drift install`. They live in the repository root. Both fail with `cannot download` until a release exists.

```sh
curl -fsSL https://raw.githubusercontent.com/gmitch215/Drift/master/install.sh | sh -s -- --dry-run
```

| Option | Effect |
| --- | --- |
| `--user`, `--global`, `--dir PATH`, `--no-modify-path`, `--dry-run` | passed to `drift install` unchanged |
| `-h`, `--help` | print the usage and exit 0 |

| Variable | Effect |
| --- | --- |
| `DRIFT_VERSION` | version to install, for example `1.0.0`; a leading `v` is stripped. The default is the latest release, read from the redirect of `/releases/latest`. Required when `DRIFT_INSTALL_BASE_URL` is set |
| `DRIFT_INSTALL_BASE_URL` | where assets live, laid out as `BASE/v<version>/<asset>`; the default is `https://github.com/gmitch215/Drift/releases/download`. A `file://` URL works |
| `DRIFT_NO_MODIFY_PATH=1` | same as `--no-modify-path` |

**What it does.** `install.sh` maps `uname` to an asset name (`macos-arm64`, `linux-x64` or `linux-arm64`; Rosetta counts as arm64), downloads `drift-<version>-<os>-<arch>.tar.gz` and its `.sha256` file with `curl` or `wget`, and compares the 64-hex digest with `sha256sum` or `shasum -a 256`. It extracts into a temporary directory, runs `drift --version`, runs `drift install` with your flags, and removes the directory on exit and on signals. It refuses a musl Linux (`ldd --version` or `/lib/ld-musl-*`), because the Linux executable is built for glibc. macOS on Intel has no asset. `install.ps1` takes `-User`, `-Global`, `-Dir`, `-NoModifyPath`, `-DryRun` and `-Help`, downloads `drift-<version>-windows-x64.zip`, checks it with `Get-FileHash`, and extracts it with `Expand-Archive`.

**An example that ran.** No release exists, so the example builds a release directory from the local executable with the layout the script expects and points the script at it:

```sh
mkdir -p release/v1.0.0 stage
cp "$(command -v drift)" stage/drift
cp LICENSE stage/
tar -czf release/v1.0.0/drift-1.0.0-macos-arm64.tar.gz -C stage drift LICENSE
(cd release/v1.0.0 && shasum -a 256 drift-1.0.0-macos-arm64.tar.gz > drift-1.0.0-macos-arm64.tar.gz.sha256)
DRIFT_INSTALL_BASE_URL=file://$PWD/release DRIFT_VERSION=1.0.0 sh install.sh --dry-run --no-modify-path
```

```
drift-install: downloading drift-1.0.0-macos-arm64.tar.gz
drift-install: sha256 verified
dry run: nothing is changed
scope: user
create directory /home/you/.local
create directory /home/you/.local/bin
write /home/you/.local/bin/drift (mode 0755, 9866776 bytes)
create directory /home/you/.config
create directory /home/you/.config/drift
PATH is not modified
write receipt /home/you/.config/drift/install-receipt.json
```

The first two lines go to standard error. Corrupt the archive and the script stops before it installs anything:

```sh
cp -R release release-bad
echo x >> release-bad/v1.0.0/drift-1.0.0-macos-arm64.tar.gz
DRIFT_INSTALL_BASE_URL=file://$PWD/release-bad DRIFT_VERSION=1.0.0 sh install.sh
```

```
drift-install: downloading drift-1.0.0-macos-arm64.tar.gz
drift-install: error: checksum mismatch for drift-1.0.0-macos-arm64.tar.gz (expected 97c66ddafc23715fce5e0d79f9f875f9a0976d2d955f87ec0dddcbdbeb4c1c57, got 6f4b578c65ee4a77a8f24db1d5a31a212a2c807f15a0e144a2daf2328c9675f1); nothing was installed
```

Exit codes: 0 on success; 1 for an unsupported OS or architecture, musl, a missing downloader or hash tool, a download failure, a checksum mismatch, a bad sidecar, an executable that does not run, or a failure of `drift install` (its exit code passes through); 2 for an unknown option, `--user` with `--global`, or `--dir` without a value.

| Case | Message | Exit |
| --- | --- | --- |
| archive does not match its digest | `drift-install: error: checksum mismatch for <asset> (expected ..., got ...); nothing was installed` | 1 |
| asset missing | `drift-install: error: cannot download file:///.../drift-1.0.0-macos-arm64.tar.gz` | 1 |
| base URL without a version | `drift-install: error: set DRIFT_VERSION when DRIFT_INSTALL_BASE_URL is set` | 1 |
| `--bogus` | `drift-install: error: unknown option: --bogus` | 2 |
| `--dir` without a value | `drift-install: error: --dir needs a value` | 2 |
| `--user --global` | `drift-install: error: --user and --global cannot be combined` | 2 |

What ran and what did not: the macOS runs above were run for this guide. The Debian 12 (non-root and root), Alpine (musl refused, exit 1: `the Linux executable is built for glibc and this system uses musl; no asset is available`) and `wget`-only runs come from the installer's recorded test log, and so does `install.ps1`: it parsed and passed PSScriptAnalyzer under PowerShell 7.4 on Linux, and a mocked run passed 16 of 16 checks. `install.ps1` has not run on Windows or under Windows PowerShell 5.1, the real `github.com` download and the `/releases/latest` lookup have not run, and the Linux arm64 and Rosetta branches have not run.

## Release Assets

The release workflow publishes these names. Nothing has been published yet, so they come from the workflow and from the packaging run that built them locally.

| Asset | Contents |
| --- | --- |
| `drift-1.0.0-macos-arm64.tar.gz`, `drift-1.0.0-linux-x64.tar.gz`, `drift-1.0.0-linux-arm64.tar.gz`, `drift-1.0.0-windows-x64.zip` | the CLI: `drift` (`drift.exe`), `LICENSE`, `README.txt` |
| `drift-1.0.0-macos-arm64.dmg`, `drift-1.0.0-macos-arm64.pkg` | Studio for macOS (unsigned) |
| `drift-1.0.0-windows-x64.msi`, `drift-1.0.0-windows-x64.exe` | Studio for Windows (configured, never built) |
| `drift-1.0.0-linux-x64.deb`, `drift-1.0.0-linux-x64.rpm`, `drift-1.0.0-linux-x64.AppImage` | Studio for Linux |
| `drift-1.0.0-android.apk` | Studio for Android, debug-signed |
| `<asset>.sha256` | the digest of one asset, in `sha256sum` format |
| `SHA256SUMS` | every asset, sorted by name |

Verify a download with the sidecar for one file or `SHA256SUMS` for all of them. `--ignore-missing` skips the assets you did not download; without it a listed file that is absent counts as a failure:

```sh
shasum -a 256 -c drift-1.0.0-macos-arm64.tar.gz.sha256
shasum -a 256 -c SHA256SUMS
shasum -a 256 -c --ignore-missing SHA256SUMS
```

```
drift-1.0.0-macos-arm64.tar.gz: OK
drift-1.0.0-macos-arm64.tar.gz: OK
shasum: drift-1.0.0-linux-x64.tar.gz: No such file or directory
drift-1.0.0-linux-x64.tar.gz: FAILED open or read
shasum: WARNING: 1 listed file could not be read
drift-1.0.0-macos-arm64.tar.gz: OK
```

The commands exit 0, 1 and 0. The second ran with a `SHA256SUMS` that lists one more asset than the directory holds. On Linux use `sha256sum -c`, and in PowerShell compare `(Get-FileHash <file>).Hash` with the sidecar (not run here). The Studio installers and the CLI archives are unsigned. Studio's `.deb` and `.rpm` are desktop packages: jpackage's install script needs the desktop directories (`/usr/share/applications`, `/usr/share/mime/packages`, `/etc/xdg/menus`), and `dpkg -i` or `dnf install` fails in a bare container without them.

## CI Integration

The repository root holds a GitHub composite action (`action.yml`) and `templates/drift.yml`, a GitLab CI/CD component. Both install the release archive for the runner, run the command or capture the job, compare a failing run with the last green baseline using `diagnose --against`, and write the ranked diagnosis to the job summary (GitHub) or the job log (GitLab). Neither needs a secret beyond the token the CI system already gives a job. Nothing has been published yet, so the `@v1.0.0` references below work once the release exists; the scripts were run locally and the GitHub and GitLab services were not.

### GitHub Action

```yaml
on:
  push:
    branches: [master]
  pull_request:
jobs:
  test:
    runs-on: ubuntu-24.04
    permissions:
      contents: read
      actions: read
    steps:
      - uses: actions/checkout@v6
      - uses: gmitch215/Drift@v1.0.0
        with:
          command: ./gradlew test
```

| Input | Default | Meaning |
| --- | --- | --- |
| `version` | `v1.0.0` | release tag to download; the asset is `drift-<version>-<os>-<arch>.tar.gz` (`.zip` on Windows) with its `.sha256`, verified before it is extracted |
| `mode` | `run` | `run` runs `command` and captures; `capture` only captures; `diagnose` captures and compares with the baseline; `triage` triages the issue body and comments on the issue (see [Issue Triage](#issue-triage)) |
| `publish-threshold` | empty | `triage` only; confidence from 0 to 1 at which a draft rule is published; empty keeps every draft |
| `slack-webhook` | empty | `triage` only; a Slack incoming webhook URL, passed from a secret; empty sends nothing |
| `command` | empty | shell command for `run`, started with `bash -c`; the step exits with its exit code |
| `baseline` | empty | a capsule path, or an artifact name; empty looks up `drift-baseline-<ref>-<label>` |
| `label` | job, OS and architecture | names the capsule and the artifacts; matrix jobs need different labels |
| `directory` | `drift-capsule` | where the capsule is written |
| `save-baseline` | `true` | upload the capsule of a green push run as the baseline |
| `retention-days` | `30` | days the artifacts are kept |

Outputs are `exit-code` and `capsule`. `mode: run` of a failing command uploads the capsule as `drift-capsule-<label>`, fetches the baseline, runs `diagnose --against` and still fails the step with the command's exit code. Use `mode: diagnose` in a later step with `if: failure()` when the failing step is not the action's:

```yaml
      - run: make
      - if: ${{ failure() }}
        uses: gmitch215/Drift@v1.0.0
        with:
          mode: diagnose
```

A green `run` on a `push` event uploads the capsule as `drift-baseline-<branch>-<label>`. A `capture` step saves a baseline the same way, so put it last in the job to save one only when the job passed. Fetching needs `actions: read` and `contents: read` for `GITHUB_TOKEN`; uploads use the runner's own credential. The summary of a failed run looks like this (from the script run locally with a green and a red `drift run` of the same command, the red one under `TZ=Asia/Tokyo`):

````
## Drift: test

The command exited with 3.

Compared with green:

```text
env.TZ differs most (tier difference); this ranks differences and does not confirm a cause.
```
````

Before the command starts, the action captures the environment with the variables that name the run itself removed (`GITHUB_*`, `ACTIONS_*`, `INPUT_*`, `STATE_*`, `RUNNER_TEMP`, `RUNNER_TRACKING_ID`, `GH_TOKEN`). Their values change on every run, so left in they outrank every real difference. The command still sees its normal environment. The capsule is not otherwise filtered, and the usual masking applies.

### Issue Triage

`mode: triage` runs `drift triage` on the body of an issue and posts one comment with the extracted facts, the matched rules, the claim checks and the draft rule decision. It runs only on `issues` events, needs `issues: write`, and does not capture the job, so it writes no capsule and no baseline. Nothing has been run on GitHub yet.

```yaml
on:
  issues:
    types: [opened, edited]
permissions:
  contents: read
  issues: write
jobs:
  triage:
    runs-on: ubuntu-24.04
    steps:
      - uses: gmitch215/Drift@v1.0.0
        with:
          mode: triage
          publish-threshold: '0.9'
          slack-webhook: ${{ secrets.SLACK_WEBHOOK_URL }}
```

The comment starts with the hidden marker `<!-- drift-triage -->`. An `edited` run replaces the body of that comment instead of adding another one. Only comments from a Bot account are matched, so a reader who pastes the marker into a comment does not get that comment edited. The report is indented as a code block, so Markdown in the issue text is shown and not rendered.

The issue body reaches the script through an environment variable and is then piped to `drift triage -`. It never becomes part of a shell command. Without `publish-threshold` every draft rule stays a draft. Slack receives one line with the counts and the draft decision, and only when the comment is created; an edit sends nothing. Leave `slack-webhook` empty to send nothing. Pass the URL from a secret so GitHub masks it in the log.

### Baseline Trust

An artifact name is not a credential, so the lookup does not trust it alone. The action lists successful runs of the `push` event on one branch (the current branch on a push, the base branch on a pull request, then the default branch), keeps runs whose head repository is this repository and whose `head_branch` is that branch, and takes the artifact from one of those runs only if its own `workflow_run` agrees. A pull request run never looks up its head branch, and it never uploads a baseline: only `push` runs do, and never when `github.event.pull_request.head.repo.full_name` differs from the repository. A fork can therefore not plant a `drift-baseline-master-...` artifact that a later run would pick up. A `baseline` input that is a path on disk is used as given.

The GitLab component asks for the artifacts of the latest successful pipeline of one branch of this project, using the `jobs/artifacts/:ref_name/download` endpoint. Merge request pipelines have the ref `refs/merge-requests/:iid/head`, so their artifacts are never returned for a branch name, and a fork runs its pipelines in the fork's own project. The component asks for the target branch in a merge request pipeline (never the source branch), then the default branch; outside a merge request it asks for the current branch, then the default branch. The job token travels in the `JOB-TOKEN` header, not in the URL.

### Artifact Access

A capsule is an artifact, and anyone who can download it can read it. On GitHub that is anyone with read access to the repository. The GitLab component sets `artifacts: access: developer`, so only Developer, Maintainer and Owner roles can download it (<https://docs.gitlab.com/ee/ci/yaml/index.html#artifactsaccess>).

Before a capsule is written, `drift capture` redacts environment values whose names contain `KEY`, `TOKEN`, `SECRET`, `PASSWORD`, `PASSWD`, `CREDENTIAL`, `AUTH`, `COOKIE` or `JWT`, and values in known token formats (GitHub, GitLab, npm, PyPI and JWT tokens, AWS keys, Slack tokens, private keys, bearer tokens and credentials in URLs). This is pattern matching, not a guarantee. Keep secrets out of the environment of a captured job, and do not pass `--keep-identifiers` in CI.

### GitLab Component

`templates/drift.yml` follows the component layout in the GitLab documentation (a `templates/` directory, a `spec:inputs` header and the jobs after `---`; <https://docs.gitlab.com/ci/components/>). The artifact lookup is the job artifacts API (<https://docs.gitlab.com/api/job_artifacts/>).

```yaml
include:
  - component: $CI_SERVER_FQDN/gmitch215/drift/drift@1.0.0
    inputs:
      command: ./gradlew test
```

The inputs are `command` (required), `stage` (`test`), `image` (`ubuntu:24.04`), `version` (`v1.0.0`), `job_name` (`drift`) and `expire_in` (`30 days`). The image needs `curl`, `tar`, `sha256sum` and `unzip` (the script installs `curl` and `unzip` with `apt-get` when they are missing) and glibc, since the Linux release is not built for musl. The job installs the `linux-x64` or `linux-arm64` archive, verifies its checksum, runs the command through `drift run --out drift-capsule` and keeps `drift-capsule/` as an artifact in every case. Only a successful pipeline provides a baseline, so a green job in a pipeline that failed elsewhere is not one. On a failure the job downloads the artifact of the job with the same name, prints the diagnosis to the log, writes it to `drift-capsule/diagnosis.txt` and exits with the command's code. The job log of the red run in the local test (one run of the template's script in a Linux container against a stub of the artifact API) reads:

```
Error: unexpected timezone Asia/Tokyo
drift: exit 3 after 0 ms; capsule in drift-capsule
drift: baseline is the latest green drift on master
env.TZ differs most (tier difference); this ranks differences and does not confirm a cause.
```

`.github/workflows/gitlab.yml` mirrors the repository to gitlab.com/gmitch215/Drift on every push. The component has not run on gitlab.com and is not published to the CI/CD catalog.

### Checking the Scripts

```sh
.github/action/test-drift.sh drift
.github/action/test-gitlab.sh drift
```

The first installs a locally staged release archive, verifies its digest, and runs every mode against a stub `gh`, including a fork-built artifact that must be ignored. The second runs the template's script lines, extracted from the YAML, against a stub of the artifact API. The GitLab test runs on Linux only. Both run in the `build.yml` test job.

## Scripting Patterns

**Gate a step on `verify`.** `verify` exits 1 when any check fails and prints the report either way, so a script can stop on the exit code and read the JSON for the reason. `solve` and `ingest` encode their verdict in the exit code only with `--exit-status`, and `diagnose` never does (it prints a ranking, not a verdict); otherwise read their output.

```sh
set -eu
drift verify fixtures/lab/cases/limits-nofile-1.driftcase --json > verify.json
jq -r '.ok, .verdict' verify.json | cut -c1-80
drift verify bt.driftcase --json > bad.json 2>/dev/null || echo "verify failed: $(jq -r .firstFailure bad.json)"
```

```
true
STUCK: the failure does not reproduce in the container built from the failing ca
verify failed: manifest
```

`bt.driftcase` is the edited archive from [Verify](#pack-report-and-verify).

**Pipe the JSON, parse with `jq`.** `capture` and `diagnose` print one line of canonical JSON.

```sh
drift diagnose $G $R | jq -r '.relevant[] | .path' | head -3
```

```
tool.node.version
ci.provisioner.build-date
ci.provisioner.version
```

**Determinism and hashing.** These outputs are functions of their inputs, so a hash identifies them. Over the same two capsules `diagnose` printed the same bytes on every run here (`83c35a87...187c`), `reproduce` wrote identical files, `pack` identical archives and `atlas build` an identical dataset.

```sh
drift diagnose $G $R | shasum -a 256
drift diagnose $G $R | shasum -a 256
```

```
83c35a87e4f48696684ac6817e31b04e5b9b1bf3c37452d6cbc8c12a030c187c  -
83c35a87e4f48696684ac6817e31b04e5b9b1bf3c37452d6cbc8c12a030c187c  -
```

The test suite pins these outputs for fixtures on the JVM, macOS Native and Wasm targets (see [TECHNICAL_REPORT.md](./TECHNICAL_REPORT.md), "Reproducing the Numbers"). A capsule from `capture` is the exception: it describes the machine, so two machines give two hashes.

**CI.** Capture in a failing job and in a passing job, keep both files as artifacts, then diagnose anywhere:

```sh
drift capture --label ci-job-1 --tools > capsule-1.json
```

Set `DRIFT_RUNNER_LABEL` in the job to record the runner. Do not use `--keep-identifiers` in CI: logs and artifacts are readable by more people than a local shell. To put the executable on a runner, run `install.sh` with `--dir` and `--no-modify-path` and add that directory to the job's path (see [Install Scripts](#install-scripts); not run in a CI job, since no release exists).

## Exit Codes

| Code | When |
| --- | --- |
| 0 | the command ran, including `diagnose` with no cause found, `solve` or `ingest` with any verdict (`NEEDS` and `STUCK` included) unless `--exit-status` is given, `drift` or `drift case` alone (they print help), `install` and `uninstall` (also with `--dry-run`), `uninstall` with no receipt, and `serve` after Ctrl+C or SIGTERM on a native build |
| 3 | `contract create` found no assertion set that separates a red capsule from the greens |
| the command's own | `run`: the exit code of the command it ran (0 included); a command the shell cannot find gives 127 |
| 10 | `solve --exit-status` or `ingest --exit-status` ended `CONFIRMED EFFECT (bundle)` or `CONFIRMED EFFECT (isolation not measured)` |
| 11 | the same, ended `NARROWED` |
| 12 | the same, ended `STUCK` |
| 13 | `solve --exit-status` ended `NEEDS` |
| 1 | usage error from the parser: unknown command or flag, missing argument or option, bad `--detail` value, `--json` with `--detail` |
| 1 | unreadable or malformed input: missing file, malformed capsule JSON, unsupported capsule schema, missing case, missing archive, a corrupt install receipt |
| 1 | `case show` or `next` on a case that fails its integrity check |
| 1 | `contract verify` when any assertion breaks (the report is still printed on standard output) |
| 1 | `verify` when any check fails (the report is still printed on standard output) |
| 1 | `doctor` when any check fails (the report is still printed on standard output) |
| 1 | `run` with `--tail-lines` below 1, an existing capsule directory without `--force` or a directory it cannot write; `import` with a log that holds no runner environment, an unreadable file or an invalid summary; `bisect plan` over capsules with no static difference; `bisect apply` for a step that is not pending, with neither or both of `--green` and `--red`, or on a finished plan |
| 1 | a refusal: `ingest` with any bad result, `pack` on an unsolved case, `diagnose --case` or `solve --case` into an existing case, a bad `--budget` or `--fail-when`, `install` into a directory that is not writable or while another install exists |
| 1 | `upgrade-check` with no target, a target that is not `name=version`, an unknown name, a value that is not a version for an alias, two names for one attribute with different values, or an unreadable capsule or `--to-file` |
| 1 | `triage` with an unreadable file, a `--publish-threshold` outside 0 to 1, a `--date` that is not `yyyy-mm-dd`, an unreadable or invalid `--confidence` table or `--learned` store, `--corpus` or `--out` without `calibrate`, or `calibrate` without both or with an unreadable corpus |
| 1 | `ci` outside a git checkout, on a host that is neither GitHub nor GitLab, without `gh` or `glab` on the path or a login, with no failed run, no passing run to compare with or no such job, when a log cannot be read, and for a bad flag value; it prints one sentence with the next step |
| 1 | `handsoff plan` with any stop (`two-lockfiles`, `no-lockfile`, `package-manager`, `runtime-version`, `monorepo`, `no-test-command`, `several-test-commands`, `matrix-cell`, `ci-config`, `nothing-found`) or a bad `--cell` or `--runtime` value; the report is still printed on standard output |
| 1 | `serve` with no build to serve, an unreadable file, a busy port or a build without TLS; `install.sh` for any failed step |
| 2 | `install` with `--user` and `--global`, a relative `--dir`, or from the JVM build; `serve` with a bad combination of options (`--tls` without a certificate, `--cert` without `--key`, `--mdns` without a `.local` name); `install.sh` for an unknown option, `--user` with `--global`, or `--dir` without a value |
| 130 | `serve` on the JVM build after Ctrl+C (the JVM's status for SIGINT) |

`--exit-status` gives `CONFIRMED` 0. Codes 10 to 12 are used only for verdicts. The parser's own errors exit 1 in this CLI, as they do for every command; the usage errors that the install and serve commands detect themselves exit 2. Every row except 130 and the Windows, musl and missing-OpenSSL cases was run on the macOS executable. The 130 row is from a recorded JVM run.

## Troubleshooting

| Message | Meaning |
| --- | --- |
| `invalid capsule file broken.json: expected key at 2` | the capsule file is not valid JSON (here a lone `{`) |
| `unsupported capsule schema 2` | the capsule's `schema` is not 1 |
| `cannot read capsule file: missing.json` | no such file |
| `case.driftcase is a .driftcase archive; select a capsule with case.driftcase#passing or case.driftcase#failing (drift verify, drift report and drift open read the whole archive)` | a capsule command was given an archive without `#passing` or `#failing` |
| `case.driftcase has no environments/failing.json; use drift verify, drift report or drift open` | the archive does not hold the selected capsule |
| `green.json is not a .driftcase archive; drop #passing` | a selector was given for a plain capsule file |
| `Error: missing argument <red>` | `diagnose` and `solve` take two capsules, green first |
| `Error: missing option --out` | `reproduce` requires both `--run` and `--out`; `--help` marks them `(required)` |
| `case directory already holds a case: case` | `diagnose --case` and `solve --case` never overwrite; pick a new directory |
| `case bad failed its checks: ...` | a file differs from the manifest or the chain is broken; the message names the file |
| `cannot read case nonexistent: missing case file: manifest.json` | the path is not a case directory |
| `cannot start command: nope` | `run` could not start the command (the JVM build; native builds report exit code 127 instead) |
| `red-run already holds a capsule directory; pass --force to replace it` | `run` and `import` never overwrite a capsule directory by default |
| `cannot import job.log: no runner environment found (expected an Operating System or Runner Image group)` | the file is not a log of that `--format`, or it was cut before the runner header |
| `--against takes at most one capsule` | `diagnose --against` takes the baseline and at most one current capsule |
| `the pending step is 1, not 3` | `bisect apply` takes the step the plan printed last |
| `step 2 already holds red` | that step was recorded with the other outcome; the plan is not changed |
| `the bisection is finished` | the plan already holds a minimal set |
| `--env NOPE does not name an env.* attribute of the capsule` | `--env` takes a name that the capsule holds |
| `nothing was ingested:` plus a reason | see the table under [Ingest](#ingest); the case was not changed |
| `cannot pack case: not a solved case: no certificate.json` | `pack` takes a case written by `solve` |
| `verify: failed at tar: archive is truncated: no end-of-archive marker` | the file is not a `.driftcase` |
| `case is a case directory; expected a .driftcase archive; pack the case first (drift pack case --out file.driftcase)` | `verify` and `report` read an archive; pack the case directory first |
| `verify: failed at manifest: results/0001.json was changed` | the archive was edited after `pack`; later checks list what else disagrees |
| `no probe nope in the dataset` | the id is not one of the 38 probes; `atlas show DATASET` lists them |
| `--target may only use letters, digits, '.', '_' and '-'` | the label becomes a file name |
| `error: --user and --global cannot be combined` | `install` takes one scope |
| `error: --dir must be an absolute path: relative/bin` | `install --dir` needs an absolute path |
| `error: drift is already installed in /a (user); run drift uninstall first` | one receipt, one install; uninstall before moving it |
| `error: cannot read the install receipt at <path> (expected key at 1); nothing changed` | the receipt is not valid JSON; fix or delete it by hand |
| `error: cannot write to /ro` | the directory or its nearest parent is not writable; pick another `--dir`, or run it as an elevated user for `--global` |
| `error: the JVM distribution cannot install itself; install the native executable` | `install` needs a native build |
| `no install receipt at <path>; nothing removed` | `uninstall` found nothing to undo (exit 0) |
| `error: no Studio web build found; pass --dir <dist> (build one with ./gradlew :studio:wasmJsBrowserDistribution)` | `serve` found no `index.html`; the next lines list where it looked |
| `error: cannot listen on port 57111: Address already in use; pass --port 0 to take a free port` | the port is taken |
| `error: --tls needs --cert and --key, or --mkcert` | `--tls` has no certificate source |
| `error: --cert and --key go together` | give both files |
| `warning: mkcert was not found on PATH; serving plain http instead` | `--mkcert` without mkcert; the commands to run follow |
| `warning: the mkcert local CA is not installed in the system trust store; serving plain http instead` | run `mkcert -install` yourself, then `--mkcert` again |
| `warning: leaf-key.pem is readable by other users; run: chmod 600 leaf-key.pem` | the key file has loose permissions |
| `403` from `serve` | the Host header is not `localhost`, `127.0.0.1`, `[::1]` or a `--domain` name |
| `drift-install: error: checksum mismatch for <asset> ...; nothing was installed` | the download is corrupt or changed; fetch it again |
| `drift-install: error: cannot download <url>` | no such asset; no release has been published yet, or the base URL or version is wrong |
| `drift-install: error: set DRIFT_VERSION when DRIFT_INSTALL_BASE_URL is set` | a custom base needs an explicit version |
| `the Linux executable is built for glibc and this system uses musl; no asset is available` | `install.sh` on Alpine and other musl systems (from the installer's Alpine run) |

When a `solve` trial cannot start, the certificate records an infrastructure problem (a failed `docker build`, a `docker run` error, a timeout) and the loop retries once before it ends `NEEDS` with reason `executor`. Check `docker info` first, and run `./repro/run.sh` from a `reproduce` of the same capsule to see the failure by hand.

## Limits

- A container mirrors a part of a machine. Never mirrored: the kernel (`kernel.*`), the CPU (`cpu.*`, `hw.*`), the CI provider's image and provisioner (`ci.*`), dependencies installed by the test command (`deps.*`) and the properties of the capturing binary. `reproduce` lists every unmirrored attribute and its reason, and the certificate carries the list. A `solve` verdict is about containers built from the capsules, not about the original host.
- The ranking uses hand-set weights (`hand-set-1`, `weights.calibrated: false` in `diagnose` output). A score orders candidates; it is not a probability. A ranking whose candidates all rest on attribute changes also prints a probability, read through an isotonic map fitted on the dev split (`probability.calibrated: true`). On the dev benchmark the `plausible` tier (a probe differs in the candidate's dimension) was unreliable: 10 of its 19 candidates were wrong, and probes helped no scenario. The numbers and their intervals are in [TECHNICAL_REPORT.md](./TECHNICAL_REPORT.md) under "Evaluation and Limits".
- `run` merges standard error into standard output and keeps only the tail of the output. `import` reads what a log prints, which is less than a capture: no kernel, CPU or probe data. `bisect` does not run tests and treats the empty and full sets as known.
- `ingest` can narrow a case or rule a hypothesis out. It never confirms a cause.
- `solve` runs on local Docker only. It does not use a remote host, a CI service or a cluster, and a failure that needs a different kernel, CPU model or CI-owned image cannot be varied by it (the run ends `NEEDS` with reason `uncontrollable` and the manual experiment as its step).
- `solve` assumes a single cause: the first supported experiment ends the loop.
- `verify` checks that an archive agrees with itself. It does not re-run experiments, so it cannot tell a faithful archive from a forged one that was rewritten consistently.
- `next` after `ingest` skips the experiments that have results but does not re-plan; the order of the rest comes from the plan made before the results (see [Ingest](#ingest)).
- The Atlas columns are single runs on the hardware named in [`atlas/columns.yml`](./atlas/columns.yml). A column recorded on other hardware can differ, and `atlas compare` against a column from another machine reports a difference without saying whether hardware caused it. A Linux executable linked on Ubuntu prints different last digits for three lines of `kotlin.math.transcendental-bits` than one linked on macOS, so the `linux` columns come from the macOS-linked build and a CI recording can differ. The `wasm` column on Node comes from a test harness, because the Wasm CLI has no file writer, and the `mingw` and `jvm-windows` columns are not recorded yet. The classification rests on the pages its authors read, and 16 of the 38 probes are `unclassified`. Generated repro text needs a person's review before it goes into an issue.
- `install` and `uninstall` were run on macOS only. The Linux behavior comes from the installer's Debian run, and the Windows registry and `drift.old` behavior from a run under Wine. A real Windows machine has not run `drift install`, and package-manager awareness is absent: it does not refuse to run inside a Homebrew or Chocolatey tree.
- `serve` is a development server for a local Studio build. It binds to loopback, has no range requests, compression or directory listing, reads each file whole, and has no TLS on Windows. The embedded-asset option (a CLI that carries Studio inside it) is not built.
- No release has been published. The asset names, the `brew` and `choco` commands and the install scripts' default download URL are what the workflows and templates say, and they have not been exercised against GitHub.
