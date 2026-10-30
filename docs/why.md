# Why

`drift why` says why a program works in one place and fails in another. It needs no capture file, no
case directory and no flag for a first answer. The first line of its output names the flow it chose
from what you gave it:

| You type | Drift compares |
| --- | --- |
| `drift why` | this machine with the latest failed CI run of the git checkout you are in |
| `drift why --there PLACE` | this machine with a running container, another machine over ssh, or a saved capture |
| `drift why -- COMMAND` | this machine now with this machine the last time `COMMAND` passed |
| `drift why FILE` | nothing: it reads an issue or an error report and names the environment fact it points at |

Output is from the Linux x64 build. `drift help why` prints the same manual in the terminal.

## The Answer

Every answer has three parts: the verdict in one sentence, the evidence in at most three lines (what
it is, the value on each side) and what to try next. The wording follows how much the data supports:

- **A cause:** `It fails there because Node is 20.11.1 there and 22.4.0 here.` Drift says this only
  when a rule or a measurement ties that one difference to the failure.
- **The most likely cause:** one difference is left, or the error text of the failing command points
  at one. The sentence says which of the two it was.
- **Several differences that fit:** the verdict lists them, says the data cannot tell them apart and
  gives the fastest way to separate them: change one of them to match the working side and run the
  program again. `drift next .drift/why/case` prints the full experiment plan.
- **Nothing differs:** the answer says so, lists what Drift looked at and what it did not.

Unless a run decided it, Drift assumes the program works here and fails there. The verdict states
that assumption, and `--fails-here` swaps the sides.

## Compare With a Place

`--there PLACE` captures this machine and the place, compares them and explains the difference. A
place is one of:

- `docker:NAME`, a running container. Drift never starts a container and never changes one. It
  checks with `docker inspect` that the container is running, then reads each fact with one
  `docker exec` of a command that only reads: file contents, `env`, and the `--version` of each tool.
  Nothing is copied into the container, and the image needs only `cat` and `env`. `docker:NAME:/dir`
  reads the project files in `/dir`.
- `user@host` or `ssh://user@host`, another machine. This is the read-only ssh capture of
  `drift capture --ssh`: system files and the environment of a non-login session, with a one-line
  consent notice on standard error. Tool versions and project packages are not read over ssh, and
  the answer says so.
- a file written by `drift why --save FILE` on that machine, or a directory written by
  `drift capture --tools --project --out DIR`. Use this when the place cannot be reached from here:
  run `drift why --save there.json` there, copy the file, pass its path.

This example compares a machine with a saved capture of a shell that had `TZ` set and an open-file
limit of 64:

```sh
drift why --there there
```

```
drift why: this machine against the saved capture in there

Assuming it works here and fails there: 2 differences change together, so the data cannot separate
  them (Time zone (TZ), Open-file limit).

What I found:
  Time zone (TZ): not set here, America/New_York there
  Open-file limit: 1024 here, 64 there

What to try next:
  To tell them apart, change only Time zone (TZ) there so it matches here, and run it again. If it
    passes, that was the cause.
  The full experiment plan is saved: drift next .drift/why/case
  Paste the error message you saw: drift why --there there --error "..."
```

With a container, add the failing command after `--` to run it here and there. The two exit codes
decide the direction, and the error text of the failing side points at the kind of difference it
fits: "is not a function" fits a runtime version, "Too many open files" fits a limit, "not found"
fits a tool that is missing. A command can run only in a container. Over ssh and from a saved
capture Drift runs no repository code, so paste the error message instead (see below).

```sh
drift why --there docker:web -- node test.js
```

## On the Machine Where It Fails

When you can reach the machine where the program works from the machine where it fails, use
`--there`. When you cannot, make a capture on the failing machine and carry it over:

1. On the machine where it fails, save it: `drift why --save there.json`. This captures the
   machine the way `drift why` does, redacts the same things, and writes one JSON file. The
   command refuses to replace an existing file; add `--force` to replace it.
2. Copy `there.json` to the machine where the program works.
3. On that machine, run the command it prints: `drift why --there there.json`.

```
Saved this machine to there.json.
Copy it to the machine where the program works.
Then run this there: drift why --there there.json
```

