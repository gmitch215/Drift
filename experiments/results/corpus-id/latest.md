# Corpus Identification, Dev Half

**This is the dev half. It is a development signal for tuning the extractor and the rules and is not the held-out result.** The held-out half is scored once per Drift version.

Question: given a real closed issue as filed, does `drift triage` name the cause that the maintainers later gave? Method, definitions and the bars are in `experiments/corpus-id/README.md`. Drift sees only the issue title and body as filed.

| | |
|---|---|
| Drift | drift version 1.0.0 (linux), binary sha256 `04aa1331550695aa85812dac3e65eec2af689a00d51845e9a2875e106a849e39` |
| tree | HEAD `a0dbff76` plus uncommitted changes |
| host | lane `r2` on paisley-park (container) |
| regenerate | `experiments/corpus-id/score.sh --half dev` |

## What Drift Prints

Share of items where `drift triage` printed anything. `fact (not os, signature)` counts a fact other than `os.*` and `run:signature`.

| group | n | any fact | fact (not os, signature) | any rule | draft rule | rule or draft | anything |
|---|---|---|---|---|---|---|---|
| all 164 labelled items | 164 | 95/164 (0.579; 0.503-0.652) | 65/164 (0.396; 0.325-0.473) | 7/164 (0.043; 0.021-0.085) | 3/164 (0.018; 0.006-0.052) | 10/164 (0.061; 0.033-0.109) | 95/164 (0.579; 0.503-0.652) |
| env-cause, named attribute | 21 | 17/21 (0.810; 0.600-0.923) | 15/21 (0.714; 0.500-0.862) | 3/21 (0.143; 0.050-0.346) | 2/21 (0.095; 0.027-0.289) | 5/21 (0.238; 0.106-0.451) | 17/21 (0.810; 0.600-0.923) |
| env-cause, unnamed | 7 | 4/7 (0.571; 0.250-0.842) | 3/7 (0.429; 0.158-0.750) | 0/7 (0.000; 0.000-0.354) | 0/7 (0.000; 0.000-0.354) | 0/7 (0.000; 0.000-0.354) | 4/7 (0.571; 0.250-0.842) |
| code-defect | 67 | 40/67 (0.597; 0.477-0.706) | 25/67 (0.373; 0.267-0.493) | 3/67 (0.045; 0.015-0.124) | 1/67 (0.015; 0.003-0.080) | 4/67 (0.060; 0.023-0.144) | 40/67 (0.597; 0.477-0.706) |
| user-error | 8 | 6/8 (0.750; 0.409-0.929) | 4/8 (0.500; 0.215-0.785) | 0/8 (0.000; 0.000-0.324) | 0/8 (0.000; 0.000-0.324) | 0/8 (0.000; 0.000-0.324) | 6/8 (0.750; 0.409-0.929) |
| unclear | 61 | 28/61 (0.459; 0.340-0.583) | 18/61 (0.295; 0.196-0.419) | 1/61 (0.016; 0.003-0.087) | 0/61 (0.000; 0.000-0.059) | 1/61 (0.016; 0.003-0.087) | 28/61 (0.459; 0.340-0.583) |

Fact keys printed (items with at least one fact on the key family): `os` 123, `run:signature` 35, `tool.java` 15, `tool.python` 12, `tool.php` 10, `tool.node` 9, `runtime` 7, `ci` 7, `tool.go` 6, `tool.rust` 5, `tool.cc` 4, `tool.dotnet` 3, `tool.kotlin` 2, `browser` 2, `tool.git` 1, `tool.npm` 1, `env.TZ` 1.

## Tier B Measures on Env-Cause Items

Primary set: `env-cause` with a named attribute, a quote that verifies, and no rule that cites the thread. 28 env-cause items, 21 with a named attribute, 0 quote-invalid (counted `unclear`), 0 contaminated, 21 primary. Wilson 95% intervals.

