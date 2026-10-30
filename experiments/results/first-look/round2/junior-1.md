## What it is
Drift is a command-line tool that compares the machine where a program fails with a machine where it works, a CI run, or an error message, and names the difference most likely to cause the failure.

## Who it is for
Developers whose program passes on one machine and fails on another, such as a laptop and a server or CI runner.

## What I would type first
```sh
curl -fsSL https://raw.githubusercontent.com/gmitch215/Drift/master/install.sh | sh
```
I expect a source build that takes several minutes, then `drift` on my PATH after I open a new terminal (README line 27). The README gives no exact install output, so I would treat anything short of a finished build as a failure. Then, inside the project checkout, `drift why`. I expect a verdict sentence, a "What I found" block and a "What to try next" block, matching the layout in docs/why.md line 156.

## Where I got lost
- README line 27: "The script is [install.sh](install.sh) if you want to read it first." install.sh is not in this folder, so I would be piping a script I have not read into `sh`. The line also says "a JDK 17 or newer" and gives no command to check my version.
- README line 32: "A failed CI run: `drift why` inside the project's git checkout (needs the GitHub CLI `gh` or the GitLab CLI `glab`, logged in)." No command for logging in. I would guess `gh auth login`. The rule that the remote must be on GitHub or GitLab appears only in docs/why.md line 146.
- README line 85: "Use this when a program passes on your laptop and fails on a server. Install Drift on both." My failure is on a CI runner where I cannot install anything. The README does not say whether this section applies to CI, and line 32 is the only CI line.
- README line 114: "In the answer, here is the machine where you run the command (where the program works) and there is the saved capture of the machine where it fails." I had to read it twice to work out which side "here" is.
- docs/why.md line 169: "change only CI runner provisioner.build-date in CI so it matches when CI passed, and run it again." I cannot change a CI runner's build date, and the page does not say how a CI user would do this. I stopped here.

## Keep reading or leave
Keep reading. The CI section in docs/why.md covers the case I came in with, and its sample output shows what a result looks like.

## Scores
understood what it does: 4
knew what to run first: 3
believed it works: 3
