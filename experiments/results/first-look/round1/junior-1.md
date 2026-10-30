## What it is
Drift is a command-line tool that compares the machine where a program fails with a machine where it works (or a CI log, or an error message) and names the environment difference behind the failure.

## Who it is for
Developers whose program passes on one machine and fails on another, such as a laptop and a server or CI runner.

## What I would type first
`drift why`, run inside the project's git checkout after installing Drift. I expect a header like `drift why: this machine against the latest failed CI run of OWNER/REPO, branch BRANCH`, then either a list of differences with "What to try next", or "Nothing on this machine differs ... so I compared the two CI runs." This needs `gh` or `glab` logged in. The example output comes from docs/why.md, not the README.

## Where I got lost
- README.md:29, "Pick the case you have." The three cases are a bug report, two machines I can reach, and an error message. A failed CI job is not one of them, so I guessed it matched "Other Ways In" at README.md:115.
- README.md:24, `curl -fsSL https://raw.githubusercontent.com/gmitch215/Drift/master/install.sh | sh`. I would not pipe a remote script into `sh` without reading it first. install.sh is not in the folder I was given, so I could not check it.
- README.md:27, "the installer builds Drift from source: it needs git, JDK 17 or newer, and several minutes." docs/install.md:123 says the build "downloads the JDK 21 toolchain if one is missing." I could not tell which JDK I need.
- README.md:115, "latest failed CI run of this git checkout (needs gh or glab)." I don't know what `gh` or `glab` are, and the README does not say how to install or log in to either. docs/why.md:151 says the error names the fix, but I would only learn that by running it.
- README.md:95, "Assuming it works here and fails there." "Here" means the machine I type on, and that changes depending on where I run the command. The README never explains how to swap sides. docs/why.md:31 mentions `--fails-here`, but the README does not.
- README.md:31, "Start with the error. On the machine where the program fails, paste the line that says what failed." My CI job runs on a runner I cannot log into, so I could not follow this step as written.
- README.md:130, "None of this needs Docker; only the solve command does." The README never introduces a "solve command."

## Keep reading or leave
Keep reading. The CI flow is covered only in docs/why.md ("Read the Latest Failed CI Run"), and the README does not point me to that section for my case.

## Scores
Understood what it does: 4
Knew what to run first: 2
Believed it works: 3