| set | n | fact | fact-exact | rule | rule-exact | named | cause | value |
|---|---|---|---|---|---|---|---|---|
| primary | 21 | 3/21 (0.143; 0.050-0.346) | 3/21 (0.143; 0.050-0.346) | 1/21 (0.048; 0.008-0.227) | 1/21 (0.048; 0.008-0.227) | 3/21 (0.143; 0.050-0.346) | 2/21 (0.095; 0.027-0.289) | 2/21 (0.095; 0.027-0.289) |
| primary, attribute in Drift's key table | 10 | 3/10 (0.300; 0.108-0.603) | 3/10 (0.300; 0.108-0.603) | 1/10 (0.100; 0.018-0.404) | 1/10 (0.100; 0.018-0.404) | 3/10 (0.300; 0.108-0.603) | 2/10 (0.200; 0.057-0.510) | 2/10 (0.200; 0.057-0.510) |
| primary, attribute outside the key table | 11 | 0/11 (0.000; 0.000-0.259) | 0/11 (0.000; 0.000-0.259) | 0/11 (0.000; 0.000-0.259) | 0/11 (0.000; 0.000-0.259) | 0/11 (0.000; 0.000-0.259) | 0/11 (0.000; 0.000-0.259) | 0/11 (0.000; 0.000-0.259) |
| named incl. contaminated (sensitivity) | 21 | 3/21 (0.143; 0.050-0.346) | 3/21 (0.143; 0.050-0.346) | 1/21 (0.048; 0.008-0.227) | 1/21 (0.048; 0.008-0.227) | 3/21 (0.143; 0.050-0.346) | 2/21 (0.095; 0.027-0.289) | 2/21 (0.095; 0.027-0.289) |
| stratum large | 9 | 2/9 (0.222; 0.063-0.547) | 2/9 (0.222; 0.063-0.547) | 1/9 (0.111; 0.020-0.435) | 1/9 (0.111; 0.020-0.435) | 2/9 (0.222; 0.063-0.547) | 1/9 (0.111; 0.020-0.435) | 2/9 (0.222; 0.063-0.547) |
| stratum small | 12 | 1/12 (0.083; 0.015-0.354) | 1/12 (0.083; 0.015-0.354) | 0/12 (0.000; 0.000-0.242) | 0/12 (0.000; 0.000-0.242) | 1/12 (0.083; 0.015-0.354) | 1/12 (0.083; 0.015-0.354) | 0/12 (0.000; 0.000-0.242) |
| ecosystem container-ci | 4 | 0/4 (0.000; 0.000-0.490) | 0/4 (0.000; 0.000-0.490) | 0/4 (0.000; 0.000-0.490) | 0/4 (0.000; 0.000-0.490) | 0/4 (0.000; 0.000-0.490) | 0/4 (0.000; 0.000-0.490) | 0/4 (0.000; 0.000-0.490) |
| ecosystem js | 3 | 1/3 (0.333; 0.061-0.792) | 1/3 (0.333; 0.061-0.792) | 1/3 (0.333; 0.061-0.792) | 1/3 (0.333; 0.061-0.792) | 1/3 (0.333; 0.061-0.792) | 1/3 (0.333; 0.061-0.792) | 1/3 (0.333; 0.061-0.792) |
| ecosystem jvm | 5 | 0/5 (0.000; 0.000-0.434) | 0/5 (0.000; 0.000-0.434) | 0/5 (0.000; 0.000-0.434) | 0/5 (0.000; 0.000-0.434) | 0/5 (0.000; 0.000-0.434) | 0/5 (0.000; 0.000-0.434) | 0/5 (0.000; 0.000-0.434) |
| ecosystem php | 6 | 2/6 (0.333; 0.097-0.700) | 2/6 (0.333; 0.097-0.700) | 0/6 (0.000; 0.000-0.390) | 0/6 (0.000; 0.000-0.390) | 2/6 (0.333; 0.097-0.700) | 1/6 (0.167; 0.030-0.564) | 1/6 (0.167; 0.030-0.564) |
| ecosystem python | 2 | 0/2 (0.000; 0.000-0.658) | 0/2 (0.000; 0.000-0.658) | 0/2 (0.000; 0.000-0.658) | 0/2 (0.000; 0.000-0.658) | 0/2 (0.000; 0.000-0.658) | 0/2 (0.000; 0.000-0.658) | 0/2 (0.000; 0.000-0.658) |
| ecosystem rust | 1 | 0/1 (0.000; 0.000-0.793) | 0/1 (0.000; 0.000-0.793) | 0/1 (0.000; 0.000-0.793) | 0/1 (0.000; 0.000-0.793) | 0/1 (0.000; 0.000-0.793) | 0/1 (0.000; 0.000-0.793) | 0/1 (0.000; 0.000-0.793) |

