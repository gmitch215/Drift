# Scoring

`score.sh <run-dir>` prints one block per run. It reads three files from the run directory and the answer key from `answers/`.

## Checks

Commands: every line of `commands.log` is a command. The score counts the commands the README leads to (every `drift` command and every copy between the sides); the limit is 5 for a pass. `score.sh` also prints the total of all commands, including the reader's own checks (`cat`, `node -v`); a total over 5 is reported next to the pass, not hidden. The first baseline (2026-10-26) counted every command; the README-led count was added on 2026-10-26 after the first rerun, because readers check their findings by hand, and both numbers are in `latest.md`. The reader's 15-command stop is a cap during the run.

On 2026-10-27, after the first full batch of isolated readers (`results/cold-start/round3-batch-a.log`), the counter was tightened: a command counts as README-led when it is a copy or contains `drift` followed by a subcommand (`drift why`, `drift next`). `drift --version` and `which drift` no longer count, because readers run them to check their setup and the README does not lead to them. The scorer prints the earlier rule next to the new one (`earlier rule: N`), and `latest.md` carries both. The change moved 3 of 21 runs from fail to pass (13 of 21 became 16 of 21) (scenario 2 readers 1 and 2, scenario 8 reader 1) and was made after seeing the results.

Time: the seconds from `started` in `run.env` to the modification time of `answer.md`. The limit is 600.

Answer: `answer.md` must contain one of the lines under `## Accept` in the key, case-insensitively. The strings are chosen to be specific, so a match in a wrong context is possible. The scorer does not judge meaning. A person reads the answer and the key before a run is counted in `latest.md`.

## Human Checks

The scorer cannot tell whether a command was one the README led to. A person checks the transcript for that. A command counts as led to the README when the README, or a command the README named, is the reason the reader ran it. Commands the reader ran on its own initiative (for example, `ls` or `which git`) count toward the 5-command limit but are not a README failure. Record them.

Each failure needs a named fix. The fix goes into the fix list for the next README or output change, with the line that misled the reader quoted.

## Keys That Are Not Scored

A key whose first line after the title reads `Status: UNVERIFIED` is not scored. `score.sh` exits 2 and prints the reason. No current key is in that state.

## Gate

The gate is at least 80% of scored runs passing, across 8 scenarios with 3 readers each. A run that is not scored counts as a failure of the gate until the key is verified.

## Reporter Variant

Scenarios 9, 10, 11, 13 and 14 run with two machines, a laptop where the check passes (`here`) and a server where it fails (`there`). Scenarios 7 and 12 run in single-machine mode (`MODE=single` in `scenario.env`). Scenario 12 has no server image with git that matches the architecture of its server, and scenario 7 gives only the saved server log, so the reader has `here` alone. In every one of them the reader starts from the error on the server, and Drift may ask for a check on either machine. Their `answers/<n>.md` keys begin by stating whether the cause is injected by the staging or real.

A reporter run passes when all four hold:

- `answer.md` contains one of the lines under `## Accept` in the key.
- The route to that cause went through Drift's own output. The transcript shows Drift asking for the check that reveals the family the key names, and the reader's `cold-sh.sh` run of that check, or Drift naming the cause itself. The reader's own knowledge does not count.
- At most 5 commands led by the README. The scorer counts only `drift` commands and copies, and in single-machine mode there are no copies. A person counts the reader's check commands that answered Drift's asks, and adds them to the scorer's count.
- At most 600 seconds, as for every run.

The reporter variant's `## Asks` section in `answer.md` lists the checks and replies. A person reads it against the transcript; the scorer does not parse it.

Scenario 14's cause is real, from the thread named in its key. Scenarios 7 and 9 to 13 have injected causes. The thread named in each of those keys is provenance only and does not define the cause.
