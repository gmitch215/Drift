# README Command Check

Question: does every `drift` command in the sh blocks of `README.md` exist in the binary, and does every flag in it appear in that command's `--help`?

`check.sh` reads the fenced `sh` blocks of the README, takes each line that starts with `drift`, and strips a trailing `#` comment. It stops a line's flags at a standalone `--`. In the default mode it runs `DRIFT --version` for a version command, and `DRIFT <command> --help` for the others, then requires each flag to appear in that output. It fails with the README line number of any command that does not run, or of any flag its help does not list.

## Pass and Fail

A run passes when every command is listed in the output as `ok` and the script prints `PASS`. It fails on the first command that cannot run, on an unbalanced double quote, on a flag missing from `--help`, and when the README holds no `drift` command at all.

The check does not run the commands. It does not prove that a command produces the output the README shows, and it does not check arguments other than flags.

## Inputs

- `../../README.md`, the file under check.
- `DRIFT`, the path to an executable `drift` binary. The check needs it except in `--check` mode.

## Runtime

One `--help` call per command. No Docker, no network.

## Running

```sh
experiments/readme-check/run.sh --check
DRIFT=/path/to/drift experiments/readme-check/run.sh
```

`--check` syntax-checks both scripts and parses the README without any binary. It prints each command it would run and exits non-zero on a parse failure.

The real run needs a Linux or macOS `drift` executable. The accepted run is recorded in `latest.md`.
