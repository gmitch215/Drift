# Flagship Case A

The drangler Docker E2E suite passes on a developer machine and fails on the GitHub runner. This
case compares one capture on a local host with the CI capture of the same commit, `df6dce4`, the
commit of all three red runs in `fixtures/drangler`.

## Layout

- `case/`: the case directory written by `drift diagnose --frame environment --case`. Passing is
  the paisley-park capture of run `pp6`, failing is the CI capsule imported from run
  `37195797966`.
- `ruled-out.yml`: what the frozen CI logs already exclude, with the line each claim rests on.
  A case directory has no field for hand-written eliminations, and editing one breaks its hash
  chain, so the list sits beside it.
- `vacuous-green.sh` and `vacuous-green.txt`: which failing tests exist in the green logs.
- `local/<run>.txt`: per-file results of each local run, taken from the vitest output.

## Protocol

The suite is `bun run test:e2e:coverage` at `df6dce4`, wrapped in `drift run --tools`. The tree is
a copy of `git ls-files -co --exclude-standard`, installed with `bun install --frozen-lockfile`,
with the compose images pinned by digest as in the repository. The paisley-park host runs Ubuntu
24.04, x86_64, Node 24.21.0 (the runner's version) and bun 1.4.2. The suite and its wrangler
processes are pinned to 4 CPUs with `taskset`, the size of the runner; the compose containers are
not pinned. Each run starts from `docker compose down -v`.

## Local Results

Thirteen runs on paisley-park (`pp1` to `pp13`), listed in `local/`:

| What ran | Runs | `to-worker` pair fails | `modify` fails | `preview` fails |
| --- | ---: | ---: | ---: | ---: |
| full suite | 9 | 6 | 0 | 9 |
| `to-worker.spec.ts` alone | 2 | 0 | not run | not run |
| `preview.spec.ts` then `to-worker.spec.ts` | 1 | 1 | not run | 1 |
| `preview.spec.ts` alone | 1 | not run | not run | 1 |

In CI the `to-worker` pair failed in 3 of 3 red runs, the `modify` tests in 3 of 3 (2, 1 and 2 tests), and
`preview.spec.ts` failed with a different assertion than the local one (a mismatch while verifying
the duplicate, against a changed database dump after it).

The `to-worker` failure is the CI failure: `TypeError: fetch failed` caused by `write EPIPE`, and
`/rows answered 500`, at the same lines of `to-worker.spec.ts` and `helpers/compare.ts`. It
appears when `preview.spec.ts` ran earlier in the same vitest process (6 of 9 full runs and the
one two-file run) and did not appear in two runs of `to-worker.spec.ts` alone. A fresh checkout
runs the files in CI's order (`modify`, `workspace-clone`, `detector`, `preview`, `to-worker`); vitest
reorders them from its cached results afterwards, which is why the order differs in later runs.

A runner probe (`.github/diagnostics/runner-probe.mjs` in drangler) ran beside three of the runs.
In `pp3` and `pp5` it recorded 0% CPU steal, 0 throttled periods, clocksource `tsc`, a worst
event-loop lag of 2 ms and no loopback stall or error. The runs with the probe failed 3 of 3 and
the full runs without it 3 of 6, so the probe does not explain the failure.

The first macOS run (`mac1`) did not test anything: the Docker Desktop disk was full (59 GB, 105
unused anonymous volumes from 2024 to 2026 hold 19.5 GB) and mariadb stopped with `No space left on
device`. Five suites failed at compose start; `local/mac1.txt` records that.

## Mac Results

Once the Docker Desktop VM had space again (27 GB available), the suite ran on an Apple M2 Pro
(arm64, 16 GB) with Docker Desktop, Node 24.21.0 and bun 1.4.0. The tree is the committed `df6dce4`
tree, the `drift` release executable was linked from the Drift tree at 01:33, and the results cache
was removed before each run. The compose images are the pinned digests; `drupal` was pulled for this run.

| What ran | Runs | `to-worker` pair fails | `modify` fails | `preview` fails |
| --- | ---: | ---: | ---: | ---: |
| full suite | 3 | 0 | 2 | 3 |
| `to-worker.spec.ts` alone | 2 | 0 | not run | not run |

The three full runs are `local/mac-r1.txt`, `mac-r2.txt` and `mac-r3.txt`; the two single-file
runs are `local/mac-tw1.txt` and `mac-tw2.txt`. The `preview` failure is the same assertion the
paisley runs showed (`left the source byte-identical`). The `modify` test `requires simple_oauth,
uploads simple_oauth_21 and enables it` failed with `Error: Network connection lost.` in `mac-r2`
and `mac-r3`; no paisley run failed that test.

The `to-worker` pair passed in every Mac run.
Three full runs and two single-file runs are too few to measure a rate, so they do not change the
verdict: the failure reproduces on paisley-park in 6 of 9 full runs, and the Mac did not show it in
this set.

## Vacuous Green Check

`sh fixtures/cases/a/vacuous-green.sh` counts, for each of the four failing tests, the green runs
that list it as passed and the red runs that list it as failed. The two `to-worker` tests passed
in 4 and 5 of the 5 green runs, so a green run did execute them. The two `modify` tests are in no
green log. The `preview` file is in no green log either.

## Ranking and Next Experiment

`drift diagnose` finds 61 attribute differences, ranks 56 of them with the same support and names
no cause. One attribute differs in value, `tool.git.version` (2.43.0 locally, 2.55.0 on the
runner). The other 60 exist on one side only, because the CI capsule is read from a log and the local one is captured, so
most of the ranking compares capture surfaces. The planner's top experiment, `e89`, sets 47
attributes at once, mostly `env.*` and `deps.*`, and its arms are `reproduce` containers that
cannot start the compose stack. It was named and not run. The ranking is not evidence about the
cause.

## Verdict

STUCK on the cause, with the premise narrowed by hand: the failure is not CI-only. It reproduces
on bare metal when `preview.spec.ts` runs first, and the cause of that interaction is unknown.
After `preview.spec.ts` alone no wrangler or workerd process and no listening port remained, so a
leaked process was not what was observed. Drift's loop produced no confirmation.

## Does Not Show

- Why the `modify` tests fail in CI and pass on every local run.
- The Mac result, and any result on the runner image.
- That `preview.spec.ts` causes the `to-worker` failure: the order and the failure move together
  in ten runs on one host, and the two-file run is a single run.
- Anything about the green-to-red commit delta.
