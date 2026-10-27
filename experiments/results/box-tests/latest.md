# Box tests for confirmed Kotlin findings

Question: do the confirmed findings K1, Y1, Y2 and 1^nan reproduce on jvm, linuxX64, js (Node) and wasmJs (Node) when written as compiler-shaped `box` tests?

Method: four files in `experiments/box-tests/tests/` each return `OK` or a failure string. The run script generates one package per file and a `main` that prints `box <name>: <result>`, then builds and runs the program on each target. The three relation tests compare two forms of one program. The 1^nan test checks the documented value `NaN`.

Run: 2026-10-26, Kotlin 2.4.20, commit `a0dbff76` (working tree had uncommitted changes), host paisley-park, lane `b2` (`drift-cisim:3`, JDK 21, Node 24). Accepted run `runs/20261026T224501Z` (`--offline`, `--max-workers=2`). Two earlier runs before the `--max-workers` fix (`runs/20261026T224219Z`, online; `runs/20261026T224335Z`, offline) gave the same cells.

## Result table

| test | jvm | linuxX64 | js | wasmJs |
| --- | --- | --- | --- | --- |
| k1-long-template | OK | OK | Fail 2^53+1: wrap template gave ok:9007199254740992, toString gave ok:9007199254740993 for 9007199254740993 | OK |
| pow-one-nan | OK | Fail 1.0.pow(NaN): got 1.0, expected NaN | OK | OK |
| y1-regex-ignore-case | OK | Fail pattern σ$ on final sigma: got false, expected true (as for sigma) | OK | Fail pattern σ$ on final sigma: got false, expected true (as for sigma) |
| y2-nan-negation | OK | Fail Double: constant 7ff8000000000000, runtime fff8000000000000 | OK | OK |

## Atlas cross-check

Each cell was compared with the transcript for the same target in `fixtures/atlas/`. Probe sources: `scan/src/common/main/dev/gmitch215/drift/scan/probe/kotlin/RegexProbes.kt` (ic-* lines use `^σ$` against `ς`), `FloatProbes.kt:168` (`neg-nan-raw` is `-Double.NaN`).

| test | target | run | Atlas line | agrees |
| --- | --- | --- | --- | --- |
| pow-one-nan | jvm | OK (NaN) | `jvm.txt:421` `1^nan = NaN` | yes |
| pow-one-nan | linuxX64 | Fail (1.0) | `linux.txt:421` `1^nan = 1.0` (also macos, linux-arm64, android, ios) | yes |
| pow-one-nan | wasmJs | OK (NaN) | `wasm.txt:421` `1^nan = NaN` | yes |
| pow-one-nan | js | OK | no `js-ir.txt` line for `1^nan` | not checked |
| y1-regex-ignore-case | jvm | OK | `jvm.txt:638` `ic-final-sigma = ok:true` | yes (anchored form) |
| y1-regex-ignore-case | linuxX64 | Fail | `linux.txt:638` `ic-final-sigma = ok:false` | yes (anchored form) |
| y1-regex-ignore-case | wasmJs | Fail | `wasm.txt:638` `ic-final-sigma = ok:false` | yes (anchored form) |
| y1-regex-ignore-case | js | OK | no `js-ir.txt` ic-* lines | not checked |
| y2-nan-negation | jvm | OK | `jvm.txt:186` `neg-nan-raw = fff8000000000000` | yes |
| y2-nan-negation | linuxX64 | Fail (constant 7ff8) | `linux.txt:186` `neg-nan-raw = 7ff8000000000000` | yes |
| y2-nan-negation | wasmJs | OK | `wasm.txt:186` `neg-nan-raw = fff8000000000000` | yes |
| y2-nan-negation | js | OK | no `js-ir.txt` line | not checked |
| k1-long-template | any | see below | no template probe in `fixtures/atlas/` | not checked |

The Atlas `ic-*` probes test the anchored form `^σ$` only. The Y1 test also fails `σ$` on Native and wasm. That pattern is not in the Atlas, so the Atlas does not confirm it. The Y1 draft lists `σ$` as false on Native; the run agrees with the draft, not with a transcript line.

K1 has no Atlas probe. The K1 draft (`build/kotlin-js-issues.md`) reports the same JS result: `D ok:9007199254740992` for the value 9007199254740993 under an inlined lambda. The JS failure here matches that draft line.

## Verdict

Every cell that the Atlas covers agrees with the Atlas. The JS column is not covered by the Atlas for any finding, so the JS cells rest on the K1 draft and the run itself. The K1 and Y1 JS results are the ones a maintainer would reproduce first.

## Commands

On paisley-park, lane `b2`:

```
NET=1 .claude/batch/paisley-lane.sh bg b2 box1 bash experiments/box-tests/run.sh --targets jvm,linuxX64,js,wasmJs
.claude/batch/paisley-lane.sh bg b2 box3 bash experiments/box-tests/run.sh --offline --targets jvm,linuxX64,js,wasmJs
```

Locally, the same script with `--check`, and a dry run against a fake Gradle launcher, to check the generated sources.

Re-run: `experiments/run.sh box-tests --paisley`.

Two fixes landed after the first two runs. The `command` line in `table.md` was empty because the argument loop shifted `$*` before the report; it now captures the arguments first. The Gradle calls did not pass `--max-workers=2`; they now do. Neither change touches the table cells.

Commit field: the lane tree has no `.git`, so `run.sh` printed `commit unknown`. The commit above was read from the Mac.
