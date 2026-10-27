# Corpus Identification

Question: given a real issue as filed, does `drift triage` name the cause that the maintainers later gave?

Ground truth is what happened after the issue was filed: a later maintainer comment or the closing pull request states the cause. Drift never sees either; it reads the issue title and body as filed, and its answer is compared with that later statement.

The triage corpus holds titles and bodies of closed issues. The existing scores measure fact extraction against issue-form sections, the claim check against labelled corrections and rule derivation. None of them scores whether Drift, reading only the issue as filed, points at the attribute that the thread later blamed. This experiment samples closed issues whose maintainers state a reason in text, labels the stated cause, runs Drift on the issue text alone and reports how often its output names that cause.

The experiment has two tiers that are never pooled. Tier B is a few hundred threads in which a maintainer states a reason in text, labelled by reading the thread: strong truth, small n. Tier A is several thousand closed issues with a merged closing pull request, labelled by a deterministic rule over the files the pull request changed: weak truth, large n. A pull request that bumps a pin can still fix a code bug, so Tier A says how often Drift's output lines up with what the fix touched, and the agreement between the Tier A rule and the Tier B labels measures how weak that truth is.

Both tiers are built twice, once for each half of the corpus split (`sha256("drift-triage-split-v1:<repo>#<number>")`, first byte below 128 is dev). The dev half is what later work reads to improve the extractor and the rules. The held-out half is not read for any tuning: its threads, labels and Drift outputs are scored once per Drift version, and the result of a version is recorded in `latest.md` as that version's number. A score on the dev half is a development signal and is never reported as the result. The two halves use the same code, filters, ordering and caps, separate directories (`runs/dev/`, `runs/heldout/`) and separate batch and label files (`dev-NN`, `heldout-NN`).

The experiment reports numbers with denominators and intervals, and `results/corpus-id/latest.md` records them whatever they are. Drift is not changed during the experiment.

## Pre-Registration

This section was written before any thread was fetched or read. The fetch, the filter, the labelling and the scoring follow it as written. A change after the first fetch is recorded in `latest.md` under Deviations with the reason. The fetch was first written against the REST API, which cost one request per candidate and exhausted the hourly budget on one repository whose maintainers do not carry a public `MEMBER` association; before any label or Drift output existed it was rewritten on GraphQL with the closing reference taken from the `closed` event and the comment cap set to 100. Tier A and the Bars were added before any Tier A data was fetched; a Tier B test fetch of one repository had run by then, and no thread text had been read.

### Population

- Repositories: the 42 GitHub repositories of `triage/corpus.yml`. `gitlab-org/gitlab` is left out because the lane reads GitHub through `gh` only.
- Issues, not pull requests, with `created_at` on or before the manifest cutoff (2026-10-13), inside the newest 25,000 issues of the repository (the window the corpus builder keeps), in state `closed`, in the half under test (`--half dev` or `--half heldout`, default `heldout`). The split is `sha256("drift-triage-split-v1:<repo>#<number>")`, first byte below 128 is dev, the rest are held-out (`Calibrate.isDev`). Everything below applies to each half separately.
- Eligible: at least one comment by an author whose `author_association` is `MEMBER`, `OWNER` or `COLLABORATOR`, whose account type is not `Bot`, and whose login differs from the issue author's.

### Sample

