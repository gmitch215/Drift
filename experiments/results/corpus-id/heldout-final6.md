# Corpus Identification, Held-Out Half

**This is the held-out half, scored once for this Drift version. Nothing was tuned on it.** The dev half is the development signal.

Question: given a real closed issue as filed, does `drift triage` name the cause that the maintainers later gave? Method, definitions and the bars are in `experiments/corpus-id/README.md`. Drift sees only the issue title and body as filed.

| | |
|---|---|
| Drift | final6, binary sha256 `ddeb143bbb91e9bf18cd3ec28cd93f5cc57dc27e472729a59c9a1493263af45d` |
| tree | HEAD `a0dbff76` plus uncommitted changes |
| host | lane `r2` on paisley-park (container) |
| regenerate | `experiments/corpus-id/score.sh --half heldout` |

## What Drift Prints

Share of items where `drift triage` printed anything. `fact (not os, signature)` counts a fact other than `os.*` and `run:signature`.

| group | n | any fact | fact (not os, signature) | any rule | draft rule | rule or draft | anything |
|---|---|---|---|---|---|---|---|
| all 141 labelled items | 141 | 82/141 (0.582; 0.499-0.660) | 48/141 (0.340; 0.267-0.422) | 4/141 (0.028; 0.011-0.071) | 0/141 (0.000; 0.000-0.027) | 4/141 (0.028; 0.011-0.071) | 82/141 (0.582; 0.499-0.660) |
| env-cause, named attribute | 6 | 4/6 (0.667; 0.300-0.903) | 2/6 (0.333; 0.097-0.700) | 0/6 (0.000; 0.000-0.390) | 0/6 (0.000; 0.000-0.390) | 0/6 (0.000; 0.000-0.390) | 4/6 (0.667; 0.300-0.903) |
| env-cause, unnamed | 7 | 5/7 (0.714; 0.359-0.918) | 3/7 (0.429; 0.158-0.750) | 0/7 (0.000; 0.000-0.354) | 0/7 (0.000; 0.000-0.354) | 0/7 (0.000; 0.000-0.354) | 5/7 (0.714; 0.359-0.918) |
| code-defect | 32 | 21/32 (0.656; 0.483-0.796) | 11/32 (0.344; 0.204-0.517) | 1/32 (0.031; 0.006-0.157) | 0/32 (0.000; 0.000-0.107) | 1/32 (0.031; 0.006-0.157) | 21/32 (0.656; 0.483-0.796) |
| user-error | 7 | 6/7 (0.857; 0.487-0.974) | 5/7 (0.714; 0.359-0.918) | 0/7 (0.000; 0.000-0.354) | 0/7 (0.000; 0.000-0.354) | 0/7 (0.000; 0.000-0.354) | 6/7 (0.857; 0.487-0.974) |
| unclear | 89 | 46/89 (0.517; 0.415-0.618) | 27/89 (0.303; 0.218-0.405) | 3/89 (0.034; 0.012-0.094) | 0/89 (0.000; 0.000-0.041) | 3/89 (0.034; 0.012-0.094) | 46/89 (0.517; 0.415-0.618) |

Fact keys printed (items with at least one fact on the key family): `os` 117, `run:signature` 35, `runtime` 10, `tool.java` 9, `tool.python` 8, `tool.docker` 8, `kernel` 7, `tool.php` 7, `tool.go` 7, `ci` 6, `tool.node` 5, `tool.npm` 4, `tool.rust` 3, `tool.kotlin` 2, `tool.deno` 2, `tool.cc` 2, `tool.spigot` 1, `tool.pip` 1, `tool.docker-buildx-plugin` 1, `tool.docker-compose-plugin` 1, `tool.paper` 1, `env.LANG` 1, `project.python` 1, `tool.valgrind` 1, `env.USE_ZEND_ALLOC` 1.

## Tier B Measures on Env-Cause Items

Primary set: `env-cause` with a named attribute, a quote that verifies, and no rule that cites the thread. 13 env-cause items, 6 with a named attribute, 0 quote-invalid (counted `unclear`), 0 contaminated, 6 primary. Wilson 95% intervals.

