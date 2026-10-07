# Advanced Usage: The Full CLI

This guide covers every `drift` command and flag for people who have already run `drift capture`. It shows scripting patterns, the JSON and text output levels, cases, reproduction, the Lab (`solve`, `ingest`, `pack`, `report`, `verify`), the Atlas, and the commands that install and serve Drift (`install`, `uninstall`, `serve`, `install.sh`, `install.ps1`). For the design and the measurements behind the commands, see [TECHNICAL_REPORT.md](./TECHNICAL_REPORT.md).

Every example below was run with the macOS release executable (`drift version 1.0.0 (macos)`) from a directory that holds `fixtures/` and `bench/`, against the files in [`fixtures/`](./fixtures/) and [`bench/data/`](./bench/data/). Output is copied from those runs; a line that holds only `...` marks output cut for length. Where a command prints to standard error, the example says so. The `install`, `uninstall` and `serve` examples ran with a scratch home directory and a scratch prefix, never the real profile, registry or trust store. Their output shows the scratch home as `/home/you`, the scratch work directory as `/work` and the scratch release directory as `/release`. The Windows and Linux builds and the `.ps1` script were not run for this guide, and the sections say where a statement comes from a recorded run of someone else's.

The executable is `cli/build/bin/macosArm64/releaseExecutable/drift.kexe`; the README explains how to build it and how to download one. The examples call it `drift`; put it on `PATH` (see [Install and Uninstall](#install-and-uninstall)) or alias it.

## Command Index

| Command | Reads | Writes | Needs Docker |
| --- | --- | --- | --- |
| [`capture`](#capture) | this machine | a capsule on standard output | no |
| [`diagnose`](#diagnose) | two capsules | JSON or text, optionally a case directory | no |
| [`next`](#next-and-case-show) | a case directory | text | no |
| [`case show`](#next-and-case-show) | a case directory | text | no |
| [`reproduce`](#reproduce) | a capsule | `Dockerfile`, `run.sh`, `manifest.json` | no (it only writes files) |
| [`solve`](#solve) | two capsules | a case directory with a certificate | yes |
| [`ingest`](#ingest) | a case directory, result files | the case directory | no |
| [`pack`](#pack-report-and-verify) | a solved case directory | a `.driftcase` archive | no |
| [`report`](#pack-report-and-verify) | a `.driftcase` | `report.html` | no |
| [`verify`](#pack-report-and-verify) | a `.driftcase` | text or JSON | no |
| [`atlas record`](#atlas) | this machine | a transcript | no |
| [`atlas build`](#atlas) | transcripts | a dataset | no |
| [`atlas compare`](#atlas) | a dataset, a transcript | text | no |
| [`atlas show`](#atlas) | a dataset | text | no |
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
| `--tools` | also run the toolchain scanners (`tool.*` attributes such as `tool.git.version` and `tool.java.version`) |
| `--probes` | also run every probe and store its transcript under `probes` |
| `--keep-identifiers` | keep the account name, host name and home path |

```sh
drift capture --label demo --tools --probes > cap-full.json
jq '.attributes | length' cap.json cap-full.json
jq '.probes | length' cap.json cap-full.json
```

```
86
99
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
18
13
```

`--keep-identifiers` turns the first rule off and prints a warning on standard error:

```
warning: the capsule keeps your account, host name and home path
```

Use it only for a capsule that stays on your machine, for example to see which attribute holds a home path. Do not use it for a capsule that you commit, attach to an issue, put in a case, or hand to `reproduce --env-all`. The redactor knows paths and secret-looking names. It has no rule for email addresses or account ids, so those pass through in any mode.

Exit codes: 0 on success; 1 for a usage error such as an unknown flag (`Error: no such option --bogus`).

## Diagnose

`drift diagnose GREEN RED` compares the capsule of a passing run with the capsule of a failing run, ranks the attributes that differ and proposes experiments. The default output is canonical JSON.

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
8
717191f28a8d593ecf8a5fcedee04b4471187cddfe29718dbd2e38e6676de52e  diag.json
```

The top-level keys are `bundles`, `changed`, `competing`, `facts`, `relevant`, `unattached` and `weights`. `weights.calibrated: false` means the ranking uses hand-set weights, so the order of candidates is not a probability of being the cause.

`--detail` prints text instead, at three levels. It cannot be combined with `--json`.

```sh
drift diagnose --detail summary $G $R
```

```
8 changes differ with the same support, and tool.node.version ranks first only by hand-set weights,
so the data does not name one cause.
```

`--detail detail` adds the ranked candidates, the bundle and the next experiment (64 lines for this pair). `--detail full` adds every changed attribute (285 lines). The text is plain ASCII, wrapped at 100 columns.

```sh
drift diagnose --detail detail $G $R | sed -n 4,10p
```

```
Candidates, best first. Scores come from hand-set weights (hand-set-1, uncalibrated); they order
  candidates and are not probabilities.
  1. tool.node.version tier difference inferred bundle bundle-1
      evidence: attribute tool.node.version; no run history behind it
  2. ci.provisioner.build-date tier difference inferred bundle bundle-1
      evidence: attribute ci.provisioner.build-date; no run history behind it
  3. ci.provisioner.version tier difference inferred bundle bundle-1
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
8 changes differ with the same support, and tool.node.version ranks first only by hand-set weights,
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

8 changes differ with the same support, and tool.node.version ranks first only by hand-set weights,
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
RUN apk add --no-cache tzdata
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

## Atlas

The Atlas asks, per probe, whether a Kotlin program prints the same thing on every target. It holds 38 probes. `atlas record` runs them on this target, `atlas build` aggregates transcripts into a dataset, `atlas compare` places this device against it and `atlas show` prints the matrix or a draft for one probe.

**Columns.** `fixtures/atlas/` holds 13 recorded columns on Kotlin 2.4.20, and `fixtures/atlas/2.5.0-beta1/` holds 5 on 2.5.0-Beta1 (`jvm`, `macos`, `wasm`, `android`, `ios`). [`atlas/columns.yml`](./atlas/columns.yml) says how each was measured.

| Column | Where it was recorded |
| --- | --- |
| `jvm` | the CLI JVM build on Temurin 21, macOS arm64 |
| `jvm-17`, `jvm-21`, `jvm-25` | the same classes (JVM target 17) on Temurin 17, 21 and 25, x86-64 Linux containers |
| `linux`, `linux-arm64` | Kotlin/Native release executables linked on macOS, run in Debian containers |
| `macos` | Kotlin/Native on macOS arm64 |
| `wasm` | Kotlin/Wasm on Node, macOS arm64 |
| `wasm-chromium`, `wasm-firefox`, `wasm-webkit` | the Studio web build in each browser, driven by Playwright in a Linux container (WebKit here is not Safari) |
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
dataset: 38 probes, 3 columns, 23 divergent, sha256 3cf771d67a004c85570eeb323050261a6a2a1d8f74fac71f04551760fb8f0470
dataset: 38 probes, 18 columns, 23 divergent, sha256 de6b25cac2fed7bc5f357d3130be44d1629961efa94d5fdac4ab8a572a2ccebb
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
kotlin.char.case-special                  documented        A           A             A
a letter repeated on a row marks a byte-identical transcript
divergent probes: 23 of 38
  documented: 6 of 18
  platform-defined: 4 of 4
  unclassified: 13 of 16
```

With more than one Kotlin version in the dataset the summary adds a line per version:

```sh
drift atlas show atlas-all.json | tail -3
```

```
divergent probes per kotlin version:
  2.4.20: 23
  2.5.0-Beta1: 18
```

**Classification.** Every probe carries one of three labels, written by hand from primary sources in [`atlas/classification.yml`](./atlas/classification.yml) and embedded in the dataset:

| Label | Meaning | Probes |
| --- | --- | --- |
| `documented` | a Kotlin documentation page states the behavior | 18 |
| `platform-defined` | a JDK or ECMAScript page does | 4 |
| `unclassified` | no explanation was found; the entry records what was searched | 16 |

Each entry also has a `basis` (`verified` when the cited page states the contract, `inferred` when the page implies it), the references with a short quote, and the linked issue ids with the state read on the research date. `unclassified` is not a finding. The 13 divergent unclassified probes each have a repro draft under [`atlas/repros/`](./atlas/repros/).

**Compare.** `atlas compare DATASET TRANSCRIPT` places this device against each recorded column. `--detail summary` prints counts; `detail` adds the probes that differ.

```sh
drift atlas compare atlas.json tr/macos.txt
drift atlas compare atlas-all.json tr/macos.txt | tail -19
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
  ios@2.4.20: 38 of 38 probes match
  ios@2.5.0-Beta1: 37 of 38 probes match
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
basis: inferred
reference: https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.2.3 "IEEE 754 allows multiple d
reference: https://webassembly.github.io/spec/core/exec/numerics.html "Some operators are non-deterministic, b
reference: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/to-raw-bits.html "preserving NaN values exact 
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

The `docs.yml` workflow runs `./gradlew dokkaGenerate atlasSite` and pushes the site, with the engine documentation under `engine/`, to the `gh-pages` branch when a release is published or when someone starts it by hand. It has not run yet.

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
write /home/you/.local/bin/drift (mode 0755, 6742776 bytes)
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
write /opt/tools/drift (mode 0755, 6742776 bytes)
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
write /home/you/.local/bin/drift (mode 0755, 6742776 bytes)
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
curl -fsSL https://raw.githubusercontent.com/gmitch215/Drift/main/install.sh | sh -s -- --dry-run
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
write /home/you/.local/bin/drift (mode 0755, 6742776 bytes)
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

**Determinism and hashing.** These outputs are functions of their inputs, so a hash identifies them. Over the same two capsules `diagnose` printed the same bytes on every run here (`717191f2...de52`), `reproduce` wrote identical files, `pack` identical archives and `atlas build` an identical dataset.

```sh
drift diagnose $G $R | shasum -a 256
drift diagnose $G $R | shasum -a 256
```

```
717191f28a8d593ecf8a5fcedee04b4471187cddfe29718dbd2e38e6676de52e  -
717191f28a8d593ecf8a5fcedee04b4471187cddfe29718dbd2e38e6676de52e  -
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
| 10 | `solve --exit-status` or `ingest --exit-status` ended `CONFIRMED EFFECT (bundle)` |
| 11 | the same, ended `NARROWED` |
| 12 | the same, ended `STUCK` |
| 1 | usage error from the parser: unknown command or flag, missing argument or option, bad `--detail` value, `--json` with `--detail` |
| 1 | unreadable or malformed input: missing file, malformed capsule JSON, unsupported capsule schema, missing case, missing archive, a corrupt install receipt |
| 1 | `case show` or `next` on a case that fails its integrity check |
| 1 | `verify` when any check fails (the report is still printed on standard output) |
| 1 | a refusal: `ingest` with any bad result, `pack` on an unsolved case, `diagnose --case` or `solve --case` into an existing case, a bad `--budget` or `--fail-when`, `install` into a directory that is not writable or while another install exists |
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
| `Error: missing argument <red>` | `diagnose` and `solve` take two capsules, green first |
| `Error: missing option --out` | `reproduce` requires both `--run` and `--out`; `--help` marks them `(required)` |
| `case directory already holds a case: case` | `diagnose --case` and `solve --case` never overwrite; pick a new directory |
| `case bad failed its checks: ...` | a file differs from the manifest or the chain is broken; the message names the file |
| `cannot read case nonexistent: missing case file: manifest.json` | the path is not a case directory |
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
- `ingest` can narrow a case or rule a hypothesis out. It never confirms a cause.
- `solve` runs on local Docker only. It does not use a remote host, a CI service or a cluster, and a failure that needs a different kernel, CPU model or CI-owned image cannot be varied by it (the run ends `STUCK` with reason `uncontrollable`).
- `solve` assumes a single cause: the first supported experiment ends the loop.
- `verify` checks that an archive agrees with itself. It does not re-run experiments, so it cannot tell a faithful archive from a forged one that was rewritten consistently.
- `next` after `ingest` skips the experiments that have results but does not re-plan; the order of the rest comes from the plan made before the results (see [Ingest](#ingest)).
- The Atlas columns are single runs on the hardware named in [`atlas/columns.yml`](./atlas/columns.yml). A column recorded on other hardware can differ, and `atlas compare` against a column from another machine reports a difference without saying whether hardware caused it. A Linux executable linked on Ubuntu prints different last digits for three lines of `kotlin.math.transcendental-bits` than one linked on macOS, so the `linux` columns come from the macOS-linked build and a CI recording can differ. The `wasm` column on Node comes from a test harness, because the Wasm CLI has no file writer, and the `mingw` and `jvm-windows` columns are not recorded yet. The classification rests on the pages its authors read, and 16 of the 38 probes are `unclassified`. Generated repro text needs a person's review before it goes into an issue.
- `install` and `uninstall` were run on macOS only. The Linux behavior comes from the installer's Debian run, and the Windows registry and `drift.old` behavior from a run under Wine. A real Windows machine has not run `drift install`, and package-manager awareness is absent: it does not refuse to run inside a Homebrew or Chocolatey tree.
- `serve` is a development server for a local Studio build. It binds to loopback, has no range requests, compression or directory listing, reads each file whole, and has no TLS on Windows. The embedded-asset option (a CLI that carries Studio inside it) is not built.
- No release has been published. The asset names, the `brew` and `choco` commands and the install scripts' default download URL are what the workflows and templates say, and they have not been exercised against GitHub.