- Candidates of a repository are ordered by `sha256("<repo>#<number>")`, ascending, over the hex digest. Ranking, recency and popularity play no part.
- `K = floor(1500 / 42) = 35` eligible threads per repository, taken in that order; a repository with fewer eligible threads contributes all of them. That caps the fetched threads at 1,470.
- Candidates that fail a test (pull request, open, outside the window, dev half, no eligible comment) are skipped and recorded in the fetch state with the reason. Their text is not kept.
- Every repository is sampled by number: the window start is found by bisection on `created_at`, and the numbers from there to the newest issue number that fall in the half under test are visited in hash order, 25 per `gh api graphql` call (`repository { issue(number: N) { ... } }` aliases; a pull request number returns nothing and is skipped).
- For an eligible thread the fetch keeps the issue (title, body, labels, state, state reason, author login and association, timestamps) and its first 100 comments (id, login, association, account type, timestamp, body; a thread with more comments keeps the first 100). The closing reference is the `closer` of the last `closed` event: a pull request (title and number) or a commit (first message line). Nothing else about a link is kept.
- Read only. GraphQL calls read `rateLimit { cost remaining resetAt }`; the tool sums the cost of the last hour, waits when the next call would pass 3,000 points and waits for `resetAt` when under 300 remain. The REST and search APIs serve only the newest issue number and the window start, under a rolling budget of 2,800 requests an hour.

### Cause Statement Filter

A thread is kept when the text of an eligible maintainer comment, or the closing reference title, matches one of these patterns (case-insensitive, fenced code blocks and quoted lines starting with `>` removed from a comment first, comments by the issue author and bots excluded):

```
caused by
due to
because of
regression
since (version|v?[0-9])
after (upgrading|updating)
downgrad
pin(ned)? to
fixed in
introduced in
broke in|broken in
work-?around
requires? (version|v?[0-9])
not supported (on|in)
(upgrade|update) to
(older|outdated|newer) version
(only )?works (on|with|in) (version|v?[0-9])
```

The number of threads that pass, per repository, is reported. The filter restricts the test to issues whose resolution states a reason in text; an issue closed with no stated reason is outside what this experiment can say.

### Ground Truth

A labeller reads the trimmed thread (see `LABELLING.md`) and gives one verdict:

| verdict | meaning |
| --- | --- |
| `env-cause` | the thread states a cause that is a change or difference in the environment: a tool, runtime or library version, platform or operating system, configuration key, environment variable, resource limit, locale, timezone, network or file system |
| `code-defect` | a defect in the project's own code, or a documented behaviour change of the project itself |
| `user-error` | the reporter's own mistake or misunderstanding |
| `unclear` | anything else; the default |

For `env-cause` the labeller gives the attribute in Drift's namespace (`tool.<name>.version`, `project.<ecosystem>.<package>`, `os.*`, `runtime.*`, `env.<NAME>`, `limits.*`, `browser.*`, `ci.*`) or `unnamed`, the before and after values when the thread states them, a verbatim quote of at most 40 words and the id of the comment it comes from. The scorer checks that the quote occurs in that comment (or in the closing title). An `env-cause` item whose quote fails the check counts as `unclear` in every primary table and is listed separately.

The attribute is labelled from the thread. The labeller never sees Drift's output and Drift never sees the thread past the title and body.

### What Drift Sees

Only the issue title and body as filed, never a comment: `drift triage --json` over `"<title>\n\n<body>"`, the text the corpus evaluator uses, with the shipped rule catalog and no learned store. The same bytes give the same output on every build and no model is involved. Where triage reports a failing and a passing `tool.<name>.version` for one tool, `drift upgrade-check` is also run and its rule ids are reported as a separate column; the primary measures use triage alone. The hash of each Drift output is committed with the labels.

### Measures

Each measure is computed over the `env-cause` items that carry a named attribute `A` and are not contaminated. The family of a key is `tool.<name>`, `project.<ecosystem>`, `env.<NAME>`, or the first segment for `os`, `runtime`, `limits`, `browser` and `ci`.

| measure | hit when |
| --- | --- |
| fact | triage prints a fact whose key is in the family of `A` (`fact-exact`: the key is `A`) |
| rule | a matched rule (any grade) has a detect key in the family of `A` (`rule-exact`: the key is `A`) |
| named | `A` appears exactly as a fact key, as a detect key of a matched rule or as a detect key of the draft rule |
| cause | `A` is a detect key of a matched rule or of the draft rule (a rule or draft is the only output that claims a cause; a fact only states the environment) |
| value | a fact with key `A` has a value that agrees with the stated before or after value (`8.1` agrees with `8.1.0`) |

