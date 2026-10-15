# Advanced Usage: The Full CLI

This guide covers every `drift` command and flag for people who have already run `drift capture`. It shows scripting patterns, the JSON and text output levels, cases, reproduction, the Lab (`solve`, `ingest`, `pack`, `report`, `verify`), CI commands (`run`, `import`, `diagnose --against`, `bisect plan`, `bisect apply`), portability contracts (`contract create`, `contract verify`), issue triage (`triage`), upgrade preflight (`upgrade-check`), the Atlas, and the commands that install and serve Drift (`install`, `uninstall`, `serve`, `install.sh`, `install.ps1`). For the design and the measurements behind the commands, see [TECHNICAL_REPORT.md](./TECHNICAL_REPORT.md).

Every example below was run with the macOS release executable (`drift version 1.0.0 (macos)`) from a directory that holds `fixtures/` and `bench/`, against the files in [`fixtures/`](./fixtures/) and [`bench/data/`](./bench/data/). Output is copied from those runs; a line that holds only `...` marks output cut for length. Where a command prints to standard error, the example says so. The offline run in [Offline](#offline) used the Linux arm64 executable inside a Docker container. The `install`, `uninstall` and `serve` examples ran with a scratch home directory and a scratch prefix, never the real profile, registry or trust store. Their output shows the scratch home as `/home/you`, the scratch work directory as `/work` and the scratch release directory as `/release`. The Windows and Linux builds and the `.ps1` script were not run for this guide, and the sections say where a statement comes from a recorded run of someone else's.

The executable is `cli/build/bin/macosArm64/releaseExecutable/drift.kexe`; the README explains how to build it and how to download one. The examples call it `drift`; put it on `PATH` (see [Install and Uninstall](#install-and-uninstall)) or alias it.

## Command Index

| Command | Reads | Writes | Needs Docker |
| --- | --- | --- | --- |
| [`capture`](#capture) | this machine | a capsule on standard output | no |
| [`diagnose`](#diagnose) | two capsules | JSON or text, optionally a case directory | no |
| [`diagnose --against`](#against-a-baseline) | a baseline and a capsule, or this machine | JSON or text, optionally a case directory | no |
| [`diagnose --set`](#over-a-set-of-capsules) | two or more capsules, each passing or failing | JSON or text | no |
| [`run`](#run) | this machine, a command | a capsule directory | no |
| [`import`](#import) | a saved GitHub Actions or GitLab CI log | a capsule directory | no |
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
| [`probe cloudflare`](#probe) | a built Worker directory | a transcript | no |
| [`triage`](#triage) | an issue body (a file or standard input) | text or JSON | no |
| [`atlas build`](#atlas) | transcripts | a dataset | no |
| [`atlas compare`](#atlas) | a dataset, a transcript | text | no |
| [`atlas show`](#atlas) | a dataset | text | no |
| [`atlas diff`](#atlas) | a dataset | text | no |
| [`install`](#install-and-uninstall) | this executable | a copy of it, shell profiles or the registry, a receipt | no |
| [`uninstall`](#install-and-uninstall) | the receipt | removes what `install` wrote | no |
| [`serve`](#serve) | a Studio web build directory | nothing (a local web server) | no |

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

The counts are from one Mac and vary with the tools installed. `--probes` adds 71 probe records; each holds `id`, `status`, a `hash` and the `transcript`.

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

A normal capture also reads these facts, without `--tools`:

- `cgroup.version` is `v2` when every line of `/proc/self/cgroup` starts with `0::` and `v1` when any line names a v1 controller (a hybrid host counts as `v1`).
- `tz.system-zone` is the target of the `/etc/localtime` link below `zoneinfo/`, then the content of `/etc/timezone`. `Etc/UTC`, `Etc/UCT`, `Etc/Universal`, `Etc/Zulu`, `UCT`, `Universal` and `Zulu` are written as `UTC`; `Etc/GMT` stays as it is. It is absent on Windows and where neither source exists. `env.TZ` is separate.
- `runtime.jvm.options` joins `JAVA_TOOL_OPTIONS` and `JDK_JAVA_OPTIONS`, in that order, and is absent when both are empty. Flags on a `java` command line are not visible to a capture.
- `java.net.preferIPv6Addresses` is the last `-Djava.net.preferIPv6Addresses=` value in those options, and absent when they do not set it.

Keys that a capture of this machine cannot supply: `runtime.database.server.*`, `network.tls-version-offered` and `net.dns.aaaa.present` need a connection or a name lookup against a named target, and `browser.name` needs a browser. Rules that read them stay `pending-scanner`.

### Attribute Prefixes

A rule's `applies` list and the ranking use one of 14 dimensions. The dimension of an attribute path comes from the longest matching prefix in this table; a path that no prefix claims, such as `drift.platform`, has no dimension.

| Dimension | Prefixes |
| --- | --- |
| `os` | `os.name`, `os.version`, `os.release.`, `tool.libc.`, `libc.`, `windows.`, `fs.`, `crypto.`, `file.` |
| `kernel` | `kernel.`, `cgroup.` (except the two below), `sysctl.` |
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
e47bd4ace69badf351800c7d737dbe58262f5cf1f3445312edce53e9f5b2cc08  diag.json
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

`--detail detail` adds the ranked candidates, the bundle and the next experiment (64 lines for this pair). `--detail full` adds every changed attribute (285 lines). The text is plain ASCII, wrapped at 100 columns.

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

Put `--` before the command so that its own flags are not read as `drift` flags; arguments after the first word of the command are passed through either way. A program that does not exist makes the shell report exit code 127, which `drift run` passes through. The command is started directly when the host can (the JVM build) and through `sh` on the native builds. The Windows build was not run for this guide.

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

Nothing in `run`, `import`, `diagnose --against` or `bisect` opens a network connection. This run used the Linux arm64 executable in a container with `--network none`, whose network namespace holds the loopback device and the kernel's default tunnel devices, all `down` or `unknown` in `/sys/class/net/*/operstate`:

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
RUN apk add --no-cache gcompat tzdata
RUN npm install -g npm@10.9.2
WORKDIR /work
```

`wrote 3 files` goes to standard error and the summary to standard output. Running the command twice produces identical files:

```sh
shasum -a 256 repro/*
```

```
67b596a58c76f708d69be46e3a99d00a84e4007142697cae8cc0fb76dfbf1d9c  repro/Dockerfile
33592f2de94cedfb387f9311810f65a72939566ee7183a8dee0da32f3cf7b878  repro/manifest.json
14e096545016d3c998c10ff787a0ec456f1dab2fbeba38516b090f22d9abd280  repro/run.sh
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

**Naming the toolchain.** The base image comes from the toolchain the capsule records. `--run` decides which one when the capsule records several: the first word of the command that names a toolchain wins (a path prefix is ignored, so `./gradlew` counts as `gradlew`; `node`, `npm` and `python3` are other examples). With no match, the first toolchain in table order is used.

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
| `--exit-status` | exit 0 `CONFIRMED`, 10 `CONFIRMED EFFECT (bundle)`, 11 `NARROWED`, 12 `STUCK` (default: 0 for every verdict) |
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

**Budgets.** `--budget` caps trials over all arms and container minutes together. If no trials-per-arm count up to 30 reaches power 0.8 inside the remaining budget, the run ends `STUCK` with reason `budget` and names the shortfall. A bad item fails before any container starts: `bad --budget item: bogus`, or `--budget takes trials=N and minutes=M, not hours=2`.

**The four verdicts.**

| Verdict | Meaning |
| --- | --- |
| `CONFIRMED` | a minimal set of attributes, all set directly, reproduces the failure forward and removes it in reverse |
| `CONFIRMED EFFECT (bundle)` | the same, but the arms also differ in attributes nobody set; each member is named |
| `NARROWED` | an experiment was supported or stayed inconclusive, but the conditions above are not met |
| `STUCK` | no experiment was supported; the reason is `not-reproduced`, `uncontrollable`, `no-candidates`, `budget` or `executor` |

A `STUCK` run, with a failure pattern that the program never prints:

```sh
drift solve --run 'sh prog.sh' --fail-when 'no such text' --pilot 3 --budget trials=20,minutes=5 \
  --workspace ws --case stuck bench/data/capsules/locale-tz-date-2.green.json \
  bench/data/capsules/locale-tz-date-2.red.json
```

```
wrote 19 files to stuck
STUCK: the failure does not reproduce in the container built from the failing capsule: 0 of 3 trials
  failed
1 candidate changes, 0 experiments run in containers (6 trials, 6 seconds), not on the original
  host.
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
wrote packed.driftcase: 19 files, 50176 bytes, sha256 8d4aaf9f238896a823a4aa065b3022df1581b42e4c3e0f8c93986bfb1c889cba
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
73 of 298 rules read a target key

Will break (0)
  none

May break (7)
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

Only rules that read a target key on side `b` and give a different verdict for the targets than for the capsule alone are listed. The line `73 of 298 rules read a target key` counts the rules that read one before that filter. Probe results and run facts describe today's environment, so side `b` has none, and a rule that reads one is unknown.

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
{"rules":298,"touched":73,"willBreak":[],"versionBoundary":[{"rule":"php-8-2-dynamic-properties-deprecated","crossings":[{"key":"tool.php.version","from":"8.1.34","to":"8.3"}]},{"rule":"drupal-10-to-11-symfony-6-2-to-7-1","crossings":[{"key":"project.composer.drupal/core","from":"10.3.6","to":"11"},{"key":"project.composer.symfony/http-kernel","from":"v6.4.12","to":"7.1"}]}]}
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
46 of 298 rules read a target key

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

May break (0)
  none

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
21 of 298 rules read a target key

Will break (0)
  none

May break (0)
  none

Version boundary crossed (0)
  none

Unknown (15)
  1. [high, verified] Drupal 10 removes the Aggregator, Color, HAL, Quick Edit and RDF modules and the Bartik, Seven, Classy and Stable themes from core
     rule: drupal-10-removed-core-modules-and-themes
     boundary: project.composer.drupal/core unknown -> 11 (rule: was <10, now >=10)
     needs: project.composer.drupal/core, project.drupal.modules.aggregator, project.drupal.modules.color, project.drupal.modules.hal, project.drupal.modules.quickedit, project.drupal.modules.rdf, project.drupal.themes.bartik, project.drupal.themes.classy, project.drupal.themes.seven, project.drupal.themes.stable; the rule is pending-scanner
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

The Atlas asks, per probe, whether a Kotlin program prints the same thing on every target. It holds 38 probes. `atlas record` runs them on this target, `atlas build` aggregates transcripts into a dataset, `atlas compare` places this device against it, `atlas show` prints the matrix or a draft for one probe and `atlas diff` lists what changed between two Kotlin versions.

**Columns.** `fixtures/atlas/` holds 15 recorded columns on Kotlin 2.4.20, and `fixtures/atlas/2.5.0-beta1/` holds 5 on 2.5.0-Beta1 (`jvm`, `macos`, `wasm`, `android`, `ios`). [`atlas/columns.yml`](./atlas/columns.yml) says how each was measured.

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
dataset: 38 probes, 3 columns, 23 divergent, sha256 c69fd560a487f6084b1e1296ed478aac18b58265c47e96e6a10fa3e1503e5ea4
dataset: 38 probes, 21 columns, 32 divergent, sha256 8364183f0ffda6face1da93bd71142ac66761d85cc145818533eebffa574aae5
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

`./gradlew docsSite` runs `dokkaGenerate` and `atlasSite` first, then assembles `build/site` (3,089 files): `README.md` as `index.html`, `ADVANCED_USAGE.md` as `advanced-usage.html` and `TECHNICAL_REPORT.md` as `technical-report.html`, each rendered into `assets/site-template.html`, the Atlas site under `atlas/`, the Dokka site under `engine/`, the icons from `assets/`, `CNAME` and `.nojekyll`. A relative link in the three Markdown files to a repository path that does not exist fails the render, and a link to one that does exist becomes a GitHub URL on `master`. `checkSiteLinks` runs after it and fails on a relative `href` or `src` that does not resolve inside `build/site`. It scanned 73,286 links and found none unresolved outside `engine/`, where Dokka leaves 14 unresolved; those are counted and printed and do not fail it.

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

The keys are the ones the rules read: `os.name`, `os.version`, `os.arch`, `os.release.ID`, `os.release.VERSION_ID`, `tool.<name>.version` for Java, Kotlin, Gradle, Node, npm, Python, PHP, Go, Rust, Git, .NET, workerd, pip, `tool.cc.*` and `tool.libc.*`, `project.gradle.kotlin.version`, `project.gradle.agp.version`, `project.<ecosystem>.<package>` for dependencies pasted from a build file or an install command, `browser.name`, `browser.version`, `ci.provider`, `ci.runner.label`, `runtime.platform`, `runtime.container.image`, `runtime.jvm.options`, `env.LANG`, `env.TZ`, and `run:signature` for the first error line, normalized the way a failing step is.

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

A draft needs a symptom signature, one version key stated on both sides with the passing version below the failing one, and no rule that already holds on that key. Its detect tree is the version range each side stated plus the signature:

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

### Fixtures and Scores

[`fixtures/triage/`](./fixtures/triage/) holds 70 issue bodies, each with an expectation file. The first 52 are strict forms (10), forms with blank issues (8), free text (12), sparse or wrong beginner reports (10) and mixed phrasing (12); the other 18 cover false positives of the key-value path and claim wording. The bodies are invented for this repository; none is copied from an issue. The expectation lists every fact the text states, the rules and claims it should trigger and whether a draft follows. `TriageTest` scores the extractor on them and prints the result:

| | |
| --- | --- |
| facts extracted | 189 |
| precision, recall | 0.989, 0.979 (187 true positives, 2 false positives, 4 false negatives) |
| Brier score | 0.0851 |

The misses are the intended hard cases: a form field that still holds the old version (`b07-form-wrong-version`), a fix reported as `upgraded to node 20 ... went away` (`h05-form-then-fix`), `Go 1.22 tutorials` read as an environment (`h11-lookalike-prose`) and `on my mac` not read as macOS (`b01-form-no-response`). The test fails when precision drops below 0.95, recall below 0.93 or the Brier score above 0.12, and when any path's mean confidence exceeds its accuracy by more than 0.05. The scores are an upper bound for real issues.

### Limits

A version with no name next to it is not read (`since upgrading to 3.13.0`). One value is kept per key and side. A form field that holds the wrong value is read as written, with 0.950. The text of a pasted log is read only for its first error line. Claims cover nine statements. The draft rule generalizes from one report and is a starting point for a person, not a finding. `-` was not run on Windows.

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

**Determinism and hashing.** These outputs are functions of their inputs, so a hash identifies them. Over the same two capsules `diagnose` printed the same bytes on every run here (`e47bd4ac...cc08`), `reproduce` wrote identical files, `pack` identical archives and `atlas build` an identical dataset.

```sh
drift diagnose $G $R | shasum -a 256
drift diagnose $G $R | shasum -a 256
```

```
e47bd4ace69badf351800c7d737dbe58262f5cf1f3445312edce53e9f5b2cc08  -
e47bd4ace69badf351800c7d737dbe58262f5cf1f3445312edce53e9f5b2cc08  -
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
| 0 | the command ran, including `diagnose` with no cause found, `solve` or `ingest` with any verdict (`STUCK` included) unless `--exit-status` is given, `drift` or `drift case` alone (they print help), `install` and `uninstall` (also with `--dry-run`), `uninstall` with no receipt, and `serve` after Ctrl+C or SIGTERM on a native build |
| 3 | `contract create` found no assertion set that separates a red capsule from the greens |
| the command's own | `run`: the exit code of the command it ran (0 included); a command the shell cannot find gives 127 |
| 10 | `solve --exit-status` or `ingest --exit-status` ended `CONFIRMED EFFECT (bundle)` |
| 11 | the same, ended `NARROWED` |
| 12 | the same, ended `STUCK` |
| 1 | usage error from the parser: unknown command or flag, missing argument or option, bad `--detail` value, `--json` with `--detail` |
| 1 | unreadable or malformed input: missing file, malformed capsule JSON, unsupported capsule schema, missing case, missing archive, a corrupt install receipt |
| 1 | `case show` or `next` on a case that fails its integrity check |
| 1 | `contract verify` when any assertion breaks (the report is still printed on standard output) |
| 1 | `verify` when any check fails (the report is still printed on standard output) |
| 1 | `run` with `--tail-lines` below 1, an existing capsule directory without `--force` or a directory it cannot write; `import` with a log that holds no runner environment, an unreadable file or an invalid summary; `bisect plan` over capsules with no static difference; `bisect apply` for a step that is not pending, with neither or both of `--green` and `--red`, or on a finished plan |
| 1 | a refusal: `ingest` with any bad result, `pack` on an unsolved case, `diagnose --case` or `solve --case` into an existing case, a bad `--budget` or `--fail-when`, `install` into a directory that is not writable or while another install exists |
| 1 | `upgrade-check` with no target, a target that is not `name=version`, an unknown name, a value that is not a version for an alias, two names for one attribute with different values, or an unreadable capsule or `--to-file` |
| 1 | `triage` with an unreadable file, a `--publish-threshold` outside 0 to 1, a `--date` that is not `yyyy-mm-dd`, an unreadable or invalid `--confidence` table or `--learned` store, `--corpus` or `--out` without `calibrate`, or `calibrate` without both or with an unreadable corpus |
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

When a `solve` trial cannot start, the certificate records an infrastructure problem (a failed `docker build`, a `docker run` error, a timeout) and the loop retries once before it ends `STUCK` with reason `executor`. Check `docker info` first, and run `./repro/run.sh` from a `reproduce` of the same capsule to see the failure by hand.

## Limits

- A container mirrors a part of a machine. Never mirrored: the kernel (`kernel.*`), the CPU (`cpu.*`, `hw.*`), the CI provider's image and provisioner (`ci.*`), dependencies installed by the test command (`deps.*`) and the properties of the capturing binary. `reproduce` lists every unmirrored attribute and its reason, and the certificate carries the list. A `solve` verdict is about containers built from the capsules, not about the original host.
- The ranking uses hand-set weights (`hand-set-1`, `weights.calibrated: false` in `diagnose` output). A score orders candidates; it is not a probability. On the dev benchmark the `plausible` tier (a probe differs in the candidate's dimension) was unreliable: 10 of its 19 candidates were wrong, and probes helped no scenario. The numbers and their intervals are in [TECHNICAL_REPORT.md](./TECHNICAL_REPORT.md) under "Evaluation and Limits".
- `run` merges standard error into standard output and keeps only the tail of the output. `import` reads what a log prints, which is less than a capture: no kernel, CPU or probe data. `bisect` does not run tests and treats the empty and full sets as known.
- `ingest` can narrow a case or rule a hypothesis out. It never confirms a cause.
- `solve` runs on local Docker only. It does not use a remote host, a CI service or a cluster, and a failure that needs a different kernel, CPU model or CI-owned image cannot be varied by it (the run ends `STUCK` with reason `uncontrollable`).
- `solve` assumes a single cause: the first supported experiment ends the loop.
- `verify` checks that an archive agrees with itself. It does not re-run experiments, so it cannot tell a faithful archive from a forged one that was rewritten consistently.
- `next` after `ingest` skips the experiments that have results but does not re-plan; the order of the rest comes from the plan made before the results (see [Ingest](#ingest)).
- The Atlas columns are single runs on the hardware named in [`atlas/columns.yml`](./atlas/columns.yml). A column recorded on other hardware can differ, and `atlas compare` against a column from another machine reports a difference without saying whether hardware caused it. A Linux executable linked on Ubuntu prints different last digits for three lines of `kotlin.math.transcendental-bits` than one linked on macOS, so the `linux` columns come from the macOS-linked build and a CI recording can differ. The `wasm` column on Node comes from a test harness, because the Wasm CLI has no file writer, and the `mingw` and `jvm-windows` columns are not recorded yet. The classification rests on the pages its authors read, and 16 of the 38 probes are `unclassified`. Generated repro text needs a person's review before it goes into an issue.
- `install` and `uninstall` were run on macOS only. The Linux behavior comes from the installer's Debian run, and the Windows registry and `drift.old` behavior from a run under Wine. A real Windows machine has not run `drift install`, and package-manager awareness is absent: it does not refuse to run inside a Homebrew or Chocolatey tree.
- `serve` is a development server for a local Studio build. It binds to loopback, has no range requests, compression or directory listing, reads each file whole, and has no TLS on Windows. The embedded-asset option (a CLI that carries Studio inside it) is not built.
- No release has been published. The asset names, the `brew` and `choco` commands and the install scripts' default download URL are what the workflows and templates say, and they have not been exercised against GitHub.
