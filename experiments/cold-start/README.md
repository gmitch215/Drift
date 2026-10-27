# Cold-Start Test

Question: can a developer who has never used Drift reach the right answer from the README and the tool's own output, without outside help?

Each run gives a fresh reader one broken scenario. The reader has the README, the `drift` executable on PATH, and two shells: `here` (the project as it works) and `there` (the same project where it fails). The reader gets no other documentation, no network, and no Drift source.

## Pass and Fail

A run passes when the reader's `answer.md` names the cause in the answer key (its attribute path or a listed plain-language equivalent), the reader used at most 5 commands that the README led to, and the run took at most 10 minutes. Anything else fails. `SCORING.md` defines each check.

The gate for the batch is at least 80% of runs passing, across 8 scenarios with 3 independent readers each.

## Inputs

- `scenarios/<n>/brief.md`: the plain-language problem the reader sees.
- `scenarios/<n>/project/`: the program and its files, copied into both containers. Side directories `scenarios/<n>/here/` and `scenarios/<n>/there/` overlay the project on one side only. `scenarios/8/alt/` is the second issue report for scenario 8.
- `scenarios/<n>/scenario.env`: the image and the extra `docker run` flags for each side. Images are named from `docker images` on the host, and `cold-up.sh` refuses to start while one is `TBD`.
- `answers/<n>.md`: the answer key. It is never copied into a container, and readers must not open it.
- `harness.sh` and the four scripts below read the host from `COLD_HOST` (default `paisley-park`) and the `drift` Linux binary from `COLD_DRIFT_BIN` (default `drift-rig/bin/drift-linux-x64-c775d8f`, relative to the remote home).

## Runtime

About 10 minutes per reader run, plus setup. Each scenario runs two containers with `--network none`, 1 GiB of memory, 1 CPU, 256 processes and `--storage-opt size=2G`. The storage cap needs an XFS backing filesystem; on other hosts the flag fails and must be removed or the host changed.

## Needs

- Docker on `COLD_HOST`, and an ssh alias for it with key authentication.
- The `drift` Linux x64 executable at `COLD_DRIFT_BIN` on `COLD_HOST`.
- Images named in each `scenario.env`, present on `COLD_HOST`.
- Downloads on the host are limited to images that are already present. Any new image pull follows the network lock rule in `~/.claude/references/remote-machines.md`.

## Run One Scenario

```sh
experiments/cold-start/cold-up.sh 1
experiments/cold-start/cold-sh.sh 1 here 'drift --help'
experiments/cold-start/cold-down.sh 1
experiments/cold-start/score.sh experiments/results/cold-start/runs/<run-dir>
```

`cold-up.sh <n> alt` starts the alternative variant of a scenario; its run directory is named `<n>-alt-<stamp>`.

The main session starts the reader with `READER.md` and the scenario brief, puts the script directory on the reader's PATH, and starts the reader in a directory outside this repository. A reader with shell access to the repository can read the answer key, so the working directory is the isolation.

## Single-Machine Mode

Scenarios 7 and 12 set `MODE=single` in `scenario.env`. They start only the reporter's container (`drift-cold-<n>-here`). `cold-sh.sh <n> there` and `cold-cp.sh` refuse to run for these scenarios, and `cold-down.sh` stops only the reporter's container. The brief is a reporter's message, and the reader follows `READER.md`'s reporter variant.

## Run All Scenarios

Run the eight scenarios one after another, three readers each. `cold-up.sh` refuses to start a second scenario while one is up, so no two run at once.

## Check Mode

```sh
experiments/cold-start/run.sh --check
```

The check runs no Docker and no network. It syntax-checks the scripts, confirms each scenario and answer key exists, and runs the scorer on a synthetic run.

## Results

Each run writes `experiments/results/cold-start/runs/<run-dir>/` (gitignored): `run.env`, `commands.log`, `transcript.log`, the reader's `answer.md`, and the score. The accepted table is `experiments/results/cold-start/latest.md`.

The directory names of the containers are `drift-cold-<n>-here` and `drift-cold-<n>-there`.