A rule's detect keys are read from its file under `rules/`, found by the rule id. Each measure is reported with its count, its denominator and the Wilson 95% interval, for all items, for items whose attribute is in Drift's key table (`in-table`), per repository stratum (`large`, `small`) and per ecosystem (`strata.yml`). Items outside Drift's key table can only be named by luck, so the `in-table` rows are the ones that say whether the extractor and the rules work when they have the vocabulary.

Base rates and controls, all with intervals:

- the share of all kept threads where triage printed any fact, any rule, any draft rule;
- the same three shares on the `code-defect` and `user-error` threads, where there is no cause attribute and a hit is a coincidence;
- a mismatch control: each `env-cause` item scored against the attribute of another item (the items sorted by `sha256(id)`, each paired with the next, cyclically; pairs with the same attribute are dropped), which gives the rate at which an output names an arbitrary attribute.

Drift's cause set `C(item)` is the set of detect keys of its matched rules and of its draft rule, without `run:signature`. `cause` is `A in C(item)`.

If fewer than 100 `env-cause` items with a named attribute exist, the test is underpowered and the report says so beside the intervals. The sample is not enlarged to reach 100.

### Contamination

The extractor's confidences were set on the dev half; only the held-out half is used. The catalog's rules were written from documentation and release notes. An item is `contaminated` when a rule file cites the thread: the text of any file under `rules/` contains `<repo>/issues/<n>`, `<repo>/pull/<n>` or `<repo>#<n>` for the thread or its closing pull request, or `gh-<n>` for `python/cpython`. Contaminated items are listed, left out of the primary denominators and reported once as a sensitivity row.

### Tier A

- Source: `gh api graphql`, read only. For each of the 42 repositories the closed issues of the window (newest first, `created_at` on or before the cutoff, inside the window start used for Tier B) are listed 100 per call with `title`, `body`, `createdAt`, `closedAt`, the author login (hashed on disk with the corpus HMAC) and `closedByPullRequestsReferences(first: 2)` (merged pull requests only, `includeClosedPrs` left at its default `false`: `number`, `title`, `merged`).
- Every call reads `rateLimit { cost remaining resetAt }`. The tool sums the costs of the last hour and waits when the next call would pass 3,000 points, waits for `resetAt` when `remaining` is under 300 and resumes from `runs/tier-a/state/`.
- Eligible: closed, held-out half, at least one merged closing pull request. `closedByPullRequestsReferences` only holds pull requests that GitHub links to the issue, so issues fixed without a link are outside Tier A.
- Sample: eligible issues of a repository ordered by `sha256("<repo>#<number>")` as for Tier B; the first `Q = 600` per repository, all of them where fewer exist. The target is at least 5,000 issues in total. If the repositories hold fewer, the report gives the n reached and the number of eligible issues per repository.
- For the sampled issues the files of the first closing pull request come from `pullRequest(number) { changedFiles files(first: 30) { nodes { path } } }`, 25 per call.
- Counts reported per repository from the fetch: closed issues in the window, with a merged closing pull request, in the held-out half, sampled, and per Tier A class.

#### Weak Truth

Rules written before any Tier A result. The closing pull request's file list gives the class:

- `env-or-config-change` when it changed at most 30 files and every path is one of: a dependency manifest or lockfile (`package.json`, `package-lock.json`, `npm-shrinkwrap.json`, `yarn.lock`, `pnpm-lock.yaml`, `bun.lock`, `bun.lockb`, `requirements*.txt`, `constraints*.txt`, `Pipfile`, `Pipfile.lock`, `poetry.lock`, `pyproject.toml`, `uv.lock`, `Cargo.toml`, `Cargo.lock`, `go.mod`, `go.sum`, `Gemfile`, `Gemfile.lock`, `composer.json`, `composer.lock`, `pom.xml`, `build.gradle`, `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts`, `gradle.properties`, `libs.versions.toml`, `gradle-wrapper.properties`, `*.csproj`, `Directory.Packages.props`, `Directory.Build.props`, `global.json`, `packages.config`, `NuGet.config`); a tool version file (`.nvmrc`, `.node-version`, `.tool-versions`, `.python-version`, `.ruby-version`, `.java-version`, `.go-version`, `.sdkmanrc`, `rust-toolchain`, `rust-toolchain.toml`); a container file (`Dockerfile*`, `*.dockerfile`, `docker-compose*.yml`, `compose*.yml`); a CI file (`.github/workflows/*`, `.github/actions/**`, `.gitlab-ci.yml`, `.travis.yml`, `azure-pipelines*.yml`, `.circleci/config.yml`, `appveyor.yml`, `Jenkinsfile`); or a config file (`.editorconfig`, `tsconfig*.json`, `.eslintrc*`, `.prettierrc*`, `*.config.js`, `*.config.ts`, `*.config.mjs`, `*.config.cjs`, `.babelrc`, `tox.ini`, `setup.cfg`, `pytest.ini`, `.npmrc`, `*.properties`, `.gitattributes`).
- `code-change` for everything else, including a pull request with more than 30 files, since the unseen files are unknown. The report also counts the `code-change` pull requests whose files are all Markdown or under `docs/`.
- The attribute is derived only for `env-or-config-change` pull requests of at most 5 files, from the added lines of `GET /repos/{repo}/pulls/{n}/files`, by fixed parsers: `package.json` and lockfiles give `project.npm.<name>` (`engines.node` gives `tool.node.version`, `engines.npm` gives `tool.npm.version`); `requirements*.txt`, `constraints*.txt` and `pyproject.toml` give `project.python.<name>`; `go.mod` gives `tool.go.version` for the `go` line and `project.go.<module>` for a requirement; `Cargo.toml` gives `project.cargo.<name>`; `composer.json` gives `project.composer.<name>`; `.nvmrc` and `.node-version` give `tool.node.version`, `.python-version` gives `tool.python.version`, `.java-version` gives `tool.java.version`, `.go-version` gives `tool.go.version`, `.tool-versions` gives `tool.<name>.version`; a `FROM` line in a container file gives `runtime.container.image`; a workflow `node-version`, `python-version`, `java-version`, `go-version` or `dotnet-version` key gives `tool.<name>.version` and `runs-on` gives `ci.runner.label`; `libs.versions.toml` gives `project.gradle.kotlin.version` for `kotlin` and `project.gradle.agp.version` for `agp`; `gradle-wrapper.properties` gives `tool.gradle.version`. A pull request that yields exactly one key has that attribute; zero or several keys give `unnamed`.
- The weak truth is a statement about what the fix touched. It is not a verdict that the environment caused the issue.

#### Tier A Measures

The same `fact`, `rule`, `named` and `cause` measures, over the `env-or-config-change` items with one derived attribute, with the same families, Wilson intervals and strata. The null baseline is the `code-change` items: the share where triage printed any fact, any rule, any draft rule. The precision row is the share of items with a non-empty `C(item)` where the item is `env-or-config-change` and `A in C(item)`. Tier A numbers are reported in their own table and are not pooled with Tier B.

Agreement of the weak rule with the human labels is computed on the Tier B items whose closing reference is a merged pull request: the files of that pull request go through the same class rule and the report gives the cross-tab against the Tier B verdict, the share of `env-cause` items classed `env-or-config-change`, and the share of `code-defect` items classed `code-change`.

### Three-Way Classification

