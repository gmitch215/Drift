# Labelling

A labeller reads the threads of one batch file (`batches/dev-NN.md` or `batches/heldout-NN.md`) and writes the matching `labels/dev-NN.yml` or `labels/heldout-NN.yml`, one entry per thread. The labels are the truth that `drift triage` is scored against, so they record what the thread says, not what the labeller knows.

## What You Read

Each thread has a header (labels, dates, the author's hashed id, the closing reference), the issue as filed, and the maintainer (`MAINT`) and author (`AUTHOR`) comments. Comments by other users are dropped, and long text is cut with a `[... N words cut ...]` marker. Logins are replaced by `u` and twelve hex digits. A comment heading carries its id: `### Comment 123456789 [MAINT u3f9a... ] 2021-04-02`.

## Verdicts

One verdict per thread. The first that holds wins; `unclear` is the default.

| verdict | when |
| --- | --- |
| `env-cause` | a `MAINT` comment or the closing reference states a cause that is a change or difference in the reporter's environment: a tool, runtime or library version, platform or operating system, configuration key, environment variable, resource limit, locale, timezone, network or file system |
| `code-defect` | a `MAINT` comment or the closing reference states a defect in the project's own code, or a behaviour change of the project itself that the project documents |
| `user-error` | a `MAINT` comment states that the reporter's own usage, setup or understanding was wrong and no environment difference is the cause |
| `unclear` | the thread gives no stated cause, two causes of equal weight, or a cause you would have to infer |

Rules:

- The cause must be stated in the text. An `AUTHOR` guess counts only when a `MAINT` comment or the closing reference agrees with it.
- A version bump of the project's own dependency that the project then adapts to is `code-defect` when the maintainers changed the project's code, and `env-cause` when the thread names the changed version as the reason and the fix is to pin, upgrade or configure it on the reporter's side.
- Never use your own knowledge of the project to decide. If the thread does not say it, the answer is `unclear`.
- Do not read the closing reference as a verdict by itself. A pull request title that says `fix crash` states no cause.

## Attribute

Only for `env-cause`. Choose one key in Drift's namespace for the cause the thread blames for this reporter's failure:

| key | names |
| --- | --- |
| `tool.<name>.version` | the version of a tool or runtime: `tool.node.version`, `tool.python.version`, `tool.java.version`, `tool.kotlin.version`, `tool.gradle.version`, `tool.php.version`, `tool.go.version`, `tool.rust.version`, `tool.git.version`, `tool.dotnet.version`, `tool.npm.version`, `tool.pip.version`, `tool.docker.version`, `tool.cc.version`, `tool.libc.version` and any other tool in the same form with a lowercase name |
| `project.<ecosystem>.<package>` | a dependency version in the reporter's project: `project.npm.react`, `project.python.numpy`, `project.composer.symfony`, `project.maven.<group>:<artifact>`, `project.gradle.kotlin.version`, `project.gradle.agp.version` |
| `os.name`, `os.version`, `os.arch`, `os.release.ID`, `os.release.VERSION_ID` | operating system, release or CPU architecture |
| `runtime.platform`, `runtime.container.image`, `runtime.jvm.options` | the platform, a container image, JVM options |
| `browser.name`, `browser.version` | the browser |
| `ci.provider`, `ci.runner.label` | the CI system or runner image |
| `env.<NAME>` | an environment variable, such as `env.LANG`, `env.TZ`, `env.PATH` |
| `limits.<name>` | a resource limit: `limits.memory`, `limits.open-files`, `limits.timeout` |
| `config.<key>` | a configuration key or setting that is not one of the above |
| `unnamed` | the thread states an environment cause but no attribute fits, or several fit equally |

Give `before` and `after` as strings when the thread states the value that worked and the value that failed (`before: "18.19.0"`, `after: "20.11.1"`); otherwise `null`.

## Quote and Comment

`comment` is the integer id from the `### Comment <id>` heading that states the cause, or the word `closing` for the closing reference title, or `issue` for the issue text as filed (only for a `user-error` or `unclear` verdict). `quote` is a span of at most 40 words copied exactly from that one comment: same words, same order, whitespace may differ, no ellipsis and no text from two places. The validator searches for the quote in the original comment and rejects the entry when it is not there. For `unclear`, `quote` and `comment` may be `null`.

## Output

A YAML list with one mapping per thread, in batch order, with exactly these keys: `id`, `verdict`, `attribute`, `before`, `after`, `comment`, `quote`, `note`. Write every string with double quotes and escape a double quote inside it as `\"`. Write `null` for an absent value. `note` is at most 20 words saying why, or `null`.

Check the file with `python3 -I experiments/corpus-id/corpus_id.py labels --half dev NN` (or `--half heldout`); fix every problem it prints.

## Worked Example

The thread below is invented.

```
## 03. example/tool#412
labels: bug | opened 2022-03-01 | closed 2022-03-04 (completed) | author u0a1b2c3d4e5f
closing reference: none

### Issue
Title: Build fails with "unknown option --legacy-peer-deps"

I run `npm install --legacy-peer-deps` in CI and it fails. It worked last week.

### Comment 1100200300 [MAINT u9f8e7d6c5b4a] 2022-03-02

The CI image moved to Node 14 on Monday. That flag needs npm 7 or newer, and Node 14 ships npm 6.
Pin the job to Node 16 and it works.
```

```yaml
- id: "example/tool#412"
  verdict: "env-cause"
  attribute: "tool.node.version"
  before: "16"
  after: "14"
  comment: 1100200300
  quote: "That flag needs npm 7 or newer, and Node 14 ships npm 6."
  note: "maintainer blames the CI image moving to Node 14"
```

An issue whose maintainer writes "this was a bug in our parser, fixed in the linked change" is `code-defect` with `attribute: null`. An issue answered with "the answer is in the documentation, you passed the option twice" is `user-error`. An issue closed with "can't reproduce, please reopen with more details" is `unclear`.