| set | n | fact | fact-exact | rule | rule-exact | named | cause | value |
|---|---|---|---|---|---|---|---|---|
| primary | 6 | 2/6 (0.333; 0.097-0.700) | 2/6 (0.333; 0.097-0.700) | 0/6 (0.000; 0.000-0.390) | 0/6 (0.000; 0.000-0.390) | 2/6 (0.333; 0.097-0.700) | 0/6 (0.000; 0.000-0.390) | 1/6 (0.167; 0.030-0.564) |
| primary, attribute in Drift's key table | 2 | 2/2 (1.000; 0.342-1.000) | 2/2 (1.000; 0.342-1.000) | 0/2 (0.000; 0.000-0.658) | 0/2 (0.000; 0.000-0.658) | 2/2 (1.000; 0.342-1.000) | 0/2 (0.000; 0.000-0.658) | 1/2 (0.500; 0.095-0.905) |
| primary, attribute outside the key table | 4 | 0/4 (0.000; 0.000-0.490) | 0/4 (0.000; 0.000-0.490) | 0/4 (0.000; 0.000-0.490) | 0/4 (0.000; 0.000-0.490) | 0/4 (0.000; 0.000-0.490) | 0/4 (0.000; 0.000-0.490) | 0/4 (0.000; 0.000-0.490) |
| named incl. contaminated (sensitivity) | 6 | 2/6 (0.333; 0.097-0.700) | 2/6 (0.333; 0.097-0.700) | 0/6 (0.000; 0.000-0.390) | 0/6 (0.000; 0.000-0.390) | 2/6 (0.333; 0.097-0.700) | 0/6 (0.000; 0.000-0.390) | 1/6 (0.167; 0.030-0.564) |
| stratum large | 3 | 2/3 (0.667; 0.208-0.939) | 2/3 (0.667; 0.208-0.939) | 0/3 (0.000; 0.000-0.561) | 0/3 (0.000; 0.000-0.561) | 2/3 (0.667; 0.208-0.939) | 0/3 (0.000; 0.000-0.561) | 1/3 (0.333; 0.061-0.792) |
| stratum small | 3 | 0/3 (0.000; 0.000-0.561) | 0/3 (0.000; 0.000-0.561) | 0/3 (0.000; 0.000-0.561) | 0/3 (0.000; 0.000-0.561) | 0/3 (0.000; 0.000-0.561) | 0/3 (0.000; 0.000-0.561) | 0/3 (0.000; 0.000-0.561) |
| ecosystem container-ci | 1 | 1/1 (1.000; 0.207-1.000) | 1/1 (1.000; 0.207-1.000) | 0/1 (0.000; 0.000-0.793) | 0/1 (0.000; 0.000-0.793) | 1/1 (1.000; 0.207-1.000) | 0/1 (0.000; 0.000-0.793) | 1/1 (1.000; 0.207-1.000) |
| ecosystem js | 3 | 1/3 (0.333; 0.061-0.792) | 1/3 (0.333; 0.061-0.792) | 0/3 (0.000; 0.000-0.561) | 0/3 (0.000; 0.000-0.561) | 1/3 (0.333; 0.061-0.792) | 0/3 (0.000; 0.000-0.561) | 0/3 (0.000; 0.000-0.561) |
| ecosystem python | 2 | 0/2 (0.000; 0.000-0.658) | 0/2 (0.000; 0.000-0.658) | 0/2 (0.000; 0.000-0.658) | 0/2 (0.000; 0.000-0.658) | 0/2 (0.000; 0.000-0.658) | 0/2 (0.000; 0.000-0.658) | 0/2 (0.000; 0.000-0.658) |