Every labelled item has one truth class: `environment` (verdict `env-cause`, with its attribute when named), `not environment` (`code-defect` or `user-error`) or `unclear`. Drift's answer is one of: it names a cause (a matched rule or a draft rule gives a non-empty cause set `C(item)`), it says the issue is not an environment difference, or it cannot tell (nothing of the two). The current binary has no output that says "not environment", so that column is zero by construction; the scorer stops when an output carries a top-level field it does not know, so a build that adds the answer cannot be scored by accident. The report gives the 3x3 table and the three-class accuracy: an `environment` item is correct when Drift names a cause and, if the attribute is named, `A in C(item)` (an unnamed attribute accepts any named cause); a `not environment` item is correct when Drift says so; an `unclear` item is correct when Drift cannot tell. Items of the three truth classes where Drift names a cause that is not the labelled one are errors. The dev-half table for the current binary is the baseline.

### Bars

Fixed before any held-out result. Bars 1 and 2 are on the held-out half, Tier B only.

1. Precision of `cause`: among items with a non-empty `C(item)` and a verdict other than `unclear`, the share that are `env-cause` with `A in C(item)` has a point estimate of at least 0.85 and a Wilson 95% lower bound of at least 0.65 (about 18 of 21 correct).
2. Three-class accuracy over all labelled held-out items has a point estimate of at least 0.80 and a Wilson 95% lower bound of at least 0.72 (n about 140).
3. Coverage: the share of `env-cause` items with a named attribute where `cause` holds is reported with its interval and no pass bar. If it is under 0.10, `latest.md` and the front page state that Drift's text triage does not identify most real causes.
4. The dev half has no bar. Its rows are development signals, and each is labelled as dev wherever it appears.
5. Tier A has no bar. Its rows are context for Tier B and are never used to pass or fail the claim.

Why these numbers: with n near 20, a Wilson lower bound of 0.60 passes a system that is only about 81% precise (17 of 21 correct), which is too lenient. A lower bound of 0.65 at a point estimate of 0.85 is the smallest bar that is stricter without being unreachable at this n. 0.90 precision is the usefulness target the claim is held to.

The numbers, the choice of `cause` over `named` as the bar measure, the choice of exact keys over families and the accuracy definition are the experiment author's proposals. Gregory ratifies them or changes them before the results are read as a verdict. The expected outcome is low coverage, and nothing is tuned toward a different one.

#### Amendment 2026-10-26

Made before any held-out Drift output and before any held-out Tier B label existed; the dev labels and the dev Drift outputs existed. Old bar 1: precision of `cause` with a Wilson lower bound of at least 0.60. New bar 1: point estimate at least 0.85 and lower bound at least 0.65. New bar 2, the three-class accuracy (point estimate at least 0.80, lower bound at least 0.72), did not exist before. Bars 3 to 5 are the old bars 2 to 4 renumbered. The amendment follows the coordinator's request for Gregory; the bars stay Gregory's to ratify.

#### Amendment 2026-10-26, held-out label consistency

