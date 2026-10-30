## What it is
Drift is a CLI (with a Studio app) that compares the machine where a program fails with one where it works, then names the environment difference most likely behind the failure and suggests what to try next.

## Who it is for
Developers and maintainers chasing "works here, fails there" bugs, failing CI runs, or bug reports from machines they cannot reach.

## What I would type first
`drift why issue.md`, after the install command on README line 24 (`curl -fsSL https://raw.githubusercontent.com/gmitch215/Drift/master/install.sh | sh`). The README's case list (line 31) points a bug report with no machine access at this command. I expect a `drift why: reading the report in issue.md` header, then either a named difference, such as a tool version, or "I found no environment fact" with one check to run where it fails. That output shape comes from docs/why.md lines 221-235 and 317-328, not the README, which has no issue-file example.

## Where I got lost
- Line 24 and line 27: the first command builds from source because no release exists yet, and the README says only "several minutes." It doesn't say what the installer prints or how a finished install looks, so I can't confirm the install worked.
- Line 27: "The script is [install.sh](install.sh) if you want to read it first." `install.sh` is not in the folder I was given. Neither are `LICENSE`, `fixtures/` or `cli/`. I could not read the script before piping it to `sh`.
- Line 52: "No CI run or second machine was available." In a GitHub or GitLab checkout, `--error` reads the latest failed CI run instead (docs/why.md lines 297-299). The README doesn't say which path applies, so I had to guess.
- Line 51: `php -v | drift why --reply` appears before any session exists. The README never says `--reply` needs a session from an earlier `drift why` in the same folder. docs/why.md lines 432-433 says it fails without one.
- Line 104: "your error text points at this: is not a function." Nothing follows the colon that names a difference. I took it for a broken line.
- Line 135: "Tool versions (Node, Python, Git, npm)." Line 49 uses `php -v` in the same README, and PHP is not in that list. docs/why.md line 689 describes a wider set, so the README's list is out of date or incomplete.
- Lines 42-54 and 97-112: the sample output has no note that it is recorded. docs/why.md lines 153-154 says its CI example replays recorded answers. I assumed the same for these blocks.
- Lines 98-100 and 114: "here" and "there" are the working and failing machines. The README never defines them. I inferred that from lines 85-94.
- Line 131: the accuracy figures (141 reports, 24 calls, 8/2/14 split) point to TECHNICAL_REPORT.md (line 145), which is not in the folder. I could not check them.

## Keep reading or leave
Keep reading, but only `install.sh` (line 27). It is the one thing between me and running the first command, and it is linked but missing from this folder.

## Scores
Understood what it does: 4
Knew what to run first: 3
Believed it works: 2