The saved file is a plain capture, so `drift why --there` reads it the same way it reads the
output of `drift capture --tools --project`.

### Give It the Error Text

A saved file cannot run the program, so Drift cannot see the error it prints. Pass that text with
`--error`, which works wherever Drift compares two machines:

```
drift why --there there.json --error @error.txt
drift why --there there.json --error "TypeError: Object.groupBy is not a function"
cat error.txt | drift why --there there.json --error -
```

`@FILE` reads a file and `-` reads standard input. Drift reads the first 64 KiB of the text and says
so when it cuts. The text is read the way the output of `-- COMMAND` is read, so the phrases in the
error decide which difference is named first. A text with no known phrase leaves the answer as it
was. `--error` cannot be combined with `-- COMMAND`, because the command's own output is already the
error text.

A name in the error text also moves that difference to the front. `sh: 1: git: not found` names
`git`, so a missing `git` is the answer even when other tools are missing too. Environment
variables, locked packages and limits named in the text work the same way. One name gives the most
likely cause. Two names stay tied, and Drift says it cannot tell which one causes the failure.

This is the answer for a missing `git` in a container, from the saved-capture fixtures in
`fixtures/why`:

```
drift why: this machine against the saved capture in there.json

Assuming it works here and fails there: it most likely fails there because Git is not installed
  there (2.39.5 here); the error text points at it.

What I found:
  Git version: 2.39.5 here, not installed there (your error text names it: git)

What to try next:
  Install Git there, or put it on the PATH there.
  To be sure, change only that and run it again there.
```

## Read the Latest Failed CI Run

`drift why` with no argument, in a git checkout whose remote is on GitHub or GitLab, reads the latest
failed run of the current branch through `gh` or `glab`, so your own login does the authentication.
It compares this machine with that run, and compares the failed run with the last passing run of the
same workflow. A CI log prints only part of a runner's environment, so Drift compares the facts
present on both sides. When this machine shows no difference, the answer compares the two CI runs.
If `gh` or `glab` is missing or not logged in, the message names the one command that fixes it.

The output below replays recorded `git` and `gh` answers from public runs of this repository
([`fixtures/ci/live`](../fixtures/ci/live)); a live run prints the same layout for your own runs.

```
drift why: this machine against the latest failed CI run of gmitch215/Drift, branch master

Nothing on this machine differs from the failing CI run in what its log prints, so I compared the
  two CI runs. 4 differences change together, so the data cannot separate them (CI runner
  provisioner.build-date, CI runner provisioner.version, CI runner runner.image.version and 1 more).

What I found:
  CI runner provisioner.build-date: 2026-10-02T22:28:27Z when CI passed, 2026-10-12T17:48:21Z in CI
  CI runner provisioner.version: 20261002.596 when CI passed, 20261012.601 in CI
  CI runner runner.image.version: 20261004.327.1 when CI passed, 20261012.334.1 in CI

What to try next:
  To tell them apart, change only CI runner provisioner.build-date in CI so it matches when CI
    passed, and run it again. If it passes, that was the cause.
  The full experiment plan is saved: drift next .drift/why/case
```

## Run a Command Here

`drift why -- COMMAND` runs the command. If it passes, Drift saves this machine as the baseline in
`.drift/why/baseline`. If it fails and a baseline exists, the answer says what differs from the
baseline. The output of the command goes to standard error, so the answer stays on standard output.

```sh
drift why -- sh check.sh
TZ=Asia/Tokyo drift why -- sh check.sh
```

```
drift why: running sh check.sh here

It passed. I saved this machine as the baseline in .drift/why/baseline.

What to try next:
  When it fails, run the same command again: drift why -- sh check.sh
```

```
drift why: running sh check.sh here

It most likely fails now because the time zone differs: TZ is Asia/Tokyo now and not set when it
  last passed; the error text points at it.

What I found:
  Time zone (TZ): not set when it last passed, Asia/Tokyo now

What to try next:
  Unset TZ now, or make the code name its time zone.
  To be sure, change only that and run it again now.
```

A failure with no baseline says what Drift cannot do yet and what to do next: run the command where
it passes, or compare with a machine or container with `--there`.

