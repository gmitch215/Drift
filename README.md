<div style="display: flex; align-items: center; flex-direction: column;" align="center">
    <img align="center" style="align-self: center; max-width: 256px" src="assets/drift.png" width="30%" alt="Drift logo" />
    <h1 style="text-align: center;">Drift</h1>
    <p style="text-align: center;">Why it passes here and fails there</p>
    <div align="center">
        <img src="https://img.shields.io/github/actions/workflow/status/gmitch215/Drift/build.yml?label=build" alt="build">
        <img src="https://img.shields.io/github/license/gmitch215/Drift" alt="license">
        <img src="https://img.shields.io/codecov/c/github/gmitch215/Drift" alt="codecov">
        <img src="https://img.shields.io/github/stars/gmitch215/Drift?style=flat" alt="stars">
        <img src="https://img.shields.io/github/commit-activity/t/gmitch215/Drift?color=violet" alt="commit activity">
    </div>
</div>

---

Drift tells you why your program works in one place and fails in another.

Give it the machine where the program fails and the machine where it works, a CI log, or an error message. Drift compares what differs, names the cause when the data supports one, and says what to try next.

## Try It

Install Drift. Open a new terminal if `drift` is not found afterwards.

```sh
curl -fsSL https://raw.githubusercontent.com/gmitch215/Drift/master/install.sh | sh
```

There are two ways in. If you can run commands on the machine where the program fails and on the machine where it works, skip to **Compare two machines** further down: it needs three commands. If you have only the error message, or only the failing machine, start with the error.

**Start with the error.** On the machine where the program fails, paste the line that says what failed: for a Python traceback, the last line; for Node, the first line that starts with the error name.

```sh
drift why --error "Fatal error: Uncaught Error: Call to undefined function str_contains()"
```

```
drift why: reading the error text

I found no environment fact in this error text (no tool version, operating system or setting), so I
  cannot say what it points at.

What to try next:
  To tell, run php -v where it fails and paste the result: the error text is PHP's, but the report
    does not say which version runs it.
  In one step: php -v | drift why --reply
  No CI run or second machine was available, so I read only the error text. To compare with another
    machine, add --there PLACE.
```

Drift does not stop at "I don't know". It names one check, and you pipe the result back to continue the same session. Run the check where the program fails:

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

The program works on another machine, so Drift asks for the same check there. Run it on that machine and pipe it into Drift. The session lives in the folder where you started, so Drift prints one line for you to copy back:

```sh
php -v | drift why --reply --works
```

```
There is no session here, so I cannot compare yet. The session is in the folder where you ran drift why. Run this there:
printf '%s\n' 'PHP 8.3.12 (cli)' | drift why --reply --works
```

Run that line on the machine where you started. Drift then compares the two sides:

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

Drift keeps the session in `.drift/why/session.json` in the current directory, so each `--reply` picks up where the last command stopped. It stores what it read from your answer, not the text you piped in.

**Compare two machines.** Use this when a program passes on your laptop and fails on a server, or when Drift asks for the second machine. Install Drift on both, then run `drift why --save there.json` on the machine where the program fails, copy the file to the machine where the program works, and run `drift why --there there.json` there. The saved file holds the tool versions, environment variables and limits of the failing machine, with secret values hidden. Drift writes it in the current directory and prints its full path.

```sh
drift why --save there.json
```

Use the command below on the machine where the program works. A saved file cannot run the program, so pass the error message the program printed. Paste the line that says what failed: for a Python traceback, the last line; for Node, the first line that starts with the error name.

```sh
drift why --there there.json --error "TypeError: Object.groupBy is not a function"
```

```
drift why: this machine against the saved capture in there.json

Assuming it works here and fails there: it most likely fails there because Node is 20.20.2 there and
  22.23.3 here; the error text points at it.

What I found:
  your error text points at this: is not a function
  Node version: 22.23.3 here, 20.20.2 there

What to try next:
  Use Node 22.23.3 there. Pin the version so the two stay equal, for example in .tool-versions or
    your CI image. If there is no version manager there, change the image or package that provides
    Node.
  To be sure, change only that and run it again there.
```

