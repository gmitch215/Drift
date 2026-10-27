# Drangler Case A: the `to-worker` EPIPE

Question: why does `tests/e2e/to-worker.spec.ts` at drangler `df6dce4` fail with `TypeError: fetch failed`, `Caused by: Error: write EPIPE` and `Error: /rows answered 500` on the GitHub runner and on x86_64 Linux, and pass on an Apple Silicon Mac?

Verdict: the failure needs two things at once. (1) The test process blocks its event loop for more than about 5 s between its last request to the fixture worker and the 5.9 MB `/seed` POST, and workerd closes an idle keep-alive connection exactly 5.0 s after a response. (2) The POST goes out on that pooled connection. Removing either stops the failure in the measured arms. No process died. The machine difference is how long the synchronous tests run, not the CPU architecture: on paisley-park the `node:sqlite` replay of the converted dump takes about 5300 ms under `/tmp` (ext4 on LVM) and about 38 ms under tmpfs, and on the Mac 495 to 718 ms. Why the arms at 5.0 s and 5.5 s behave differently, and why Node 24.20.0 did not fail in 5 of 5 runs, is not explained.

## Method

Host: paisley-park, Ubuntu 24.04, kernel 6.8.0-138, x86_64, 24 threads, `ulimit -n` 1024. Tree: drangler `df6dce4` exported from git objects (170 blobs), `bun install --frozen-lockfile` (bun 1.4.2), wrangler 4.146.0, workerd 1.20261001.1 (the same versions are installed on the Mac), Node 24.21.0 unless an arm says otherwise. The spec ran alone with `--coverage` as in `test:e2e:coverage`, on a copy of the tree with two additions (a copy of the fixture worker's `dev.log` and the dump kept per run; env switches for the arms below). One run at a time, arms in rotating order, compose stack recreated per run.

## Evidence

Which process: wrangler and workerd were alive before and after the failing call (`/serve` answered 200, then `/rows` answered 500). The fixture worker's `dev.log` has no `/seed` request. The 500 is `near "FROM": syntax error` because the table was never seeded. The failing test dies in 62 to 154 ms with `write EPIPE`, so the writer is the vitest worker writing to a pooled socket whose reader (workerd) had closed it.

Idle close: a raw socket on the fixture worker (`lab/idle.mjs`, 3 reps) received FIN 5000, 5000 and 5001 ms after a complete response.

CI logs (`ci-timings.sh`, sum of the `converts` and `replays` test times in ms):

| run | converts | replays | sum | `to-worker` |
| --- | ---: | ---: | ---: | --- |
| 36232485230 | 2664 | 1218 | 3882 | pass |
| 36311117223 | 2554 | 886 | 3440 | pass |
| 36412389380 | 2671 | 1099 | 3770 | pass |
| 36702683742 | 2564 | 1224 | 3788 | pass |
| 36995781138 | 4364 | 1415 | 5779 | fail |
| 37114464625 | 4270 | 1576 | 5846 | fail |
| 37195797966 | 4371 | 1504 | 5875 | fail |

Every pass is under 5000 ms and every fail is over it. `converts` rose by about 1700 ms between the green and red commits (not examined). Run 35585969640 has no `loads` line and is left out.

## Arms

Seed POST failures out of runs, `to-worker.spec.ts` alone, 95% Wilson interval. B0 pools three matrices (15 runs).

| arm | what changed from B0 | failed | interval | reading |
| --- | --- | ---: | --- | --- |
| B0 | as CI: coverage, `TMPDIR=/tmp`, Node 24.21.0 | 12 of 15 | 0.55 to 0.93 | baseline |
| B1 | `TMPDIR=/dev/shm/p5-drangler` (the sync replay drops to about 38 ms) | 0 of 5 | 0.00 to 0.43 | removes the block: failure stops |
| B2 | B1 plus a 6.0 s `Atomics.wait` before the seed | 3 of 5 | 0.23 to 0.88 | adds the block back: failure returns |
| B4 | B1 plus a 5.5 s block | 3 of 5 | 0.23 to 0.88 | same |
| B5 | B0, seed POST on a fresh `node:http` connection (`agent: false`) | 0 of 5 | 0.00 to 0.43 | removes the pooled-socket reuse: failure stops |
| B3 | B0 plus `Connection: close` on the seed POST | 5 of 5 | 0.57 to 1.00 | not a removal: undici still writes the request to the pooled socket |
| C1 | Node 26.10.0 (undici 8.10.2) | 2 of 5 | 0.12 to 0.77 | not separated from B0 |
| C2 | Node 24.20.0 (undici 7.29.0) | 0 of 5 | 0.00 to 0.43 | separated from B0; cause not found |
| C4 | `ulimit -n 1048576` | 5 of 5 | 0.57 to 1.00 | no effect |
| C5 | `LC_ALL=C` | 4 of 5 | 0.38 to 0.96 | no effect |
| C6 | `taskset -c 0-3` | 5 of 5 | 0.57 to 1.00 | no effect |

Intervals of 5 runs are wide; B1, B5 and C2 separate from B0 only because B0 has 15 runs.

Without the spec (`lab/seedrepro.mjs` against the fixture worker, 15 reps per arm, a random 0 to 1 s pause before each GET): block 3 s 0, 4 s 0, 5.0 s 15, 5.5 s 0, 6 s 10, 7 s 5, 10 s 9 of 15 failed; sleeping 5.5 s without blocking 0 of 15; a fresh connection after a 5.5 s block 0 of 15. The failure rate is not monotonic in the block length and the reason is not known.

Without workerd (`lab/min.mjs`, a Node `http` server in a child process, Node 24.21.0, run alone): block 6 s failed 10 of 10 in two runs, block 5.5 s 0 of 10, sleep 6 s 0 of 10. An earlier run of other arms gave block 5.5 s 8 of 15, 6 s 7 of 15, 7 s 8 of 15, 5 s 0 of 15; two runs made beside another job gave 0 of 15 for every arm. Using the `undici` package (7.29.0 and 7.29.1) in place of the built-in `fetch` gave 0 of 30 for block 6 s on both versions, so the package A/B did not test the Node 24.20.0 result.

The Mac: the `node:sqlite` replay of the same converted dump took 594, 495 and 718 ms. The full sync time (`converts` plus `replays`) on the Mac was not measured.

## Not Shown

- Architecture was not varied. No `linux/arm64` run under qemu was made. The measured variable that separates the machines is the duration of the synchronous tests; CPU architecture enters only through that duration.
- Why the failure window in block length is not monotonic, and why Node 24.20.0 passed.
- That the Mac fails below 5 s of sync time: the Mac full-run sum was not measured.
- The `preview` and `modify` failures.
- Which kj setting closes the idle connection (the 5.0 s is measured, the attribution to `pipelineTimeout` was not read in source).

## Commit And Tools

Drift `a0dbff76` plus working tree. Regenerate: `experiments/run.sh drangler-case-a` for the minimal reproduction and the CI table; the arm matrices ran from `~/drift-rig/p5-drangler/` on paisley-park (logs `matrix-b.log`, `matrix-c.log`, `matrix-d.log`, `runs/lab-d/out.jsonl`, `runs/min/out.json`).