## Read an Issue

`drift why FILE` reads an issue or an error report, and `drift why -` reads standard input. It is
`drift triage` with the same three-part answer.

```sh
drift why issue.md
```

```
drift why: reading the report in issue.md

The report points at git branch --show-current needs Git 2.22: Git 2.22.0 added the --show-current
  subcommand to "git branch", so an older Git rejects it as an unknown option.

What I found:
  Git version: not stated where it works, 2.20.1 where it fails

What to try next:
  Ask the reporter to check Git version (2.20.1) on the machine where it fails, and to send what is
    on the machine where it works.
  To compare the two machines, ask them to run drift why --save there.json on the machine where it
    fails and drift why --there there.json on the machine where it works.
  To be sure, change only that and run it again now.
```

The report states Git 2.20.1 and the error ``unknown option `show-current'``, and a recorded rule
(`git-branch-show-current-needs-2-22`) ties the two: the option arrived in Git 2.22. A report that
names a tool version and no rule explains it gets an ask instead;
[What To Run Next](#what-to-run-next) shows how.

A CI log is the commonest report. A GitHub Actions log, whether it is `gh run view --log` output
(`job<TAB>step<TAB>timestamp text`) or raw timestamped lines, loses its prefixes and colour codes and
is read as one block of output, so a version banner (`Python 3.9.18`, `PHP 7.4.33 (cli)`,
`ruby 3.0.2p107`, `go version go1.20.4`, `rustc 1.70.0`, `java version "11.0.2"`, `git version
2.31.1`, `Docker version 24.0.5`, or `v18.19.1` under `node --version`) is the version of that tool,
and a setup input such as `python-version: 3.9` is read as a stated version at the same confidence
as one in prose. When the log holds both, the banner wins. A tool version the report states is the
answer of the failing machine to the version check, so the next step is the same check where it
works:

```sh
drift why ci-job.log
```

```
drift why: reading the report in ci-job.log

The report names Python version 3.9.18 on the machine where it fails. I have no recorded rule that
  explains this failure from Python version 3.9.18 alone, so I cannot say whether it matters yet.

What I found:
  Python version: not stated where it works, 3.9.18 where it fails
  CI runner label: not stated where it works, ubuntu-24.04 where it fails

What to try next:
  Run python3 --version where it works. If you run it there with `| drift why --reply --works`,
    Drift prints one line to copy back to where you started; or copy the output, go back to where
    you started (the folder where you ran drift why), run `drift why --reply --works`, paste it and
    press Ctrl-D (Ctrl-Z then Enter on Windows).
  If you can reach that machine by ssh: `ssh USER@HOST python3 --version | drift why --reply
    --works`
```

With `Python 3.12.15` from the machine where it works, `drift why --reply --works` names the
difference. The log ends in a `SyntaxError: invalid syntax` under a Python frame, which also makes
Python the runtime when the log prints no banner:

```
drift why: comparing the answers from where it works and where it fails

It most likely fails where it fails because Python is 3.9.18 where it fails and 3.12.15 where it
  works; the error text points at it.

What I found:
  Python version: 3.12.15 where it works, 3.9.18 where it fails (your error text names it: python)

What to try next:
  Use Python 3.12.15 where it fails. Pin the version so the two stay equal, for example in
    .tool-versions or your CI image. If there is no version manager where it fails, change the image
    or package that provides Python.
  To be sure, change only that and run it again where it fails.
```

## An Error Text Alone

`drift why --error TEXT` with no file, no `--there` and no command reads the error text as a
report. In a git checkout on GitHub or GitLab the same line still reads the latest failed CI run. In
a folder that is not a checkout it falls back to the text, and the answer says so in its last line:
no CI run or second machine was available, so only the text was read, and `--there PLACE` adds the
comparison.

```sh
drift why --error "Error: too many open files while running node"
```

When the error text is all there is, Drift assumes the program works somewhere else (that is why you
are comparing) and says what to run on the machine in front of you. It does not call a failure
"not an environment difference" from the text alone in this flow, and the capture is the second step
after the first answer. The error line below names no tool, but only Python prints it:

```sh
drift why --error "ModuleNotFoundError: No module named 'distutils'"
```

```
drift why: reading the error text