In the output, "here" is the machine you run the command on, and "there" is the saved capture of the machine where the program fails. The answer has three parts: the verdict, which states the assumption that the program works here and fails there; what Drift found; and what to try next.

Without an error message, run the command without `--error`. Drift then lists every difference that fits:

```sh
drift why --there there.json
```

```
drift why: this machine against the saved capture in there.json

Assuming it works here and fails there: 3 differences fit and the data cannot say which one causes
  it (Node version, Time zone database, npm version).

What I found:
  Node version: 22.23.3 here, 20.20.2 there
  Time zone database: 2026b here, 2025b there
  npm version: 10.9.9 here, 10.8.2 there

What to try next:
  To tell them apart, change only Node version there so it matches here, and run it again. If it
    passes, that was the cause.
  The full experiment plan is saved: drift next .drift/why/case
  Paste the error message you saw: drift why --there there.json --error "..."
```

Three differences fit in that output, so the check is to make one of them match on the failing machine and run the program again.

## Other Ways In

```sh
drift why --error "TEXT"        # only the error message; Drift asks for what it needs
drift why                       # latest failed CI run of this git checkout (needs gh or glab)
drift why --there docker:NAME   # a running container, read only (needs Docker)
drift why --there user@host     # another machine over ssh, read only (needs ssh access)
drift why -- make test          # runs here; a later failure is explained against the last pass
drift why issue.md              # an issue or error report (no machine access)
```

## When Drift Cannot Tell

When the report lacks the facts, Drift asks for one check at a time, as in the first example, and you pipe the result to `drift why --reply`. When the report reads like a fault in the program, such as a crash inside the project's own code, Drift may say it does not look like an environment difference. Treat that as a hint to check the code first. On 141 real issue reports it made 24 such calls: 8 matched a code fault or user error the maintainers named, 2 were environment problems, and 14 were threads where the maintainers named no cause.

When several differences fit, the answer lists them and says the data cannot separate them. Change one of them on the failing machine to match the working machine, and run the program again. If it passes, that difference is the cause. For the full experiment plan, run:

```sh
drift next .drift/why/case
```

The solve command runs those experiments in containers, and it needs Docker. When nothing differs, the answer says so and lists what Drift looked at. Causes outside that list need checking by hand.

## What It Looks At

- Tool versions, such as Node, Python, Git and npm.
- Environment variables, with secret values hidden.
- Open-file limits and other resource limits, plus memory and CPU limits.
- Time zone and locale, and the operating system release.
- Package versions locked in the project folder you run it from.

A tool that is missing on the failing machine is reported as "not installed there". It does not read your source code, your data files or your network traffic.

## Install

The first release is not published yet. Until it is, build from source.

| Method | Platform | Command |
| --- | --- | --- |
| Installer | macOS, Linux | `curl -fsSL https://raw.githubusercontent.com/gmitch215/Drift/master/install.sh \| sh` |
| Installer | Windows, PowerShell | `irm https://raw.githubusercontent.com/gmitch215/Drift/master/install.ps1 \| iex` |
| Homebrew | macOS | `brew install gmitch215/tap/drift` |
| Chocolatey | Windows | `choco install drift` |
| Source | Any with JDK 21 | `./gradlew :cli:jvmRun --args=--version` in a clone |

None of these needs Docker. Only the solve command does.

## Learn More

- [Why](docs/why.md): every flow of `drift why`, with real output.
- [Install](docs/install.md): downloads, package managers and building from source.
- [Try It](docs/try-it.md): commands to run against the recorded fixtures.
- [Kotlin Portability Atlas](docs/portability-atlas.md): what 38 small programs print on each target.
- [Architecture](docs/architecture.md) and [Limits](docs/limits.md).
- [ADVANCED_USAGE.md](ADVANCED_USAGE.md): every command and flag.
- [TECHNICAL_REPORT.md](TECHNICAL_REPORT.md): design, evidence and numbers.
- [Studio](https://drift.gmitch215.dev/atlas/): the Drift app, which runs in a browser.
- [License](LICENSE): MIT.

Drift is written in Kotlin Multiplatform, so most of its code is shared across targets. See [Shared Code](docs/shared-code.md).
