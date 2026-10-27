# README Command Check

Status: no accepted run against a drift binary yet. This file holds the parse-only pass of `run.sh --check` and the checker's self-test against a stub binary. The binary run is listed under Next Run.

## Parse-only pass

Command: `sh experiments/readme-check/run.sh --check`

```
ok line 33: drift why --save there.json
ok line 39: drift why --there there.json
ok line 65: drift why --there there.json --error "TypeError: Object.groupBy is not a function"
ok line 87: drift why
ok line 88: drift why --there docker:NAME
ok line 89: drift why --there user@host
ok line 90: drift why -- make test
ok line 91: drift why issue.md
ok line 99: drift next .drift/why/case
PASS 9 commands in ../../README.md
```

## Self-test against a stub

The stub stands in for `drift` and prints fixed help text for three commands. It tests the checker's logic only and says nothing about Drift. A fixture holds one bad flag (`--bogus`) and one unknown command (`nope`).

```
FAIL line 6: --bogus is not listed by drift why --help
FAIL line 14: drift nope does not run (exit 2): drift nope --help
FAIL 4 commands checked in bad.md
```

The checker exits 1 on the fixture and 2 when `DRIFT` is unset.

## Next run

Run in a copy of the repository on paisley-park, against the Linux binary:

```
DRIFT=$HOME/drift-rig/bin/drift-linux-x64-why2 sh experiments/readme-check/check.sh README.md
```

Replace this file with that output when the run passes.
