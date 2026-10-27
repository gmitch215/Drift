# Kotlin/JS long template

Question: on which Kotlin compiler versions does a `Long` interpolated through a string template print through a double on Kotlin/JS IR?

The reduction is a stdlib-only Kotlin Multiplatform project. It prints one `Long` (`9007199254740993`, 2^53 + 1) four ways:

| line | expression | expected |
| --- | --- | --- |
| A | `"A " + "$v"` outside any lambda | `A 9007199254740993` |
| D | `"D " + wrap { "$v" }`, `wrap` is `inline` | `D ok:9007199254740993` |
| G | `v.also { println("G " + "$it") }`, `also` is inline | `G 9007199254740993` |
| P | `"P " + plain { "$v" }`, `plain` is not inline (control) | `P ok:9007199254740993` |

A pass is every line matching its expected value. A fail is any line that differs (the value changes by one, because the double loses the last bit). `build failed` and `no output` are reported separately with the first error line.

## Running

```sh
experiments/js-long-template/run.sh --check
experiments/js-long-template/run.sh --versions 2.4.20 --out DIR
experiments/js-long-template/run.sh --report --out DIR
```

`--check` reads the project and the templates and prints the Gradle line and Kotlin block for each version. It does not download anything.

A run writes one directory per version under `--out`: the copied project, a `build.log`, and `result.tsv`. `run.sh` takes the default version list when `--versions` is not given, and prints the table of every `result.tsv` under `--out` when it finishes. `OUT_DIR` sets the default output directory. `GRADLE` picks the launcher that writes each Gradle wrapper (default: the Drift `gradlew`); each run needs network access the first time it fetches a Gradle distribution, a Node binary and the Kotlin packages.

Each version uses a fixed Gradle line:

| Kotlin | Gradle | JS compiler |
| --- | --- | --- |
| 1.8.x, 1.9.x | 8.5 | `js(IR)`, the form the 1.x plugin needs |
| 2.0.x to 2.5.x | 8.14.3 | `js { }` (IR is the only compiler) |

Gradle 8.5 is the earliest line that runs on JDK 21. The runs use `--no-daemon` and `--max-workers=2`, and run `kotlinUpgradeYarnLock jsNodeProductionRun`.

## Running on paisley-park

`paisley.sh <label> <versions>` runs the ladder in lane `b1` on paisley-park. It syncs this folder into the lane tree, copies `paisley-loop.sh` to the lane, and starts it with `nohup setsid`. The loop runs one version at a time under the box network lock (`/tmp/paisley-net.lock`), checks that `/` has at least 25 GB free before each version, and writes `logs/<label>.done` at the end. Output lands in `~/drift-rig/lanes/b1/runs/<label>/`. Read the table with:

```sh
ssh paisley-park 'docker exec -w /work/tree drift-lane-b1 bash experiments/js-long-template/run.sh --report --out /work/runs/<label>'
```

## Files

| file | content |
| --- | --- |
| `project/` | the reduction: `settings.gradle.kts` (2.4.20 default, rewritten per version), `build.gradle.kts`, `gradle.properties`, `src/jsMain/kotlin/Main.kt` |
| `run.sh` | the version loop, the report and `--check` |
| `paisley.sh`, `paisley-loop.sh` | the paisley-park driver (host side) |

Raw output of every run goes under `experiments/results/js-long-template/runs/`, which is ignored. The last accepted run is in `experiments/results/js-long-template/latest.md`.
