## What it is
A command-line tool that compares the machine where a program works with the machine where it fails, then names the difference most likely to cause the failure.

## Who it is for
Maintainers and users chasing a "works here, fails there" bug who can reach both machines, or who hold a CI log, an error message, or an issue report.

## What I would type first
```sh
curl -fsSL https://raw.githubusercontent.com/gmitch215/Drift/master/install.sh | sh
```
I expect a source build that takes several minutes, since no release exists yet (README line 27). The script then runs `drift --version` and hands over to `drift install` (docs/install.md lines 73-74). If `drift` is not found, I open a new terminal (README line 27). After that I would run `drift why issue.md` on the bug report. The docs show that output only for a different example report (docs/why.md line 221), so I have no sample for my report's exact output.

## Where I got lost
1. README line 27: "it needs git, JDK 17 or newer, and several minutes." docs/install.md line 123 says the build "downloads the JDK 21 toolchain if one is missing." I could not tell whether the installer path needs 17 or 21.
2. README line 24 pipes a remote script into `sh`. `install.sh` is not in this folder, so I could not read what it runs. docs/install.md line 97 says the default ref is `master`, so I would get whatever `master` holds today.
3. README line 29: "A bug report someone sent you (you cannot reach their machine): run `drift why issue.md`." The next paragraph says "Start with the error. On the machine where the program fails, paste the line..." That walkthrough assumes I can reach the failing machine, so it does not match my case.
4. README line 119: "`drift why issue.md  # an issue or error report (no machine access)`." The README does not say what the file must contain. docs/why.md line 238 says "A report that names a tool version and no rule explains it gets an ask instead," so the answer for my report depends on its content, and the README does not warn about that.
5. README line 140 links `TECHNICAL_REPORT.md`, line 141 links `LICENSE`, and line 2 loads `assets/drift.png`. None of them is in this folder. docs/try-it.md line 3 runs its examples "against the files in `fixtures/`", which is also absent, so I could not check the recorded outputs.

## Keep reading or leave
Leave. The README, docs/why.md and docs/install.md cover the issue flow for my case. The open questions (the installer's JDK requirement, what `install.sh` does, and the fixtures) live outside this folder, so more reading here will not close them.

## Scores
Understood what it does: 4
Knew what to run first: 2
Believed it works: 3
