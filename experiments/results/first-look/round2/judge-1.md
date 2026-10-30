## What it is
Drift compares the machine where a program fails with a machine where it works, or with an error message or CI log, and names the likely cause and what to try next.

## Who it is for
Developers and CI maintainers who have a program that passes on one machine and fails on another.

## What I would type first
```sh
curl -fsSL https://raw.githubusercontent.com/gmitch215/Drift/master/install.sh | sh
```
I expect the script to find that no release exists and build Drift from source. That takes several minutes and needs git and a JDK 17 or newer. When it finishes, `drift` should be on the path in a new terminal. Per docs/install.md:113, the default location is `~/.local/bin`. I did not confirm the script's final output, because `install.sh` is outside my reading set. The clone route from README line 27 is `./gradlew :cli:jvmRun --args='--version'`, which docs/install.md:163 says prints `drift version 1.0.0 (jvm)`.

## Where I got lost
- README line 27: "The script is [install.sh](install.sh) if you want to read it first." I could not open it, so I could not check what `curl | sh` runs.
- README line 27: "Open a new terminal if `drift` is not found afterwards." The README never says where `drift` is installed. I found the answer only in docs/install.md:113.
- README line 27: "several minutes." The README does not say what a successful source build prints. I had no end state to check against.
- README line 27: "a JDK 17 or newer to start Gradle (Gradle downloads the JDK 21 it builds with)." The clone command on the same line does not state a JDK version. docs/install.md:128 says JDK 21 for that command. I had to reconcile the two.
- README line 114: "In the answer, here is the machine where you run the command (where the program works) and there is the saved capture of the machine where it fails." I read this twice. "Here" and "there" map to the working and failing machines only when I cross-checked lines 98 to 105.
- README line 148: "See [Shared Code](docs/shared-code.md)." I did not open that page. The Kotlin Multiplatform claim rests on the target table in docs/install.md:126 to 136.

## Keep reading or leave
Keep reading. The README gives one runnable path with its cost stated, and docs/install.md gives matching commands, so I could launch the project from these pages.

## Scores
Understood what it does: 5
Knew what to run first: 4
Believed it works: 3
