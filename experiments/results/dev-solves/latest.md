# Dev Solves Scored

Question: when `drift solve` returns a verdict on a dev scenario, how often does it name the injected cause?

Current result (final scoring, in-sample): 92 of 112 cause-bearing scenarios resolved (82.1%, Wilson 74.0% to 88.1%), 58 CONFIRMED all correct and none wrong, 10 of 10 controls name no cause, 2 genuine STUCK and 20 NEEDS; nine of the resolutions follow fixes to scenario defects. The sections below keep the earlier scorings in order, starting with the first.

Verdict (first scoring): 28 of 28 CONFIRMED verdicts name the injected cause (Wilson 95% interval 87.9% to 100%), and no control drew a verdict. The solver resolves 43 of the 105 scenarios that have a cause (41.0%). The ranker puts the cause first in 46 of the same 105 and the dimension prior in 50. Precision holds on this set; coverage is the open problem. After the Lab fixes below, the solver resolves 54 of 105 (51.4%) and still has 0 wrong among 30 CONFIRMED verdicts.

## Method

`bench solves` reads `certificate.json` from each of 115 case directories (one `drift solve` run per dev scenario, solved on paisley-park) and compares the verdict with the scenario's class and injected cause path in `bench/data/scenarios.json`. The ranker and dimension-prior ranks come from `probed.scenarios` in `bench/data/calibration/evaluation.json`. The 115 cases are the final solve of each id: 66 from the re-solve with the fixed Lab and 49 older scenarios. The seven file-mount dev scenarios have no case. The scenarios are synthetic, the team tuned the engine against this dev split, and each scenario ran once. The certificates are in `experiments/dev-solves/certificates/`; `bench/data/solves/dev.json` is the full output with one row per case.

A CONFIRMED verdict is correct when its minimal set is exactly the injected cause. A bundle verdict holds the cause when the cause is among its members. On a control, STUCK is correct.

## Results

| class | n | CONFIRMED correct | CONFIRMED wrong | bundle holds (minimal set names the cause) | NARROWED | STUCK |
| --- | --- | --- | --- | --- | --- | --- |
| single | 42 | 15 | 0 | 5 (5) | 0 | 22 |
| bundle | 33 | 0 | 0 | 15 (9) | 1, misses the cause | 17 |
| decoy | 19 | 11 | 0 | 0 | 0 | 8 |
| intermittent | 11 | 2 | 0 | 1 (1) | 0 | 8 |
| control | 10 | 0 | 0 | 0 | 0 | 10, all correct |
| all | 115 | 28 | 0 | 21 (15) | 1 | 65 |

| rate | count | denominator | Wilson 95% |
| --- | --- | --- | --- |
| CONFIRMED correct | 28 | 28 CONFIRMED verdicts | 87.9% to 100% |
| controls STUCK | 10 | 10 controls | 72.2% to 100% |
| resolved (CONFIRMED correct, or a bundle whose minimal set names the cause) | 43 | 105 scenarios with a cause | 32.0% to 50.5% |
| some verdict holds the cause | 49 | 105 scenarios with a cause | 37.4% to 56.2% |
| ranker top-1 | 46 | 105 | 34.7% to 53.4% |
| ranker top-3 | 67 | 105 | 54.3% to 72.4% |
| dimension prior top-1 | 50 | 105 | 38.3% to 57.1% |
| dimension prior top-3 | 67 | 105 | 54.3% to 72.4% |

Of the 43 resolved scenarios the ranker had the cause first in 30; of the 62 unresolved ones, in 16.

Trials per case, summed over the experiment arms: CONFIRMED median 44 (range 44 to 112), bundle verdicts 58 (44 to 128), the NARROWED case 86, STUCK 30 (0 to 60; the 0 is a failed image build).

