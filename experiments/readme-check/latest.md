# README Command Check

Status: accepted run against a real `drift` executable, 2026-11-01. 9 of 9 commands pass.

## What it checks

`check.sh` reads every `drift` command in the `sh` fences of `README.md`. For each one it runs `drift SUBCOMMAND --help` and requires exit 0, and it requires every flag in the README command to appear in that help. It does not run the README commands themselves, and it skips commands outside `sh` fences, such as the `drift demo` bullet. The outputs the README quotes were copied from earlier real runs.

## Binary run

The executable is `cli/build/bin/linuxX64/releaseExecutable/drift.kexe`, linked in a paisley-park lane from commit `150223e9` plus the working-tree changes of 2026-11-01 (`:cli:linkReleaseExecutableLinuxX64`). It prints `drift version 1.0.0 (linux)`, is 15,662,760 bytes and has the SHA-256 prefix `d9cc10fdbbb75e9f`. The README is the working-tree file of 149 lines, SHA-256 prefix `335ae3d6d1108074`; the line numbers below are that file's.

Command, from the lane's copy of the repository:

```
DRIFT=cli/build/bin/linuxX64/releaseExecutable/drift.kexe sh experiments/readme-check/check.sh README.md
```

```
ok line 40: drift why --error "Fatal error: Uncaught Error: Call to undefined function str_contains()"
ok line 89: drift why --save there.json
ok line 95: drift why --there there.json --error "TypeError: Object.groupBy is not a function"
ok line 120: drift why --error "TEXT"
ok line 121: drift why
ok line 122: drift why --there docker:NAME
ok line 123: drift why --there user@host
ok line 124: drift why -- make test
ok line 125: drift why issue.md
PASS 9 commands in README.md
```

The same executable printed `fixtures/demo/golden/demo.txt` byte for byte from `drift demo`, run with `env -i` from `/`.

## Self-test against a stub

The stub stands in for `drift` and prints fixed help text for three commands. It tests the checker's logic only and says nothing about Drift. A fixture holds one bad flag (`--bogus`) and one unknown command (`nope`).

```
FAIL line 6: --bogus is not listed by drift why --help
FAIL line 14: drift nope does not run (exit 2): drift nope --help
FAIL 4 commands checked in bad.md
```

The checker exits 1 on the fixture and 2 when `DRIFT` is unset.

## Rerun

Rerun after any README change that touches a command, with the executable of the tree being released:

```
DRIFT=PATH_TO_DRIFT sh experiments/readme-check/check.sh README.md
```
