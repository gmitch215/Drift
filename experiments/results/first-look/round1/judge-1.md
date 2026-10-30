## What it is
Drift is a Kotlin Multiplatform CLI and desktop/web app that compares the machine where a program fails with the one where it works, and names the difference most likely to cause the failure.

## Who it is for
Developers and CI maintainers whose program passes on one machine and fails on another.

## What I would type first
```sh
curl -fsSL https://raw.githubusercontent.com/gmitch215/Drift/master/install.sh | sh
```
I expect the script to report that no release exists and that it is building from source, which takes several minutes (README line 27). It should finish by running the new binary's `--version` and print a line shaped like `drift version 1.0.0 (macos)` (docs/install.md line 160). I have not seen the script's output, so this is an expectation, not an observation.

## Where I got lost
- README line 24: `install.sh | sh`. `install.sh` is not in this folder, so I could not read what the pipe runs.
- README line 27: "it needs git, JDK 17 or newer, and several minutes." docs/install.md line 123 says "the build downloads the JDK 21 toolchain if one is missing." I could not tell which JDK I need installed.
- README line 27: "From a clone you can also run `./gradlew :cli:jvmRun ...`." There is no `gradlew`, `cli/`, or any source file in this folder, so I assumed the command works.
- README line 109: "Here is the machine you run the command on, and there is the saved capture." It does not say which machine is which. I re-read the example to work it out.
- README line 140: "[TECHNICAL_REPORT.md](TECHNICAL_REPORT.md)" links to a file that is not in this folder.
- docs/try-it.md line 3: the examples "run from the repository root against the files in `fixtures/`". There is no `fixtures/` folder here, so none of those commands can run from this folder.

## Keep reading or leave
Keep reading. docs/shared-code.md is the only file here that could show how the Kotlin Multiplatform code is split, and I have not opened it. Until I do, the KMP score rests on the README's claim at line 143.

## Scores
Understood what it does: 5
Knew what to run first: 3
Believed it works: 3