I found no environment fact in this error text (no tool version, operating system or setting), so I
  cannot say what it points at.

What to try next:
  To tell, run python3 --version where it fails and paste the result: the error text is Python's,
    but the report does not say which version runs it.
  In one step: python3 --version | drift why --reply
  No CI run or second machine was available, so I read only the error text. To compare with another
    machine, add --there PLACE.
```

```sh
python3 --version | drift why --reply
```

```
drift why: reading the report with your answers

The report points at Python 3.12 removes distutils and stops installing setuptools in venvs: Python
  3.12 deletes the distutils package from the standard library and venv no longer pre-installs
  setuptools, which still provides distutils when installed.

What I found:
  Python version: not stated where it works, 3.12.1 where it fails

What to try next:
  To compare the two machines, run drift why --save there.json on the one where it fails and drift
    why --there there.json on the one where it works.
```

When no rule matches the answer, the next step is the capture (`drift why --save there.json`) with
its one-step form. A command that is not found is asked for by name, and the answer is read as
installed or not:

```
To tell, run command -v git where it fails and paste the result: the error says git was not found,
  but not whether it is installed where it fails.
In one step: command -v git | drift why --reply
```

An empty answer means the command is not installed; the report then says "Git is not installed on
the machine where it fails" and lists "Git: not stated where it works, not installed where it
fails". With the answer from the machine where it works (`command -v git | drift why --reply
--works`) the line reads "Git: installed where it works, not installed where it fails".

`--error` with an issue file is refused, because the two disagree about what to read. Drop
`--error` to read the file as a report, or drop the file to read the error text.

## What To Run Next

A report that names a tool and not its version, a system and not its release, or a limit that was
hit and not its value leaves facts out. When no rule, no draft rule and no program-side reading
answers, Drift says which fact it needs and gives the command that prints it. It asks for what the
text does not state and nothing else: a version the report already gives is never asked for again,
and the report has to name the tool, the system or the symptom first. A request for "more context"
is not an ask.

Every command comes from a fixed table. A variable name is taken from the text only when it is in capitals and does not look like a secret (a name holding key, token, secret or pass is never asked for), and `PATH`, `HOME` and similar are left out:

| The report | Drift asks for | Command |
| --- | --- | --- |
| names a tool without a version | the tool's version | the tool's version command, such as `node --version` or `php -v`, the same one `drift capture --tools` runs |
| says too many open files | the open-file limit | `ulimit -n` |
| names Linux, macOS or Windows without a version, or runs a package manager that implies one | the release | `cat /etc/os-release`, `sw_vers -productVersion` or `ver` |
| is about the kernel | the kernel release | `uname -r` |
| is about a time zone or a locale | the setting | `printenv TZ` or `locale` |
| names ssl, tls, curl or a certificate | the TLS library | `openssl version` |
| says no space left on the device or a disk quota was hit | the disk use | `df -h .` |
| says PHP lacks an extension or something is missing | the PHP extensions | `php -m` |
| names an environment variable by its capitals, such as `$DATA_DIR` | its value | `printenv DATA_DIR` |
| names a Bukkit-family server (Paper, Spigot, Purpur) | its version | `/version` in the server console |
| shows an error that only one runtime prints (a Python `ModuleNotFoundError`, `ImportError` or `Traceback (most recent call last)`; a Node `Cannot find module` or `ERR_` code; a PHP `Fatal error: Uncaught` or `Call to undefined function`; a JVM `Exception in thread` or `java.lang.` name; a Go `panic:` with `goroutine` or `cannot find package`; a Rust `error[E` or `thread 'main' panicked`; a Ruby `NoMethodError` or `LoadError`; a .NET `Unhandled exception. System.`) | that runtime's version | its version command, such as `python3 --version`, even when the text never names the runtime |
| says a command was not found (`bash: git: command not found`, `command not found: git` or `No such file or directory: 'git'`) | whether it is installed | `command -v git` |
| says it works somewhere else | a capture of the failing machine | `drift why --save there.json` |
| shows a command, a failure signature and a toolchain with an official Docker image | the same command on a clean machine | a contained `docker run ... IMAGE sh -c 'cp -R /src/. /w/ && COMMAND' 2>&1`, printed and never run |
| shows a command and no error | the error text | `COMMAND 2>&1`, to pipe to `drift why --reply` |

A runtime is inferred only from its own vocabulary, never from a path or a generic word: `TypeError: x is not a function` alone, `panic:` without a goroutine, `Fatal error: out of memory` and `/usr/lib/python3/site.py` name no runtime. A name with a dot or a slash (`'config.json'`, `'/etc/app/x'`) is a file, not a command. Neither ask appears when the text already states that tool's version.

A text answer shows one ask at a time, the most useful first, with the one-step form:

```
To tell, run node --version where it fails and paste the result: the report names Node but not
  which version fails.
In one step: node --version | drift why --reply
```

`drift why --json` and `drift triage --json` list up to three asks, each as `family`, `command` and
`piped`, in `plain.ask` and in a top-level `ask`, and the clean-run ask beside them when there is one. `family` is the attribute family the command reads
(`tool.node`, `limits.nofile`, `os.release`, `env.TZ`), and the key is absent when there is nothing
to ask.

### Answering

`drift why` writes `.drift/why/session.json` whenever it asks: the report text with secrets and home
directories masked, the asks, and the facts each answer gave. `--session FILE` keeps it somewhere
else, and the one-step form then carries `--session FILE`.

`drift why --reply` reads standard input as the answer to the first ask that has none, reads it the
way the matching scanner reads that command, adds the fact to the report and answers again. The
answer is a cause, a not-environment reading or the next single ask.

```sh
node --version | drift why --reply
npm --version | drift why --reply
```

Nothing leaves the machine, and the session file keeps the facts it read, never the pasted text. The
same input gives the same bytes on every target. Input that is not the output of the asked command
is refused with the command to run, exits with status 1 and leaves the session as it was. An empty
answer to `printenv NAME` means the variable is not set. The server console answer cannot be piped, so
save it to a file and give Drift the file: `drift why --reply < console-output.txt`. The answer to `drift why --save` is the saved
file: `drift why --reply < there.json` reads the tool, system, limit and time zone facts in it.
`--reply` fails with a message that says how to start a session when there is none, and it takes no
argument other than `--session`, `--json` and standard input.

### Comparing One Fact

A report that leaves one fact open can be settled without copying a whole capture. After the answer
from the machine where it fails, and when no rule explains the failure, Drift asks for the same
check where the program works. With the error line `Fatal error: Uncaught Error: Call to undefined
function str_contains()` and `php -v` run on both machines:

```sh
php -v | drift why --reply
```

```
drift why: reading the report with your answers

The report names PHP version 7.4.33 on the machine where it fails. I have no recorded rule that
  explains this failure from PHP version 7.4.33 alone, so I cannot say whether it matters yet.

What I found:
  PHP version: not stated where it works, 7.4.33 where it fails
  PHP interface (sapi): not stated where it works, cli where it fails

What to try next:
  Run php -v where it works. If you run it there with `| drift why --reply --works`, Drift prints
    one line to copy back to where you started; or copy the output, go back to where you started
    (the folder where you ran drift why), run `drift why --reply --works`, paste it and press Ctrl-D
    (Ctrl-Z then Enter on Windows).
  If you can reach that machine by ssh: `ssh USER@HOST php -v | drift why --reply --works`
```

The session lives in the folder where you ran `drift why`, which is usually the machine where it
fails. Run the check where it works and pipe it to `drift why --reply --works` as the step says. That
machine has no session, so Drift reads and masks the answer and prints one command that carries it
back, with the output as quoted arguments (exit status 0):

```
There is no session here, so I cannot compare yet. The session is in the folder where you ran drift why. Run this there:
printf '%s\n' 'PHP 8.3.12 (cli) (built: Sep 24 2024 00:00:00) ( NTS )' 'Copyright (c) The PHP Group' | drift why --reply --works
```

Run that line in the folder where you ran `drift why --error`. The text is redacted first and
control and non-ASCII characters become `?` or are dropped, so nothing in it can be run by the shell.
An answer longer than 2 KiB gets no printed command: save it to a file and use
`drift why --reply --works < FILE`. An answer that is not the output of a check Drift asks for is
refused (exit status 1). `--session FILE` is added to the printed command only when you passed it.
`--reply` without `--works` behaves the same for the failing side. Pasting also works: run the
command in the folder with the session, paste the output and end the input (Ctrl-D in a terminal,
Ctrl-Z then Enter on Windows), or pipe it over ssh:

```sh
ssh USER@HOST php -v | drift why --reply --works
```

```
drift why: comparing the answers from where it works and where it fails

It most likely fails where it fails because PHP is 7.4.33 where it fails and 8.3.12 where it works;
  it is the only difference I found.

What I found:
  PHP version: 8.3.12 where it works, 7.4.33 where it fails

What to try next:
  Use PHP 8.3.12 where it fails. Pin the version so the two stay equal, for example in
    .tool-versions or your CI image. If there is no version manager where it fails, change the image
    or package that provides PHP.
  To be sure, change only that and run it again where it fails.
```

`--works` reads standard input as the answer to the first check that has an answer from the failing
machine and none from the working one, or, when none has, the first check without either, so the two
sides can come in any order. With `--session FILE` the form carries it. Run in a folder with no
session, it prints the one command that carries the answer there (see above). With both
sides Drift compares them the way `--there` compares two
machines, with the same ranking and the same error-text pointers. When the fact is the same on both
machines it says so and moves on to the next check, then to the capture. `--works` needs a session
and a check that can be run on both machines (not the capture, the clean run or a server console
command); without one it says what to run first. The session keeps the facts each side gave
(`reply` for the failing side, `works` for the working side), never the pasted text, and the piped
form carries `--session FILE` as for `--reply`. When the comparison lists other differences that the
error text does not point at, one more line says so, for example "12 other differences exist, but the
error text does not point at them: Git version is the only one it names."; a single difference keeps
the wording above.

### Asking a Clean Machine

When a report shows a command, the failure it printed and a toolchain with an official Docker image,
the quickest way to rule the machine out is to run the same command on a clean one. Drift prints that
command and never runs it. The report here has a `$ node server.js` line and an error:

```sh
drift why issue.md
```

```
drift why: reading the report in issue.md

I found no environment fact in this report (no tool version, operating system or setting), so I
  cannot say what it points at.

What to try next:
  To tell, run the command from the report in a clean container and paste what it prints (the
    command comes from the report, so read it before you run it): the report shows the command
    failing, and a clean node:26.12.1-bookworm-slim container (it states no version, so this is the
    newest) shows whether the failure depends on your machine.
  In one step: docker run --rm --user "$(id -u):$(id -g)" --cap-drop ALL --security-opt
    no-new-privileges --pids-limit 256 --memory 1g --network none -v "$PWD":/src:ro --tmpfs
    /w:exec,mode=1777 -w /w node:26.12.1-bookworm-slim sh -c 'cp -R /src/. /w/ && node server.js'
    2>&1 | drift why --reply
```

The command comes from the report, so read it before you run it. Only that command is the report's:
it goes inside one pair of single quotes, and everything else is fixed text. The container gets the
working directory read-only at `/src` and a copy of it in a memory-backed `/w`, runs as your user with
no added privileges (`--cap-drop ALL`, `no-new-privileges`), at most 256 processes and 1 GB of memory,
and has no network. The network stays on when the report shows network use (a word such as http, url,
install, clone, fetch or download) or when the command is a package or build manager (`npm`, `pip`,
`cargo`, `gradle` and similar).

The ask needs three things in the text: a failure signature (an error line such as `Error: ...` or
`error: ...`), a command and a toolchain with an official image, named by the command's program or,
for a script, by the language the text names. A command is one of:

- a line in a code block that starts with `$`, `>` or `%` (the last two only when the program is a
  known one, so `> Task :app:compileJava` and `> pkg@1.0.0 test` do not count);
- the one command of a code block that comes before the block with the error;
- an inline code span that follows a run verb ("run", "ran", "running", "executing", "typing",
  "command:") and whose program Drift knows, as in ``Run `node server.js` and it stops``.

The command is copied as written. Drift prints no ask for a command that is empty, longer than 400
characters, holds a backtick, `$(`, a masked secret or a character outside ASCII, or ends in a
backslash. The tag is a published one: the version the report states for that toolchain (the newest
published tag with that prefix, or the nearest one of the same major version), or the newest published
tag when it states none; a version with no published tag gets no ask.

A report with a command and no error cannot be compared, so Drift asks for the error text first. The
`COMMAND` stands for the command in the report:

```
To tell, run the command from the report where it fails and paste what it prints (the command comes
  from the report, so read it before you run it): the report shows a command but no error it
  printed, and without one a clean run cannot be compared.
In one step: COMMAND 2>&1 | drift why --reply
```

The pasted output is added to the report and Drift answers again, now with the clean-run ask.

Run the command and pipe what it prints. The answer is compared with the failure in the report, after
paths, numbers and times are normalised:

```
drift why: reading the report with your answers

It fails the same way on a clean machine, so your environment is not the cause; look at the program
  or the input.

What I found:
  The clean run printed the same failure as the report: Error: Cannot find module './config'

What to try next:
  Start from the input the command reads and the code that handles it; the failure does not depend
    on this machine.
```

If the clean run prints no error, the difference is on the machine where it failed, and the next ask
is the capture:

```
drift why: reading the report with your answers

It works on a clean machine, so something on your machine differs.

What I found:
  The clean run printed none of the errors the report shows.

What to try next:
  To tell, run drift why --save there.json where it fails and feed the file to Drift: it records the
    tools, settings and limits of that machine, which the clean run shows to differ from yours.
  In one step: drift why --save there.json && drift why --reply < there.json
```

An output with no error line is a pass, including an empty one. An output with an error that is not the
report's keeps the ask open and says what differed. Docker's own errors (the daemon is not running,
an image cannot be pulled, docker is not installed) and binary output are refused like any other
unreadable answer. The session file keeps only the result (`same` or `works`), never the pasted
output; the error text of the first reply is the one exception, since it becomes part of the report
(with secrets masked).

## When It Is Not the Environment

Some failures come from the program. When the report or the error text points at the program and no
machine difference explains it, Drift says so in one sentence, with the reason and where to look next.

The issue flow (`drift why FILE` and `drift triage`) reads the text for one of three kinds of signal. Each
kind has its own condition on what the report may say about the machine:

- Frame, mismatch and regression need a report that states no environment fact on the failing side: no
  tool version, operating system, platform, setting or limit tied to the failure.
  - a stack frame in your code, such as `at test.js:3:8` or `File "src/app/parser.py", line 42`.
    Frames in `node_modules`, `site-packages`, the JDK or `/usr` do not count.
  - an expected value and an actual value that differ: `expected 3 but got 4`, an `AssertionError`,
    or `Expected:` and `Actual:` lines. A Markdown heading or a bold label does not count.
  - the word regression.
- Crash may state the operating system and nothing else about the machine, since every bug template
  asks for it. The report says it crashed and shows an exception, a traceback, a segmentation fault, an
  access violation or a stack trace.
- A statement that the program itself is wrong may state tool versions and the operating system. It
  needs a report that tells no story of change: no passing side, no required version, no limit that was
  hit, no "works on", "worked before", "since upgrading" or "after the update". The statements are:
  - `logic`: the program computes the wrong thing (false positives, false negatives, "incorrectly",
    "silently ignores", an off-by-one).
  - `null-deref`: a null or undefined dereference (`Cannot read properties of undefined`,
    `NullPointerException`).
  - `bounds`: an index out of range or out of bounds.
  - `memory`: a memory leak, a double free or a use after free.
  - `not-shown`: an output that does not appear (not rendered, not displayed, not saved, "doesn't
    show", disappears).
  - `located`: the report already names the place in the project: a typo, a wrong link or target,
    `line 398 of upload.php` or a permalink to a source line.

The capture flows (`--there`, `-- COMMAND` and the CI flow) need the same kind of signal in the error
text, and no difference may fit that text by name or by phrase. Then Drift says
`No environment difference explains this.` and names the frame it read.

The answer stays quiet otherwise. It never overrides a named cause: a difference the error text points
at, a phrase that names a runtime, module, tool, limit or locale, a rule that matches, or a draft rule. Text with no
signal keeps the answer "cannot tell". Most bug reports state a platform or a version, so this answer
is uncommon. Treat it as a pointer to the code, not a verdict.

A report with an expected and an actual value and a frame in the project's code gives this answer:

```sh
drift why issue.md
```

```
drift why: reading the report in issue.md

This does not look like an environment difference. The report points at your own code
  (src/count.js:12): start there.

What I found:
  The report has a stack frame in your code: src/count.js:12.

What to try next:
  Open src/count.js:12 and check the values the code gets there.
```

In JSON, `drift why --json` adds `"kind": "not-environment"` to `plain`, and `drift triage --json` adds
a `notEnvironment` object with `signal` (`frame`, `mismatch`, `regression`, `crash`, `logic`, `null-deref`,
`bounds`, `memory`, `not-shown` or `located`), `line`, `frame` and `text`. Both are absent when the answer
does not apply.

## What Drift Reads

`drift why` turns on the toolchain scanners and the project scan, so a first answer needs neither
`--tools` nor `--project`. It reads tool versions, including the tools the project names in its
scripts, Makefile, Dockerfile and CI workflows; environment variables; resource limits such as open
files and process counts; memory and CPU limits; the kernel settings for memory maps, file handles
and processes; the clock source; the time zone and locale (including the locales the system has
installed); the operating system release; the shell behind `/bin/sh`; the file system type of
`/tmp`; the host names in `/etc/hosts`; the network interface names; whether IPv6 addresses are configured; the
CPU affinity; and the locked package versions of the project in the working directory.

For DNS it reads only the local resolver file, `/etc/resolv.conf`, and keeps counts and flags: how
many nameservers it lists, whether all of them are local, how many search domains it has, and which
options are set. Drift never queries DNS, and it keeps no address or domain name from that file. It
does not read your source code or data files, or network traffic. It does not time the disk either:
the write-and-sync probe runs only with `drift capture --probes`. When nothing differs, the answer lists what it looked at and what it
did not. Host names, accounts, terminal variables and
`PATH` differ between any two machines and are left out of the comparison, and an environment
variable such as `NODE_VERSION` that only repeats a tool version is dropped when the tool fact is
there.

## In Drift Studio

The Why tab in Studio runs the same logic as a conversation. Paste an error message, an issue text, a
CI log or a saved capture, and the answer comes back in the same three parts with how sure Drift is.
Each paste shows what Drift took it for, and an error message or issue text can be read the other way
with one button. A browser cannot run the check Drift asks for, so the check appears in a card: copy
the command, run it where the program fails and paste what it prints, or paste what it prints where
the program works. Two CI logs are compared like `drift import` does. Nothing is written to disk or
sent anywhere; Export Conversation copies the transcript and New Conversation clears it.

## Options

| Option | Effect |
| --- | --- |
| `--there PLACE` | compare with `docker:NAME`, `user@host` or a saved capture |
| `--fails-here` | the program fails here and works there |
| `--error TEXT` | the error the program printed; alone, outside a checkout, it is read as a report |
| `--reply` | read standard input as the answer to the first open ask |
| `--session FILE` | the session file (default `.drift/why/session.json`) |
| `--detail` | add the full ranking and plan after the answer, in the terms of `drift diagnose --detail` |
| `--json` | print the machine output of `drift diagnose` with a `plain` object that holds the same three parts |

The same inputs give the same bytes. The exit status is 0 when Drift gave an answer, including "cannot
tell", and 1 when it could not reach a place or a tool. Each message of that kind ends with what to
do next:

```
this directory is not inside a git checkout. drift why with no arguments reads the latest failed CI run, which needs a git checkout whose remote is on GitHub or GitLab. Other ways in: drift why --there PLACE, drift why -- COMMAND, or drift why FILE with an issue or an error text.
```

```
cannot read make as an issue or error text. To run a command, put -- before it: drift why -- make test
```
