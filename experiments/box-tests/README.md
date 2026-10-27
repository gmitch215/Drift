# Box Tests for Confirmed Kotlin Findings

Question: do the confirmed Kotlin findings reproduce on the four targets in the lane (jvm, linuxX64, js on Node, wasmJs on Node), in the shape the compiler's `box` tests use?

Each file in `tests/` holds one finding as `fun box(): String` that returns `"OK"` when the behavior is correct and a failure string otherwise. The failure string names the assertion and the observed values.

| file | finding | kind | source of the claim |
| --- | --- | --- | --- |
| `tests/k1-long-template.kt` | K1, Long through an inlined lambda (not filed) | relation: template equals `toString()` | `build/kotlin-js-issues.md` K1 |
| `tests/y1-regex-ignore-case.kt` | Y1, IGNORE_CASE sigma forms (KT-90534) | relation: final and capital sigma match as sigma does | `build/youtrack-issues.md` Y1 |
| `tests/y2-nan-negation.kt` | Y2, NaN negation bits (KT-88082) | relation: constant and runtime negation agree | `build/youtrack-issues.md` Y2 |
| `tests/pow-one-nan.kt` | 1^nan, `1.0.pow(NaN)` | documented value: `NaN` | Atlas `kotlin.math.pow-special-cases` |

No test invents an expected value. The three relation tests compare two forms of the same program. The pow test uses the documented sentence "`b.pow(NaN)` is `NaN`" (`libraries/stdlib/common/src/kotlin/MathH.kt:460` on kotlin master, the description of `Double.pow`).

Quote status: the sentences in the file headers match the kotlin master source verbatim. The live kotlinlang.org pages were read through a summarizing fetch, not as verbatim text, so the live wording is UNVERIFIED. Re-read the live pages before a comment cites them. The K1 quote comes from the draft in `build/kotlin-js-issues.md`, not from a fresh read.

## Prerequisites

- Gradle through the repository wrapper (`./gradlew`), JDK 21 for the jvm toolchain.
- Kotlin/Native for linuxX64 (the run needs a Linux x86-64 host; other hosts build the binary and skip running it).
- Node for js and wasmJs. The first run downloads the Kotlin/JS and Wasm toolchains, so it needs network access. Later runs can add `--offline`.

## Running

```sh
experiments/box-tests/run.sh --check
experiments/box-tests/run.sh
experiments/box-tests/run.sh --targets jvm,js --offline
experiments/run.sh box-tests --paisley
```

`--check` validates the test files and prints what would run; it needs no Gradle and no network. A run writes to `experiments/results/box-tests/runs/<label>/`: one `<target>.txt` per target, a `work/` project with the generated sources, and `table.md`. The run script generates one package per test (`box.<test_name>`) and a `main` that calls each `box()` and prints `box <name>: <result>`. The `tests/` files are never edited by the run.

The table has one row per test and one column per target. A cell is `OK`, a failure string, `not run` (no output file for that target), or `no output`. The run exits 0 when every requested target produced output, whatever the cells say, because a failing cell is the finding reproducing. It exits 2 when a target failed to build or run, and 3 when `--out` already exists.

## Maintainer Use

A confirmed finding that a maintainer should see goes into the compiler's box tests at `compiler/testData/codegen/box/<area>/<name>.kt`, with the directives below in the header.

- `k1-long-template.kt`: area `strings` or `ir/...` (pick the closest existing folder). Use `// WITH_STDLIB`, which is declared at `compiler/test-infrastructure/testFixtures/org/jetbrains/kotlin/test/directives/ConfigurationDirectives.kt:12`. Until the fix lands, restrict the test to the backend that fails with `// TARGET_BACKEND: JS_IR` (backend names from `compiler/test-infrastructure-utils.common/testFixtures/org/jetbrains/kotlin/test/TargetBackend.kt`).
- `y1-regex-ignore-case.kt`: area `regex` or `stdlib`. `// WITH_STDLIB`. Native and wasm fail, so restrict with `// TARGET_BACKEND: NATIVE` and `// TARGET_BACKEND: WASM` once the fix is merged, or exclude them with `// DONT_TARGET_EXACT_BACKEND: NATIVE` and `// DONT_TARGET_EXACT_BACKEND: WASM` until then.
- `y2-nan-negation.kt`: area `ieee754`. `// WITH_STDLIB`. Fails on Native before 2.5.0-Beta2 only. Use `// DONT_TARGET_EXACT_BACKEND: NATIVE` on releases that predate the fix.
- `pow-one-nan.kt`: area `math`. `// WITH_STDLIB`. Its expected value is `NaN`, so the fix belongs in the stdlib path rather than the test.

The `WITH_STDLIB` requirement for each file is UNVERIFIED against the test runner. Check it before filing.

Y2 was already answered upstream. On 2026-10-22 the assignee called the change deliberate and took the test idea into KT-87211, so the maintainer may already hold an equivalent test. Check KT-87211 before posting `y2-nan-negation.kt`.

## Results

`experiments/results/box-tests/latest.md` is the accepted run. Raw output goes to `experiments/results/box-tests/runs/`, which is gitignored.