The 65 STUCK verdicts, by the first clause of the certificate statement for the 55 that have a cause: 25 the failure does not reproduce in the container built from the failing capsule, 15 the passing configuration fails as often as the failing one, 10 the experiment is void (the treatment capsule does not show the requested `os.release.VERSION_ID`; all bundle scenarios), 3 no trial count up to 30 reaches the target power, 2 executor failures. The 10 controls are the other STUCK verdicts. By the certificate's reason field, STUCK counts 44 not-reproduced, 12 executor, 6 uncontrollable and 3 budget. The injected causes of the 55 cover 30 paths; the most frequent are `tool.coreutils.flavor` (7), `limits.nofile` (5) and `tool.shell.flavor` (4).

Six bundle verdicts hold the cause without naming it in the minimal set: `build-npm-sbom-plain-1`, `os-glibc-floor-1`, `os-version-gate-2`, `userland-coreutils-new-flag-1`, `userland-coreutils-new-flag-3` and `userland-cp-update-2`. The one NARROWED verdict, `build-npm-subcommand-2`, names `tool.node.version` where the cause is `tool.npm.version`.

The certificate of `env-pip-cache-dir-1` records an executor failure from an image that was removed mid-run. Its re-solve ended STUCK with 0 of 30 trials failing, and that certificate is not kept. The outcome is the same (STUCK), so only the STUCK breakdown above differs by one row.

## After the Lab Fixes

Each of the 55 stuck scenarios was classified from its certificate, arm Dockerfiles, run scripts and trial output (the table is in `TECHNICAL_REPORT.md`, Dev Solves Scored). Three Drift defects were fixed:

- The arm for an alpine capsule used the floating `alpine:3.22`, whose point release differs from the capsule's 3.22.6, so the experiment was void. `reproduce` now builds `alpine:<point release>` when `ImageTags` lists it (10 scenarios).
- A capsule taken inside the gcc image records python from the distribution, and the base came from the first toolchain in table order, so the arms had no `gcc` or `make`. A toolchain whose official image variable (`GCC_VERSION`, `PYTHON_VERSION`, and so on) is in the capsule now wins before table order (5 scenarios).
- `env.XDG_CACHE_HOME` was classed as host identity and its value `~/xdg` as a host path. The four XDG base directory variables and values under `~`, `/tmp` and `/var/tmp` are mirrored (2 scenarios).

`ImageTags.kt` was regenerated with the alpine tag shape added. A static comparison of the `reproduce` output of the committed binary and the fixed one over both capsules of all 122 dev scenarios changed 20 `FROM` lines in 15 scenarios, all of them in the two groups above, and left 224 unchanged.

The 21 scenarios of the first four classes in the report (the three fixes above and the four that earlier image rows already covered) were solved again on paisley-park with Linux executables built from the fixed tree, plus `runtime-rust-newer-std-1` and `runtime-go-loopvar-1` as checks on the environment change. `bench solves` scored the 115 certificates with those replacements into `bench/data/solves/dev-after.json`.

| class | n | resolved before | resolved after | cause held before | cause held after |
| --- | --- | --- | --- | --- | --- |
| single | 42 | 20 (33.4% to 62.3%) | 24 (42.2% to 70.9%) | 20 | 24 |
| bundle | 33 | 9 (15.1% to 44.2%) | 16 (32.5% to 64.8%) | 15 | 30 (76.4% to 96.9%) |
| decoy | 19 | 11 | 11 | 11 | 11 |
| intermittent | 11 | 3 | 3 | 3 | 3 |
| all | 105 with a cause | 43 (32.0% to 50.5%) | 54 (42.0% to 60.8%) | 49 (37.4% to 56.2%) | 68 (55.3% to 73.2%) |

CONFIRMED verdicts went from 28 to 30 and none is wrong (88.6% to 100%). Verdicts of any kind went from 50 to 71 of 105 (47.6% to 67.6%, Wilson 58.2% to 75.8% after). STUCK scenarios with a cause went from 55 to 34, and the void experiments from 10 to 0. Two of the new bundle verdicts (`build-make-feature-1` and `-2`) name `tool.cc.version` where the injected cause, `tool.make.version`, is not in the capsule. The two checks kept their verdicts (CONFIRMED EFFECT (bundle), same minimal set, same trial counts). The controls were not solved again.