Made after the held-out labels existed and before any held-out Drift output. Four labellers (batches 02, 03, 04 and 07) labelled a maintainer comment that only says "fixed in X", "will be fixed" or "thanks for catching that" as `code-defect`; `LABELLING.md` says a fix reference states no cause, and the labellers of batches 01, 05 and 06 labelled such comments `unclear`. The main session changed 16 labels to `unclear` (heldout-02: endstone#469, deno#26306, php-src#19771, deno#21921, deno#15603, deno#596, endstone#289; heldout-03: deno#21656, endstone#424, MobChip#79; heldout-04: automad#26; heldout-07: markdown#31, autopy#98, autopy#64, autopy#83, kotlin-telegram-bot#114) so the rule applies the same way in every batch. No Drift output was read when the labels changed.

#### Amendment 2026-10-27, ratified bars and the ask answer

Made by Gregory in conversation, after the held-out labels existed and before any held-out Drift output. The held-out half is scored once per Drift version.

Drift gets a fourth answer, an ask: a request to run or check something that names at least one concrete command or fact derived from the report (a tool or runtime the text names, or the operating system, runtime or version of the failing side) that the text does not already state. A generic "give more context" is not an ask and counts as cannot tell. The ask is a machine-readable field in `drift triage --json` and `drift why --json` (`plain.ask`, a list of `{family, command}`), so the scorer reads it and does not parse prose.

A correct answer, by label:

| label | correct answer | wrong answers |
| --- | --- | --- |
| env-cause with a named attribute | names that exact key, or asks for the check that reads the attribute's family | anything else, including not environment |
| env-cause, unnamed | an ask | not environment, a cause |
| code-defect or user-error | not environment | an ask, a cause, cannot tell |
| unclear | an ask | not environment, a cause |

Because an ask is wrong on code-defect and user-error, a tool that always asks can be right on at most the 89 unclear and 13 env-cause held-out items, 102 of 141 (0.72), which is under the 0.80 accuracy bar.

Bars (Gregory, 2026-10-27). Where his wording left a measure open, the reading used is stated and is his to correct before the held-out half is scored.

| measure | bar | now (dev, in-sample) |
| --- | --- | --- |
| exact attribute named, share of env-cause items with a named attribute | above 0.65 | 9 of 21 |
| facts for the labelled family, same denominator | above 0.70 | 10 of 21 |
| null baseline: share of non-environment items with no rule or draft printed (reading: higher is better, at least 0.50) | at least 0.50 | 138 of 143 |
| not environment, precision on its calls: point at least 0.95 and Wilson lower bound above 0.80 (Gregory's answer to the 2026-10-27 prompt), and no env-cause item ever called not environment | above 0.80 lower bound | 1 of 2 calls on dev tune and validate |
| dev solves, resolved share of the cause-bearing scenarios | at least 0.80 (90 of 112) | 73 of 112 |
| `cause` precision (amendment 2026-10-26) | point at least 0.85, lower at least 0.65 | 4 of 12 |
| three-class accuracy with the table above | point at least 0.80, lower at least 0.72 | not computed with asks |

The highest bar is not environment. A single env-cause item called not environment fails it.

### Limits Stated in Advance

- The filter selects threads where a maintainer wrote a reason. The result does not describe issues closed as stale, duplicate or fixed without comment.
- Labels come from one model labeller per thread and are not double-labelled; the quote check is the only mechanical control. A sample of the labels is reread by a person before the numbers are accepted.
- Facts that Drift does not model (a Docker version, a configuration key) cannot be named, so the all-items rows are bounded above by the in-table share.
- The population is closed issues of 42 repositories, mostly large trackers. Tier A leaves out issues that GitHub does not link to a merged pull request, and the 222,707-issue corpus figure includes GitLab, which neither tier reads.

## Layout

| path | content |
| --- | --- |
| `corpus_id.py` | the sampling tool: `sample`, `fetch`, `filter`, `batch`, `tier-a`, `drift-in`, `labels`, `check` |
| `fetch.sh`, `sample.sh`, `filter.sh`, `batch.sh`, `tier-a.sh`, `score.sh` | one wrapper per subcommand |
| `score.py`, `score.sh` | the scorer: reads labels, Drift outputs and rule files and writes `experiments/results/corpus-id/latest.md` |
| `replay.py`, `replay-lane.sh` | the ask and reply loop on the named environment-cause items (see Ask and Reply Loop) |
| `lane-run.sh` | runs `drift triage --json` over a directory of issue files (used inside a paisley lane) |
| `strata.yml` | repository to ecosystem |
| `LABELLING.md` | what a labeller reads, the verdicts, the YAML schema and a worked example |
| `batches/dev-NN.md`, `batches/heldout-NN.md` | trimmed, redacted threads, at most 20 per file, in order of `sha256("<repo>#<number>")` so a prefix of the batches mixes repositories (ignored; rebuilt by `batch.sh --half H`) |
| `labels/dev-NN.yml`, `labels/heldout-NN.yml` | labeller output (tracked) |
| `runs/` | raw fetched threads, request logs, fetch state, Drift outputs, with `meta/`, `list/` and `restlist/` shared by the halves and `dev/`, `heldout/` per half (ignored) |

## Ask and Reply Loop

`score.py` prints a table "Ask and Reply Loop" beside the ratified bars. It is not a bar and changes none of them. Stage 1 asks whether the first answer holds a text fact for the labelled family or an ask whose family is that family. Stage 2 answers that ask through the real `drift why --reply` with a synthetic reply: a hand-written template per family (`replay.py`, `TEMPLATES`) filled with the label's failing-side value, or a placeholder when the label has none, and asks whether the session then states a fact for the family and whether a matched rule reads the labelled key. Stage 2 measures ask coverage and the parse round trip. It does not measure diagnosis: the reply is made from the label, not read from a machine, and the harness moves the labelled ask to the front of the session, so ordering is not measured either. The label name `tool.powershell` is read as the Drift key `tool.pwsh`.

```sh
python3 experiments/corpus-id/replay.py prepare --half dev
experiments/corpus-id/replay-lane.sh a DIR
python3 experiments/corpus-id/replay.py reply --half dev
experiments/corpus-id/replay-lane.sh b DIR
experiments/corpus-id/score.sh --half dev
```

After stage `b`, `replay-lane.sh c DIR` answers the works-side step through `drift why --reply --works` with a reply built from the label's `before` value (a placeholder when the label has none) and the table gains a column "stage 3": how many replayed items end with the labelled family named as the difference. Stage 3 measures the same thing as stage 2, ask coverage and the parse round trip plus the comparison path, not diagnosis.

`replay-lane.sh` runs inside a lane with the linux `drift` executable in `DRIFT`; `DIR` is the replay directory copied into the lane.

## Clean Run Loop

`score.py` also prints "Clean Run Loop", again not a bar. It covers every dev item and asks how often Drift prints the `clean-run` ask (reach) and where the ask leads when it is answered through the real `drift why --reply`. The same-failure reply is a hand-written template per signature kind (`replay.py`, `SAME_TEMPLATES`: an npm error, a `SomethingError: ...` line, any other line) filled with the failure signature Drift extracted from the item's own body (the `run:signature` fact), so it exists only for items whose text carries a signature. The works reply is the one line `ok`. The table reports, on tune and validate separately, the share of code-defect and user-error items whose same-failure round trip ends in the not-environment reading, and for env-cause items the share whose works reply ends in the capture ask and the count that end in the not-environment reading, which must be 0. A same-failure reply on an env-cause item does end in the not-environment reading; that is the right reading when a clean machine reproduces the failure, and the count is recorded. Both replies measure ask coverage and the parse round trip. They do not measure diagnosis: nobody ran the command on a clean machine.

```sh
python3 experiments/corpus-id/replay.py prepare-clean --half dev
experiments/corpus-id/replay-lane.sh a DIR
python3 experiments/corpus-id/replay.py clean-reply --half dev
experiments/corpus-id/replay-lane.sh b DIR same
experiments/corpus-id/replay-lane.sh b DIR works
experiments/corpus-id/score.sh --half dev
```

`DIR` is `runs/dev/replay-clean`, copied into the lane.

## Running

```sh
experiments/run.sh corpus-id --check
experiments/corpus-id/sample.sh --repo php/php-src --first 5
experiments/corpus-id/fetch.sh --half dev
experiments/corpus-id/filter.sh --half dev
experiments/corpus-id/batch.sh --half dev
experiments/corpus-id/tier-a.sh --half dev
```

`--check` needs neither network nor Docker: it validates `strata.yml` against `triage/corpus.yml`, recomputes the split and the candidate order for known inputs and runs the filter and trimming on built-in examples. `fetch.sh` needs `gh` logged in, writes only under `runs/` and resumes from `runs/state/`. Environment: `GH_BUDGET` (requests per rolling hour, default 2800), `RUNS` (output directory).