Mismatch control (each item scored against another item's attribute, 6 pairs): fact 1/6 (0.167; 0.030-0.564); rule 0/6 (0.000; 0.000-0.390); named 1/6 (0.167; 0.030-0.564); cause 0/6 (0.000; 0.000-0.390).

## Null Baseline and Precision

Items with no named cause attribute (`code-defect`, `user-error`, `unclear`, unnamed env-cause). A rule or draft here is a coincidence with respect to a cause attribute.

| set | n | any fact | any rule | draft rule | rule or draft |
|---|---|---|---|---|---|
| non-named items | 135 | 78/135 (0.578; 0.493-0.658) | 4/135 (0.030; 0.012-0.074) | 0/135 (0.000; 0.000-0.028) | 4/135 (0.030; 0.012-0.074) |
| code-defect | 32 | 21/32 (0.656; 0.483-0.796) | 1/32 (0.031; 0.006-0.157) | 0/32 (0.000; 0.000-0.107) | 1/32 (0.031; 0.006-0.157) |
| user-error | 7 | 6/7 (0.857; 0.487-0.974) | 0/7 (0.000; 0.000-0.354) | 0/7 (0.000; 0.000-0.354) | 0/7 (0.000; 0.000-0.354) |

Precision of `cause` (dev signal for bar 1): items with a non-empty cause set and a verdict other than `unclear`: 1; the cause set holds the labelled attribute in 0/1 (0.000; 0.000-0.793).

Coverage of `cause` on env-cause items with a named attribute (primary): 0/6 (0.000; 0.000-0.390).

## Four-Class Answers

Truth: environment (env-cause), not environment (code-defect, user-error) or unclear. Drift's answer is one of: names a cause (a matched rule or draft rule), says not environment, asks (a non-empty `ask` list) or cannot tell. Correct answers (amendment 2026-10-27): env-cause with a named attribute, a cause that holds the exact key, or an ask whose family is the attribute or a key prefix of it; env-cause, unnamed: an ask; code-defect or user-error: not environment; unclear: an ask, or cannot tell (reading: the answer that was correct before the ask existed stays correct; the strict row below drops it).

| truth | n | names the labelled cause | names another cause | says not environment | asks for the family | asks for something else | cannot tell |
|---|---|---|---|---|---|---|---|
| environment, named attribute | 6 | 0 | 0 | 1 | 0 | 5 | 0 |
| environment, unnamed attribute | 7 | 0 | 0 | 1 | 0 | 4 | 2 |
| not environment: code-defect | 32 | 0 | 1 | 6 | 0 | 11 | 14 |
| not environment: user-error | 7 | 0 | 0 | 2 | 0 | 3 | 2 |
| unclear | 89 | 0 | 3 | 14 | 0 | 30 | 42 |
| all | 141 | 0 | 4 | 24 | 0 | 53 | 60 |

Four-class accuracy: 84/141 (0.596; 0.513-0.673). Per truth class, correct: environment 4/13 (0.308; 0.127-0.576), not environment 8/39 (0.205; 0.108-0.355), unclear 72/89 (0.809; 0.715-0.877).
Strict reading (cannot tell on unclear is not correct): 42/141 (0.298; 0.229-0.378); unclear 30/89 (0.337; 0.247-0.440).

Sanity row, a tool that always asks: correct on at most the env-cause and unclear items, 102/141 (0.723; 0.644-0.791), which must stay under the accuracy bar of 0.80.

Share of items with an ask, by truth: env-cause 9/13 (0.692; 0.424-0.873), code-defect 11/32 (0.344; 0.204-0.517), user-error 3/7 (0.429; 0.158-0.750), unclear 30/89 (0.337; 0.247-0.440).

## Ratified Bars (Amendment 2026-10-27)

Pass or miss is against the bar as ratified. Dev numbers are development signals; the held-out half decides.

| bar | now | ratified | result | note |
|---|---|---|---|---|
| exact attribute named (fact key or cause key) | 2/6 (0.333; 0.097-0.700) | above 0.65 | miss |  |
| exact attribute named, or its family asked | 2/6 (0.333; 0.097-0.700) | above 0.65 (reading with asks) | miss |  |
| facts for the labelled family | 2/6 (0.333; 0.097-0.700) | above 0.70 | miss |  |
| null baseline: no rule or draft on non-named items | 131/135 (0.970; 0.926-0.988) | at least 0.50 | pass |  |
| not environment, precision on its calls | 8/24 (0.333; 0.180-0.533) | point at least 0.95, Wilson lower above 0.80, no env-cause call | miss | Wilson lower 0.180; env-cause items called: 2 |
| `cause` precision | 0/1 (0.000; 0.000-0.793) | point at least 0.85, lower at least 0.65 | miss | Wilson lower 0.000 |
| four-class accuracy | 84/141 (0.596; 0.513-0.673) | point at least 0.80, lower at least 0.72 | miss | Wilson lower 0.513 |

`dev solves` (resolved share of the cause-bearing scenarios, at least 0.80) is measured by the bench, not by this scorer.

Not environment by signal:

| signal | fires | correct (code-defect or user-error) | wrong: unclear | wrong: env-cause |
|---|---|---|---|---|
| bounds | 2 | 1 | 1 | 0 |
| crash | 5 | 2 | 3 | 0 |
| frame | 1 | 0 | 1 | 0 |
| located | 10 | 4 | 6 | 0 |
| logic | 2 | 1 | 1 | 0 |
| memory | 1 | 0 | 1 | 0 |
| mismatch | 1 | 0 | 1 | 0 |
| not-shown | 1 | 0 | 0 | 1 |
| null-deref | 1 | 0 | 0 | 1 |

Items called not environment: EndstoneMC/endstone#115 (unclear), EndstoneMC/endstone#288 (unclear), EndstoneMC/endstone#426 (code-defect), EndstoneMC/endstone#539 (code-defect), JetBrains/markdown#27 (unclear), denoland/deno#21656 (unclear), denoland/deno#6053 (code-defect), docker/cli#1075 (unclear), dotnet/runtime#108644 (user-error), dotnet/runtime#116970 (unclear), dotnet/runtime#126040 (unclear), fabiospampinato/vscode-todo-plus#276 (env-cause), javierbrea/eslint-plugin-boundaries#349 (unclear), ki-editor/ki-editor#1212 (unclear), moby/moby#20997 (code-defect), nodejs/node#10203 (unclear), nodejs/node#26872 (user-error), nodejs/node#8545 (env-cause), openrndr/openrndr#157 (unclear), php/php-src#10670 (unclear), php/php-src#10801 (code-defect), php/php-src#19771 (unclear), rust-lang/rust#94643 (unclear), rust-lang/rust#99083 (code-defect).

## Ask and Reply Loop

Not a ratified bar. Stage 1: Drift's first answer holds a text fact for the labelled family, or an ask whose family is that family. Stage 2: the ask is answered through `drift why --reply` with a synthetic reply (a hand-written template per family filled with the label's failing-side value, or a placeholder when the label has none) and the table asks whether the session then states a fact for the family and whether a matched rule reads the labelled key. Stage 2 measures ask coverage and the parse round trip. It does not measure diagnosis: the reply is made up from the label, not read from a machine. Stage 3 answers the next step, the works-side check, through `drift why --reply --works` with the same template filled with the label's `before` value (or a placeholder) and counts the items whose answer names the labelled family as the difference.