The fixes were written after reading these certificates, so the after numbers are in-sample, and each scenario ran once.

## After Resource Limit Capture

The eight `limits-*` scenarios that were STUCK after the Lab fixes (`limits-core-1`, `limits-core-2`, `limits-fsize-2`, `limits-nofile-1`, `limits-nofile-2`, `limits-nofile-decoy-1`, `limits-nofile-decoy-2`, `limits-nofile-pipe-1`) had no `limits.*` attribute in their capsules, so no arm got a `--ulimit` flag. Capture now reads `/proc/self/limits` on Linux. The 16 capsules were recorded again on paisley-park with the new Linux x64 executable, and each scenario was solved once (`run-solve.sh --jobs 2`).

| measure | before | after |
| --- | --- | --- |
| the eight scenarios resolved | 0 of 8 (all STUCK, the failure did not reproduce) | 8 of 8 |
| the eight scenarios CONFIRMED correct, wrong | 0, 0 | 8, 0 (Wilson 67.6% to 100%) |
| all 105 scenarios with a cause, resolved | 54 (42.0% to 60.8%) | 62 (49.5% to 68.0%) |
| CONFIRMED correct / wrong, all dev | 30 / 0 | 38 / 0 (90.8% to 100%) |

Each CONFIRMED verdict names the injected cause alone (`limits.nofile` five times, `limits.core` twice, `limits.fsize` once); 7 of 7 trials fail with the cause set and 0 of 7 without it, in 44 trials (72 for the two decoy scenarios, which also change a memory limit and a log level). No decoy was named. The scenarios are in-sample, since the capture was added after their failures were read, and each ran once. `bench/data/solves/dev-limits.json` (sha256 `8b8a8d6f590db03e36829aea6df0fa3117b3bd1f49facb28fef2cc4d30613a9c`) is regenerated byte for byte from `certificates-limits/` by `experiments/dev-solves/run.sh --limits`.

## After Hosts, Name Resolution and CPU Affinity

Capture now records `network.hosts.names`, `network.nsswitch.hosts`, `network.interfaces`, `cpu.affinity` and `locale.installed`, and the Lab sets them in an arm (`--add-host`, an `nsswitch.conf` step, `--network none`, `--cpuset-cpus`). Eleven scenarios changed state: the three `network-proxy` scenarios and `network-proxy-lowercase-1`, `locale-setlocale-2`, `network-hosts-2`, `network-interface-missing-1` and `cpu-count-pool-2` (stuck to CONFIRMED), and `network-hosts-stale-1`, `-2` and `network-nsswitch-order-1` (no case to CONFIRMED). `dev-x1-a.json` scores 38 scenarios solved again with the final binaries: these eleven, all 10 controls and all 19 decoys (`network-proxy-decoy-1` and `-2` are in two of those groups).

| measure | before | after |
| --- | --- | --- |
| scenarios with a cause, resolved (112: 105 solved plus 7 with no case) | 62 (Wilson 46.1% to 64.2%) | 73 (56.0% to 73.4%) |
| CONFIRMED correct / wrong, all dev | 38 / 0 | 49 / 0 (92.7% to 100%) |
| controls STUCK | 10 of 10 | 10 of 10 |
| the 38 scenarios of `dev-x1-a.json`: CONFIRMED correct / wrong | | 24 / 0 (86.2% to 100%) |

By class, resolved before and after: single 30 and 38 of 49, decoy 13 and 15 of 19, intermittent 3 and 4 of 11, bundle 16 and 16 of 33. Four scenarios of the 38 are STUCK as before: `browser-css-support-1`, `browser-viewport-width-2`, `kernel-max-map-count-1` and `-2`, which a container cannot vary.

