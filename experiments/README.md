# Experiments

Rerunnable measurements that live beside the code. Each experiment is a folder with its own `README.md` and `run.sh`; `results/<name>/latest.md` records the last accepted run.

| experiment | question |
| --- | --- |
| [atlas-libs](atlas-libs/README.md) | do kotlinx-datetime, kotlinx.serialization, kotlinx-io and kotlinx-coroutines print the same transcript on jvm, linuxX64, js and wasmJs |
| [js-long-template](js-long-template/README.md) | from which Kotlin/JS compiler version does a Long interpolated through an inlined lambda print through a double |
| [drangler-case-a](drangler-case-a/README.md) | why does the drangler `to-worker` spec fail with `write EPIPE` on x86_64 Linux and CI and pass on an Apple Silicon Mac |
| [box-tests](box-tests/README.md) | do the confirmed Kotlin findings reproduce as compiler-shaped `box` tests on jvm, linuxX64, js and wasmJs |
| [dev-solves](dev-solves/README.md) | how often does a `drift solve` verdict on a dev scenario name the injected cause |
| [corpus-id](corpus-id/README.md) | given a real closed issue as filed, does `drift triage` name the cause that the maintainers or the closing pull request later gave |
| [cold-start](cold-start/README.md) | can a reader who has never used Drift reach the right cause from the README and the tool's output alone, in at most 5 commands and 10 minutes |
| [readme-check](readme-check/README.md) | does every `drift` command and flag in the README's sh blocks exist in the binary and parse |

Existing rigs stay where they are: `tools/perf`, `bench` and `tools/browsers`.

## Running

```sh
experiments/run.sh <name> [experiment args]
experiments/run.sh <name> --check
experiments/run.sh <name> --paisley
experiments/run.sh <name> --paisley --fetch
```

`--check` validates the experiment without building or downloading. `--paisley` copies the experiment to `~/drift-rig/experiments/<name>` on paisley-park, runs it in a `drift-cisim:3` container capped at 10 GB and 8 CPUs, takes the box-wide network lock (`NET=0` skips it for a warm run), and returns at once; `--fetch` copies the `runs/` directory back when `run.done` exists.

## Results

`results/<name>/latest.md` is tracked. Raw output of every run goes to `results/<name>/runs/`, which is ignored.