Not run for this half: no replay outputs (`replay.py prepare`, `replay-lane.sh a`, `replay.py reply`, `replay-lane.sh b`).

## Clean Run Loop

Not a ratified bar. Reach: the first `drift why --json` answer holds a `clean-run` ask (the report has a runnable command, a failure signature and a toolchain with an official image). Round trip: the ask is answered through the real `drift why --reply`. The same-failure reply is a hand-written template per signature kind filled with the failure signature Drift extracted from the item's own body (the `run:signature` fact), so it exists only for items whose text carries a signature. The works reply is one line without an error. Stage 2 measures ask coverage and the parse round trip, not diagnosis: the replies are made up, not read from a clean machine. Hard condition: a works reply never ends in NOT_ENVIRONMENT. A same-failure reply on an env-cause item does end in NOT_ENVIRONMENT, which is the correct reading when a clean machine reproduces the failure, and the count is recorded here.

Not run for this half: no clean replay outputs (`replay.py prepare-clean`, `replay-lane.sh a`, `replay.py clean-reply`, `replay-lane.sh b` with `same` and `works`).

## Env-Cause Items and the Gap

One row per `env-cause` dev item. Gap phrases: `hit`; `no extractor for it` (Drift prints no fact of that family on any dev item and it is not in the documented key table); `no rule for it` (a fact of the family is printed, the catalog has no rule on the key); `no rule for this change` (a fact of the key is printed and the catalog has rules on the key, none about this change); `wording unmatched` (the extractor exists, the issue text names the tool or the value, no fact printed); `sensor not captured (not in the issue text)` (neither the labelled value nor the tool name occurs in the issue title and body); `labeller gave no attribute`.