The first pass over the controls and decoys showed `locale-node-collation-decoy-1` and `-2` as a CONFIRMED EFFECT bundle of `env.LANG` and `locale.installed`, because the treatment arm generates the locale the control arm lacks. `Solve` now ignores `locale.installed` in the measured diff of a locale edit, and the final pass confirms `env.LANG` alone. The fixes were chosen after reading the dev certificates, so these numbers are in-sample, and each scenario ran once.

## After Member Experiments and NEEDS

`drift solve` changed in three ways. The minimal-set search keeps attributes that one intervention sets together as one group. A bundle verdict runs one experiment per member that an intervention can set alone, and lists the member as `sufficient` (set alone on the passing side, the failure follows) or `necessary` (reverted alone on the failing side, the failure goes), when the capture inside the arm shows that member and nothing else changed. A solve that cannot settle the cause now ends `NEEDS` with steps (`kind`, `text`, `command`, `reply`) instead of `STUCK`; `STUCK` is kept for a certificate with no step. `bench solves` counts a bundle as naming the cause when the cause is in its minimal set, `necessary` or `sufficient`, and a verdict that names no cause (STUCK or NEEDS) as correct on a control.

49 scenarios were solved again with Linux executables built from the changed tree (`certificates-p8/`, scored in `bench/data/solves/dev-p8.json`; the file now also holds the scenario-defect solves below): the 15 bundle scenarios that held the cause without naming it or ended NARROWED, all 11 intermittent scenarios (the solve script passes `--pilot 30`), all 10 controls, and the other scenarios that had ended STUCK. The 69 other cases keep their earlier certificates; no CONFIRMED certificate has forward flips that one intervention sets together, and member experiments run only after a bundle verdict.

| class | cause-bearing | resolved before | resolved after |
| --- | --- | --- | --- |
| single | 49 | 38 (Wilson 64.1% to 87.0%) | 38 |
| bundle | 33 | 16 (32.5% to 64.8%) | 25 (59.0% to 87.2%) |
| decoy | 19 | 15 (56.7% to 91.5%) | 15 |
| intermittent | 11 | 4 (15.2% to 64.6%) | 5 (21.3% to 72.0%) |
| all | 112 | 73 (56.0% to 73.4%) | 83 (65.3% to 81.3%) |

| verdict, over the 118 solved cases | before | after |
| --- | --- | --- |
| CONFIRMED | 49 | 49 |
| CONFIRMED EFFECT (bundle) | 39 | 40 |
| NARROWED | 2 | 2 |
| STUCK | 28 | 2 |
| NEEDS | 0 | 25 |

After the Lab changes alone the bar of 90 was not met (83). Seven Alpine scenarios (`userland-date-relative-3`, `userland-find-printf-1` and `-2`, `userland-gnu-long-options-1` to `-3`, `userland-grep-perl-1`) name `tool.coreutils.flavor` as `necessary` (7 of 7 failures with BusyBox, 0 of 7 with the GNU tools installed). `build-npm-sbom-plain-1` names `tool.npm.version` as `sufficient`, `os-version-gate-2` names its group, and `migration-python-copy-1` is resolved with the longer pilot. All 49 CONFIRMED verdicts of that set name the injected cause (Wilson 92.7% to 100%), all 10 controls end NEEDS and name no cause, and no decoy was named. The two STUCK verdicts, `cpu-limit-quota-basic-2` and `env-retries-flaky-plain-1`, have a pilot at the 30 trials per arm that the exact test allows and a failure rate (0.3125, 0.3) that leaves the power under 0.8, so no step exists. The fixes follow dev certificates, so the numbers are in-sample, and each scenario ran once.

### Scenario defects

