# Drangler Case A

Question: why does the drangler `to-worker` end-to-end spec fail with `fetch failed`, `write EPIPE` and `/rows answered 500` on GitHub's runner and on x86_64 Linux hosts, and pass on an Apple Silicon Mac?

The measured answer is in `results/drangler-case-a/latest.md`. In short: the spec posts a 5.9 MB body to the fixture worker on a pooled keep-alive connection after the test process has blocked its event loop for more than about 5 s, and the worker's HTTP server has already closed that idle connection.

## What A Pass And A Fail Look Like

`run.sh` passes when both checks hold:

1. In the recorded CI logs (`fixtures/drangler/logs`), the sum of the `converts the whole dump` and `replays into a plain SQLite` test times is under 5000 ms in every run where `to-worker` passed and over 5000 ms in every run where it failed.
2. In the minimal reproduction (`lab/min.mjs`), a client that blocks its event loop for 6 s between two requests fails the second request (a 6 MB POST) significantly more often than a client that sleeps 6 s, by Wilson 95% intervals. The server is a plain Node `http` server in a child process; there is no workerd, wrangler or drangler in the loop.

It fails when either check does not hold.

## Running

```sh
experiments/run.sh drangler-case-a --check
experiments/run.sh drangler-case-a
experiments/drangler-case-a/ci-timings.sh
experiments/run.sh drangler-case-a --paisley
```

`--check` validates the lab scripts and prints the CI table without running the reproduction. A full run takes about 6 minutes (15 reps of three arms). `--reps N` changes the count.

The reproduction timing is sensitive to other work on the machine. Run it alone: a run beside another job passed every arm in one recorded case.

`--suite DRANGLER_TREE --body CONVERTED_SQL` also runs the raw-socket idle probe and the seed repro against the fixture worker of a drangler checkout at `df6dce4` with `node_modules` installed (`bun install --frozen-lockfile`). `CONVERTED_SQL` is the converted dump the spec writes to its scratch directory.

## Files

| file | content |
| --- | --- |
| `run.sh` | the two checks, `--check`, `--suite` |
| `ci-timings.sh` | the CI log table and its separation check |
| `lab/min.mjs` | the Node-only reproduction |
| `lab/lib.mjs`, `lab/idle.mjs`, `lab/seedrepro.mjs` | start the fixture worker, measure its idle close, repeat the seed POST after a controlled block |
| `lab/replay.mjs` | times the `node:sqlite` replay that blocks the event loop in the spec |

Raw output of every run goes under `experiments/results/drangler-case-a/runs/`, which is ignored.

## Needs

Node 22 or later. No Docker, no network and no GitHub access for the default run. `--paisley` runs in the `drift-cisim:3` container, which carries Node 24.20.0; the result there is not the one recorded for Node 24.21.0.
