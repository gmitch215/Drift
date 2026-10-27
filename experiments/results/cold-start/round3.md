# Cold-Start Round 3 (reporter scenarios and the ask loop)

2026-10-27, working-tree README with the ask and reply loop. One Haiku reader per run, no outside help, one machine per reporter scenario. The pass rule for reporter scenarios 9 to 14 is in `SCORING.md` ("Reporter Variant"). Binary column names the build the reader had.

The accepted table of round 3 is in `latest.md`. This file keeps the first hand-launched runs and their findings; the later batches are in `round3-batch-a.log` to `round3-batch-e.log`.

| Run | Scenario | Binary | README-led commands | All commands | Seconds | Scorer | Person check | Finding |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 9-20261027T072347Z | 9 PHP version (injected) | `drift-linux-arm64-final` | 5 | 6 | 59 | PASS | pass by rule, weak: Drift asked `php -v` and read 7.4.33, then said "I know of no rule that links it to this failure"; the cause came from the reader's own PHP knowledge | no way to compare one fact with the working machine without copying a capture file; `drift why --there` on the failing machine's own capture said "this machine against the saved capture" and "Assuming it works here", which reads backwards for a reporter on the failing machine. Fix: `drift why --reply --works` (two-sided reply) and a same-machine message |

| 9-20261027T075658Z | 9 PHP version (injected), two machines | `drift-linux-arm64-final2` | 11 | 14 | 96 | FAIL (commands 11 over 5) | fail | `php -v | drift why --reply --works` on the laptop printed "no session in .drift/why/session.json": the session lives on the server, so the works-side reply has nowhere to go; the reader fell back to `--save`, a copy and `--there`. Also: the `--there ... --error` output lists three differences and names one without saying why the others are excluded; the fix names PHP 8.3.36 but not the minimum version `str_contains` needs. Fix: the works-side step must be pasteable on the machine that holds the session (`drift why --reply --works`, paste, Ctrl-D) or sent over ssh; wording for both |

| 12-20261027T075929Z | 12 missing tool (injected), one machine | `drift-linux-arm64-final2` | 3 | 5 | 46 | PASS | pass: Drift asked `command -v git`, the reply said "not installed where it fails" | "I know of no rule that links it to this failure" reads as if the cause did not matter (it is about the rule table); the reader had no laptop for the works-side step; the README example uses PHP while the reader's error was a Python traceback |

Runs after the two-sided reply landed are recorded below with the new binary name.
