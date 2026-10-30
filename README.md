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

```sh
curl -fsSL https://raw.githubusercontent.com/gmitch215/Drift/master/install.sh | sh
```

It installs to `~/.local/bin`; open a new terminal if `drift` is not found afterwards. The first release is not published yet, so until it is the installer builds Drift from source: it needs git, a JDK 17 or newer to start Gradle (Gradle downloads the JDK 21 it builds with), and several minutes. The script is [install.sh](install.sh) if you want to read it first. Add `| sh -s -- --from-source --ref BRANCH` to the command to build a branch, tag or commit on purpose. From a clone with JDK 21 you can also run `./gradlew :cli:jvmRun --args='why --error "TEXT"'`.

Pick the case you have:

- A bug report someone sent you, with no access to their machine: `drift why issue.md`.
- A failed CI run: `drift why` inside the project's git checkout (needs the GitHub CLI `gh` or the GitLab CLI `glab`, logged in).
- Two machines you can reach: see Compare Two Machines.
- Only an error message: start below.

Start with the error. The outputs below are copied from real runs. On the machine where the program fails, paste the line that says what failed:

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

Drift does not stop at "I don't know". It names one check, and you pipe the result back to continue the same session. Run the check where the program fails, then where it works:

```sh
php -v | drift why --reply
php -v | drift why --reply --works
```

`--reply` continues the session that `drift why` saved in `.drift/why/session.json` in the folder where you started, so run it from that folder. If you run the second command on another machine, Drift prints one line to copy back to the first. It stores what it read from your answers, not the text you piped in. Once both sides are known, Drift compares them:

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

The answer has three parts: the verdict, what Drift found, and what to try next.

## Compare Two Machines

Use this when a program passes on your laptop and fails on a server. Install Drift on both. On the failing machine, save what Drift can read, with secret values hidden:

```sh
drift why --save there.json
```

Copy the file to the machine where the program works and pass the error message the program printed:

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

In Drift's answer, "here" is the machine where you ran the command, which is the one where the program works, and "there" is the saved capture of the machine where it fails. Without `--error`, Drift lists every difference that fits and names the one change that tells them apart.

## Other Ways In

```sh
drift why --error "TEXT"        # only the error message; Drift asks for what it needs
drift why                       # latest failed CI run of this git checkout (needs gh or glab)
drift why --there docker:NAME   # a running container, read only (needs Docker)
drift why --there user@host     # another machine over ssh, read only (needs ssh access)
drift why -- make test          # runs here; a later failure is explained against the last pass
drift why issue.md              # an issue or error report (no machine access)
```

Drift Studio does the same in an app: its Why screen takes a pasted error, bug report or CI log and asks you for the same checks. `./gradlew :studio:run` opens it on the desktop, and the web build is at [drift.gmitch215.dev/atlas](https://drift.gmitch215.dev/atlas/).

## When Drift Cannot Tell

When the report lacks the facts, Drift asks for one check at a time. When several differences fit, the answer lists them and says the data cannot separate them: change one on the failing machine to match the working machine and run the program again. When the report reads like a fault in the program, Drift may say it does not look like an environment difference. Treat that as a hint to check the code first. On 141 real issue reports it made 24 such calls: 8 matched a code fault or user error the maintainers named, 2 were environment problems, and 14 were threads where the maintainers named no cause.

## What It Looks At

Tool versions (Node, Python, PHP, Git, npm and others), environment variables with secret values hidden, resource limits, time zone, locale, the operating system release, and package versions locked in the project folder. A tool that is missing on the failing machine is reported as "not installed there". Drift does not read your source code, your data files or your network traffic. None of this needs Docker.

## Learn More

- [Why](docs/why.md): every flow of `drift why`, with real output.
- [Install](docs/install.md): downloads, package managers and building from source.
- [Try It](docs/try-it.md): commands to run against the recorded fixtures.
- [Kotlin Portability Atlas](docs/portability-atlas.md): what 38 small programs print on each target.
- [Architecture](docs/architecture.md) and [Limits](docs/limits.md).
- [ADVANCED_USAGE.md](ADVANCED_USAGE.md): every command and flag.
- [TECHNICAL_REPORT.md](TECHNICAL_REPORT.md): design, evidence and numbers.
- [License](LICENSE): MIT.

Drift is written in Kotlin Multiplatform, so most of its code is shared across targets. See [Shared Code](docs/shared-code.md).