Mismatch control (each item scored against another item's attribute, 21 pairs): fact 2/21 (0.095; 0.027-0.289); rule 0/21 (0.000; 0.000-0.155); named 1/21 (0.048; 0.008-0.227); cause 0/21 (0.000; 0.000-0.155).

## Null Baseline and Precision

Items with no named cause attribute (`code-defect`, `user-error`, `unclear`, unnamed env-cause). A rule or draft here is a coincidence with respect to a cause attribute.

| set | n | any fact | any rule | draft rule | rule or draft |
|---|---|---|---|---|---|
| non-named items | 143 | 78/143 (0.545; 0.464-0.625) | 4/143 (0.028; 0.011-0.070) | 1/143 (0.007; 0.001-0.039) | 5/143 (0.035; 0.015-0.079) |
| code-defect | 67 | 40/67 (0.597; 0.477-0.706) | 3/67 (0.045; 0.015-0.124) | 1/67 (0.015; 0.003-0.080) | 4/67 (0.060; 0.023-0.144) |
| user-error | 8 | 6/8 (0.750; 0.409-0.929) | 0/8 (0.000; 0.000-0.324) | 0/8 (0.000; 0.000-0.324) | 0/8 (0.000; 0.000-0.324) |

Precision of `cause` (dev signal for bar 1): items with a non-empty cause set and a verdict other than `unclear`: 9; the cause set holds the labelled attribute in 2/9 (0.222; 0.063-0.547).

Coverage of `cause` on env-cause items with a named attribute (primary): 2/21 (0.095; 0.027-0.289).

## Three-Way Classification

Truth: environment (env-cause), not environment (code-defect, user-error) or unclear. Drift's answer: names a cause (a matched rule or draft rule), says not environment, or cannot tell. The binary under test has no output that says not environment, so that column is 0 by construction: this is the baseline. `names a cause` is split into the labelled attribute being in the cause set and not.

| truth | n | names the labelled cause | names another cause | says not environment | cannot tell |
|---|---|---|---|---|---|
| environment, named attribute | 21 | 2 | 3 | 0 | 16 |
| environment, unnamed attribute | 7 | 0 | 0 | 0 | 7 |
| not environment: code-defect | 67 | 0 | 4 | 0 | 63 |
| not environment: user-error | 8 | 0 | 0 | 0 | 8 |
| unclear | 61 | 0 | 1 | 0 | 60 |
| all | 164 | 2 | 8 | 0 | 154 |

Three-class accuracy: 62/164 (0.378; 0.307-0.454). Per truth class, correct: environment 2/28 (0.071; 0.020-0.226), not environment 0/75 (0.000; 0.000-0.049), unclear 60/61 (0.984; 0.913-0.997).

Bars (README, held-out only; dev shown for reference): precision of `cause` needs a point estimate of at least 0.85 and a lower bound of at least 0.65; three-class accuracy needs at least 0.80 and 0.72.

## Env-Cause Items and the Gap

One row per `env-cause` dev item. Gap phrases: `hit`; `no extractor for it` (Drift prints no fact of that family on any dev item and it is not in the documented key table); `no rule for it` (a fact of the family is printed, the catalog has no rule on the key); `no rule for this change` (a fact of the key is printed and the catalog has rules on the key, none about this change); `wording unmatched` (the extractor exists, the issue text names the tool or the value, no fact printed); `sensor not captured (not in the issue text)` (neither the labelled value nor the tool name occurs in the issue title and body); `labeller gave no attribute`.

| item | attribute | before to after | Drift printed | gap | note |
|---|---|---|---|---|---|
| PicGo/PicGo-Core#142 | unnamed | - | run:signature only | labeller gave no attribute |  |
| TasmoAdmin/TasmoAdmin#21 | tool.tasmota.version | 5.10.0j to 5.10.0c | nothing | no extractor for it |  |
| TasmoAdmin/TasmoAdmin#824 | tool.php.version | 8.1 to 7.4.33 | facts os.arch=x86_64, os.name=linux | wording unmatched | hand-read: the version is in a Composer error, `You are running 7.4.33` |
| TasmoAdmin/TasmoAdmin#875 | unnamed | - | facts tool.php.version=7.0 | labeller gave no attribute |  |
| actions/runner#258 | tool.powershell.version | - | facts ci.provider=github-actions, ci.runner.label=ubuntu-18.04, ci.runner.label=windows-latest, os.name=windows, tool.python.version=3.5, tool.python.version=3.6; draft on tool.python.version | no extractor for it |  |
| actions/runner#334 | runtime.container.image | quay.io/pypa/manylinux2010_x86_64 to quay.io/pypa/manylinux2014_x86_64 | facts os.arch=x86_64, tool.node.version=12 | wording unmatched | hand-read: two image names in prose and a code span, no `image:` or `FROM` line |
| datatags/MobChipLite#5 | tool.paper.version | - | facts tool.java.version=534; rules jdk-11-java-ee-modules-removed | no extractor for it |  |
| docker/cli#2552 | unnamed | 2020-05-27T20:24:00 to 2020-05-27T20:24:00Z | facts os.arch=x86_64, os.name=macos, tool.go.version=1.12.17 | labeller gave no attribute |  |
| docker/cli#4340 | tool.docker.version | - | facts runtime.container.image=base, tool.dotnet.version=6.0 | no extractor for it |  |
| gmitch215/MobChip#78 | runtime.platform | - | facts tool.java.version=981 | sensor not captured (not in the issue text) | hand-read: `Server Version: Paper 1.20.1` is a form field, no key reads it |
| gustavosett/Windows-11-Clipboard-History-For-Linux#127 | unnamed | - | nothing | labeller gave no attribute |  |
| investbrainapp/investbrain#150 | tool.php.version | 8.4 to 8.3 | facts tool.php.version=185, tool.php.version=219; draft on tool.php.version | hit |  |
| javierbrea/eslint-plugin-boundaries#294 | config.boundaries.elements.pattern | entities/packages/* to entities/**/* | facts tool.node.version=16.17.1, tool.npm.version=8.15.0 | no extractor for it |  |
| jpenilla/MiniMOTD#102 | unnamed | - | nothing | labeller gave no attribute |  |
| jpenilla/MiniMOTD#104 | unnamed | - | nothing | labeller gave no attribute |  |
| jpenilla/MiniMOTD#112 | runtime.platform | Paper to Spigot | facts tool.java.version=379; rules jdk-24-security-manager-disabled | wording unmatched | hand-read: platform appears only in jar names inside a stack trace |
| jpenilla/MiniMOTD#211 | tool.paper.version | 1.21.1 build 76 to 1.21-38 | facts tool.java.version=21 | no extractor for it |  |
| jpenilla/MiniMOTD#53 | runtime.platform | Waterfall to Travertine | nothing | wording unmatched | hand-read: platform appears in a log line, `This server is running Travertine version` |
| konsoletyper/teavm#407 | unnamed | 0.1 to 0.2.0-dev-70 | facts tool.java.version=8 | labeller gave no attribute |  |
| marcantondahmen/automad#151 | limits.disk-quota | - | nothing | no extractor for it |  |
| microsoft/vscode#303341 | tool.electron.version | 42.7.0 to ? | facts os.arch=aarch64, os.name=macos | no extractor for it |  |
| moby/moby#24636 | tool.skopeo.version | 0.1.3 to ? | facts os.arch=x86_64, os.name=linux, os.release.ID=centos, os.release.VERSION_ID=7, tool.go.version=1.4.2 | no extractor for it |  |
| mottosso/Qt.py#465 | project.python.PyQt6 | PyQt5 to PyQt6 | nothing | wording unmatched | hand-read: PyQt5 and PyQt6 named in prose, no install command or build file |
| mrusse/soularr#54 | env.SLSKD_UMASK | 000 to 022 | facts env.TZ=America/New_York, runtime.container.image=youegraillot/lidarr-on-s | no extractor for it |  |
| nodejs/node#62022 | tool.node.version | 25.0.0 to 25.5.0 | facts os.name=macos, tool.node.version=25.0.0; rules node-22-import-assertions-dropped | hit |  |
| php/php-src#10119 | tool.openssl.version | - | facts os.name=windows, os.version=10, tool.php.version=8.0 | no extractor for it |  |
| php/php-src#8150 | tool.php.version | 8.0.0 to 7.4.4 | facts os.name=macos, os.version=10.13.6, tool.php.version=7.4.4 | no rule for this change (55 catalog rules read the key) | hand-read: PHP versions appear in a form field; no rule on the 8.0 bcmath locale change |
| rust-lang/rust#105167 | ci.runner.label | macos-11 to macos-latest | facts os.arch=aarch64, os.name=macos, tool.rust.version=1.51.0 | sensor not captured (not in the issue text) | hand-read: the runner label change is stated in a later comment, not the issue |

Gap counts: no extractor for it 11, labeller gave no attribute 7, wording unmatched 5, sensor not captured (not in the issue text) 2, hit 2, no rule for this change (55 catalog rules read the key) 1.

## Tier A (Weak Truth), Dev

Weak truth: the class comes from the files the closing pull request changed, not from what a person read. A pull request that bumps a pin can fix a code bug. 6253 sampled items with a Drift output; Tier A is never pooled with Tier B.

Classes: code-change 6103, unknown 89, env-or-config-change 61.

| class | n | any fact | fact (not os, signature) | any rule | draft rule | rule or draft | anything |
|---|---|---|---|---|---|---|---|
| env-or-config-change | 61 | 43/61 (0.705; 0.581-0.804) | 16/61 (0.262; 0.168-0.384) | 1/61 (0.016; 0.003-0.087) | 0/61 (0.000; 0.000-0.059) | 1/61 (0.016; 0.003-0.087) | 43/61 (0.705; 0.581-0.804) |
| code-change | 6103 | 3360/6103 (0.551; 0.538-0.563) | 1865/6103 (0.306; 0.294-0.317) | 86/6103 (0.014; 0.011-0.017) | 16/6103 (0.003; 0.002-0.004) | 102/6103 (0.017; 0.014-0.020) | 3360/6103 (0.551; 0.538-0.563) |
| all sampled | 6253 | 3461/6253 (0.553; 0.541-0.566) | 1919/6253 (0.307; 0.296-0.318) | 87/6253 (0.014; 0.011-0.017) | 16/6253 (0.003; 0.002-0.004) | 103/6253 (0.016; 0.014-0.020) | 3461/6253 (0.553; 0.541-0.566) |

| set | n | fact | fact-exact | rule | rule-exact | named | cause |
|---|---|---|---|---|---|---|---|
| env-or-config-change, one derived attribute | 26 | 1/26 (0.038; 0.007-0.189) | 1/26 (0.038; 0.007-0.189) | 0/26 (0.000; 0.000-0.129) | 0/26 (0.000; 0.000-0.129) | 1/26 (0.038; 0.007-0.189) | 0/26 (0.000; 0.000-0.129) |
|   in Drift's key table | 26 | 1/26 (0.038; 0.007-0.189) | 1/26 (0.038; 0.007-0.189) | 0/26 (0.000; 0.000-0.129) | 0/26 (0.000; 0.000-0.129) | 1/26 (0.038; 0.007-0.189) | 0/26 (0.000; 0.000-0.129) |
|   outside the key table | 0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 |

Items with a non-empty cause set: 93; of those, `env-or-config-change` with the derived attribute in the cause set: 0/93 (0.000; 0.000-0.040).

Derived attributes: `project.cargo.deno_core` 3, `project.cargo.dprint-plugin-typescript` 3, `runtime.container.image` 2, `ci.runner.label` 2, `project.cargo.deno_graph` 2, `project.npm.distro` 1, `project.npm.node` 1, `project.npm.@vscode/policy-watcher` 1, `project.cargo.deno_task_shell` 1, `project.cargo.deno_npm` 1, `project.cargo.swc_bundler` 1, `project.cargo.rusty_v8` 1, `project.cargo.zeromq` 1, `project.cargo.deno_semver` 1, `project.cargo.v8` 1, `project.cargo.deno_config` 1, `project.cargo.twox-hash` 1, `project.cargo.tokio` 1, `project.cargo.arboard` 1.

### Weak Rule Against the Labels

The Tier A class rule applied to the closing pull request of each labelled Tier B item.

| Tier B verdict | env-or-config-change | code-change | pull request not readable | closing commit | no closing reference |
|---|---|---|---|---|---|
| env-cause | 0 | 2 | 0 | 1 | 25 |
| code-defect | 1 | 8 | 0 | 9 | 49 |
| user-error | 0 | 1 | 0 | 0 | 7 |
| unclear | 0 | 10 | 0 | 4 | 47 |

Of the env-cause items with a readable closing pull request, 0/2 (0.000; 0.000-0.658) are classed `env-or-config-change`; of the code-defect items, 8/9 (0.889; 0.565-0.980) are classed `code-change`.

## Reproduction

Drift outputs are `drift triage --json` over `title + blank line + body` of each item, produced by `lane-run.sh` inside lane `r2` (6417 outputs, sha256 in `dev-output-hashes.txt`). Steps: `corpus_id.py drift-in --half dev --tier-a`; sync `runs/dev/drift-in` and `lane-run.sh` into the lane with `paisley-lane.sh sync`; `DRIFT=/work/bin/drift lane-run.sh runs/dev/drift-in/b runs/dev/drift-out/b` (and `/a`); copy `drift-out` back; `score.sh --half dev`.

## Limits

Dev half only. n is small: 21 env-cause items with a named attribute, so every interval above is wide. Labels come from one model labeller per thread; the quote check is the only mechanical control. Only the first 100 comments of a thread were fetched. Tier A weak truth is a statement about the files a pull request touched. `upgrade-check` was not run. The gap phrases are rules over the output and the issue text, with a few hand-read notes marked as such.