Nine dev scenarios hid their cause from the capture and the arms, so there was nothing to flip: the four shell scenarios (both arms ran one image and differed only in the command line), the two version pins, the lockfile pin and the two checksum pins (the pin was a file inside the image). They were changed so that the cause is a fact the capture records and an arm can set: the green image links `/bin/sh` to bash and both arms run `sh` (`tool.sh.flavor`); the pins are workspace files that `capture --project` reads (`project.pins.node`, `project.pins.python`, `project.python.schemakit`, `project.checksums.vendor/<name>.txt`). Their capsules were recorded again with the current Linux x64 executable, and the nine were solved once with it (`certificates-p8/`, now 53 cases, `bench/data/solves/dev-p8.json` sha256 `1fd143812da31d2303082cf6ed6606d6e36473bd98571bccbf1346f53d0fbe33`). All nine end CONFIRMED on the injected cause alone. The four older score files changed only in the cause label of five cases (`tool.shell.flavor` to `tool.sh.flavor` for four, `deps.schemakit.version` to `project.python.schemakit` for one).

| class | cause-bearing | resolved before | resolved after |
| --- | --- | --- | --- |
| single | 49 | 38 | 46 (Wilson 83.5% to 97.9%) |
| bundle | 33 | 16 | 25 (59.0% to 87.2%) |
| decoy | 19 | 15 | 15 |
| intermittent | 11 | 4 | 6 (28.0% to 78.7%) |
| all | 112 | 73 (56.0% to 73.4%) | 92 (74.0% to 88.1%) |

92 of 112 is 82.1%, so the bar of 0.80 is met, partly by fixing scenario defects, and the fixes follow dev certificates, so the number is in-sample. Over the 122 solved cases: 58 CONFIRMED (all correct, 93.8% to 100%), 40 CONFIRMED EFFECT (bundle), 2 NARROWED, 20 NEEDS, 2 STUCK; the 10 controls end NEEDS and name no cause.

The first run of the member experiments showed a defect: the Reproduce step that installs the GNU tools on Alpine keyed on the capsule value, so the forward `set-os alpine` arm received them and passed (the seven scenarios ended NEEDS). It now installs them only for an attribute that an intervention set. Those certificates are not kept.

## Reproducibility

After `bench/data/solves/dev.json` was written (sha256 `19d43f90f353d5be427ecf8e4c1b6882a0eefdc0716c1eab2366796e162d010f`), `experiments/dev-solves/run.sh` scored the committed certificates again and the output matched it byte for byte. An earlier version of the file, written twice with the case directories given in opposite orders, was also identical both times.

## Versions

- Base commit `a0dbff76` (`master`), working tree dirty with other lanes' work.
- Kotlin 2.4.20, Gradle 9.8.0, JDK 21.0.12 in container `drift-cisim:3` on paisley-park, lane `e1`.
- Second run: the working tree on top of `a0dbff76` with the changes under After the Lab Fixes, Linux x64 and arm64 release executables built in lane `l1`, `run-solve.sh --jobs 2`, 23 scenarios solved. `dev-after.json` sha256 `04045b55063cdd1dc0726c5ef58bae38616bf7b0629f69190a4cc774876f21f4`, regenerated byte for byte by `experiments/dev-solves/run.sh --after`.
- Third run: the working tree on top of `a0dbff76` with the capture, Lab and `Solve` changes above, Linux x64 and arm64 release executables built in lane `x1`, `run-solve.sh --jobs 2`, 38 scenarios solved. `dev-x1-a.json` sha256 `01da3bbbee0954e0499b82304b5ed9d990f702ce7e169866a663d60646be082e`.
- Fourth run: the working tree on top of `a0dbff76` with the member experiments, `set-userland`, the NEEDS verdict and the other changes above, Linux x64 and arm64 release executables built in container `drift-lane-n1x`, `run-solve.sh --jobs 2`, 49 scenarios solved.
- The solves ran on paisley-park with the Linux release executables built from the tree, qemu-user-static for the aarch64 capsules, and `run-solve.sh --jobs 2`.

## Regenerate

```sh
experiments/dev-solves/run.sh
experiments/dev-solves/run.sh --after
experiments/dev-solves/run.sh --limits
experiments/dev-solves/run.sh --x1
experiments/dev-solves/run.sh --p8
```