| item | attribute | before to after | Drift printed | gap | note |
|---|---|---|---|---|---|
| EndstoneMC/endstone#423 | unnamed | - | facts os.name=windows, os.version=11, tool.python.version=3.12.10 | labeller gave no attribute |  |
| RicterZ/doujinshi-dl#376 | tool.nhentai.version | - | nothing | no extractor for it |  |
| astral-sh/ruff#21284 | unnamed | - | nothing | labeller gave no attribute |  |
| docker/cli#5898 | tool.go.version | 1.23 to 1.24 | facts kernel.release=6.12.17-1-lts, os.arch=x86_64, os.name=linux, runtime.container.image=Client:, tool.docker.version=28.0.0, tool.go.version=1.24.0 | no rule for this change (3 catalog rules read the key) |  |
| fabiospampinato/vscode-todo-plus#276 | tool.vscode.version | - | facts ci.provider=azure-pipelines | no extractor for it |  |
| gustavosett/Windows-11-Clipboard-History-For-Linux#42 | unnamed | - | facts os.arch=x86_64, os.name=linux, os.release.ID=ubuntu, os.release.VERSION_ID=22.04, os.version=11 | labeller gave no attribute |  |
| ki-editor/ki-editor#505 | unnamed | - | nothing | labeller gave no attribute |  |
| microsoft/vscode#321633 | unnamed | - | facts os.name=windows, os.version=10 | labeller gave no attribute |  |
| mrusse/soularr#48 | config.download_dir | /downloads/slskd to /downloads | nothing | no extractor for it |  |
| nodejs/node#34527 | limits.memory | - | facts os.arch=x86_64, os.name=windows | no extractor for it |  |
| nodejs/node#45723 | os.name | - | facts os.arch=x86_64, os.name=windows | no rule for this change (8 catalog rules read the key) |  |
| nodejs/node#8545 | unnamed | - | facts os.arch=x86_64, os.name=linux, os.release.ID=alpine, tool.node.version=6.5.0, tool.npm.version=3.10.3 | labeller gave no attribute |  |
| openrndr/openrndr#24 | unnamed | - | facts os.name=linux, tool.java.version=1.8.0_181 | labeller gave no attribute |  |

Gap counts: labeller gave no attribute 7, no extractor for it 4, no rule for this change (3 catalog rules read the key) 1, no rule for this change (8 catalog rules read the key) 1.

## Tier A (Weak Truth), Dev

Weak truth: the class comes from the files the closing pull request changed, not from what a person read. A pull request that bumps a pin can fix a code bug. 0 sampled items with a Drift output; Tier A is never pooled with Tier B.

Classes: .

| class | n | any fact | fact (not os, signature) | any rule | draft rule | rule or draft | anything |
|---|---|---|---|---|---|---|---|
| env-or-config-change | 0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 |
| code-change | 0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 |
| all sampled | 0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 |

| set | n | fact | fact-exact | rule | rule-exact | named | cause |
|---|---|---|---|---|---|---|---|
| env-or-config-change, one derived attribute | 0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 |
|   in Drift's key table | 0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 |
|   outside the key table | 0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 | 0/0 |

Items with a non-empty cause set: 0; of those, `env-or-config-change` with the derived attribute in the cause set: 0/0.

Derived attributes: .

### Weak Rule Against the Labels

The Tier A class rule applied to the closing pull request of each labelled Tier B item.

| Tier B verdict | env-or-config-change | code-change | pull request not readable | closing commit | no closing reference |
|---|---|---|---|---|---|
| env-cause | 0 | 1 | 0 | 1 | 11 |
| code-defect | 0 | 7 | 0 | 8 | 17 |
| user-error | 0 | 1 | 0 | 0 | 6 |
| unclear | 0 | 10 | 0 | 7 | 72 |

Of the env-cause items with a readable closing pull request, 0/1 (0.000; 0.000-0.793) are classed `env-or-config-change`; of the code-defect items, 7/7 (1.000; 0.646-1.000) are classed `code-change`.

## Reproduction

Drift outputs are `drift triage --json` over `title + blank line + body` of each item, produced by `lane-run.sh` inside lane `r2` (141 outputs, sha256 in `heldout-output-hashes.txt`). Steps: `corpus_id.py drift-in --half heldout --tier-a`; sync `runs/heldout/drift-in` and `lane-run.sh` into the lane with `paisley-lane.sh sync`; `DRIFT=/work/bin/drift lane-run.sh runs/heldout/drift-in/b runs/heldout/drift-out/b` (and `/a`); copy `drift-out` back; `score.sh --half heldout`.

## Limits

Dev half only. n is small: 6 env-cause items with a named attribute, so every interval above is wide. Labels come from one model labeller per thread; the quote check is the only mechanical control. Only the first 100 comments of a thread were fetched. Tier A weak truth is a statement about the files a pull request touched. `upgrade-check` was not run. The gap phrases are rules over the output and the issue text, with a few hand-read notes marked as such.
