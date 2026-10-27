# Kotlin/JS long template: first bad compiler version

Question: on which Kotlin/JS IR compiler versions does a `Long` interpolated in a string template, where the template sits in an inlined lambda concatenated with a literal, print through a double?

Verdict: the defect is present on every version from 1.8.0 to 2.5.0-Beta2. The range has no good version, so there is no last good version and no patch release to bisect. The earliest version in range (1.8.0) is already bad, so the first bad version is at or below 1.8.0. Versions before 1.8.0 are outside the tested range.

## Method

The reduction is `experiments/js-long-template/project/`, a stdlib-only Kotlin Multiplatform project with a JS target on Node. It prints one `Long` (`9007199254740993`, 2^53 + 1) on four lines. Line A is outside any lambda. Lines D and G put the template in an inlined lambda concatenated with a literal. Line P is the control: the same template in a non-inline function. A version passes when A, D, G and P all print the exact value.

Each version runs `kotlinUpgradeYarnLock jsNodeProductionRun` in its own copy of the project. The Kotlin plugin version is written into `settings.gradle.kts`. For 1.x the JS block is `js(IR)`, the form that the 1.x plugin needs. For 2.x it is `js { }`, the only IR form. Gradle is 8.5 for 1.x (the earliest line that runs on JDK 21) and 8.14.3 for 2.x. Each row is taken from the build log of that version's run.

## Results

Run 1, `ladder1`, and run 2, `ladder2`, used the same version list. Their tables are identical.

| version | Gradle | A | D | G | P (control) | verdict |
| --- | --- | --- | --- | --- | --- | --- |
| 1.8.0 | 8.5 | `A 9007199254740993` | `D ok:9007199254740992` | `G 9007199254740992` | `P ok:9007199254740993` | wrong |
| 1.8.22 | 8.5 | `A 9007199254740993` | `D ok:9007199254740992` | `G 9007199254740992` | `P ok:9007199254740993` | wrong |
| 1.9.0 | 8.5 | `A 9007199254740993` | `D ok:9007199254740992` | `G 9007199254740992` | `P ok:9007199254740993` | wrong |
| 1.9.25 | 8.5 | `A 9007199254740993` | `D ok:9007199254740992` | `G 9007199254740992` | `P ok:9007199254740993` | wrong |
| 2.0.0 | 8.14.3 | `A 9007199254740993` | `D ok:9007199254740992` | `G 9007199254740992` | `P ok:9007199254740993` | wrong |
| 2.0.21 | 8.14.3 | `A 9007199254740993` | `D ok:9007199254740992` | `G 9007199254740992` | `P ok:9007199254740993` | wrong |
| 2.1.0 | 8.14.3 | `A 9007199254740993` | `D ok:9007199254740992` | `G 9007199254740992` | `P ok:9007199254740993` | wrong |
| 2.1.21 | 8.14.3 | `A 9007199254740993` | `D ok:9007199254740992` | `G 9007199254740992` | `P ok:9007199254740993` | wrong |
| 2.2.0 | 8.14.3 | `A 9007199254740993` | `D ok:9007199254740992` | `G 9007199254740992` | `P ok:9007199254740993` | wrong |
| 2.2.21 | 8.14.3 | `A 9007199254740993` | `D ok:9007199254740992` | `G 9007199254740992` | `P ok:9007199254740993` | wrong |
| 2.3.0 | 8.14.3 | `A 9007199254740993` | `D ok:9007199254740992` | `G 9007199254740992` | `P ok:9007199254740993` | wrong |
| 2.3.21 | 8.14.3 | `A 9007199254740993` | `D ok:9007199254740992` | `G 9007199254740992` | `P ok:9007199254740993` | wrong |
| 2.4.0 | 8.14.3 | `A 9007199254740993` | `D ok:9007199254740992` | `G 9007199254740992` | `P ok:9007199254740993` | wrong |
| 2.4.21 | 8.14.3 | `A 9007199254740993` | `D ok:9007199254740992` | `G 9007199254740992` | `P ok:9007199254740993` | wrong |
| 2.5.0-Beta1 | 8.14.3 | `A 9007199254740993` | `D ok:9007199254740992` | `G 9007199254740992` | `P ok:9007199254740993` | wrong |
| 2.5.0-Beta2 | 8.14.3 | `A 9007199254740993` | `D ok:9007199254740992` | `G 9007199254740992` | `P ok:9007199254740993` | wrong |

Every row has exit 0 and no build failures. The 2.4.20 smoke run (`smoke1`) gave the same D and G values, so the 2.4.20 result matches the K1 draft.

## Plain-control verdict

Line P is correct on all 16 versions. A non-inline template with the same concatenation prints the exact value. The defect depends on the inlined lambda, and the control rules out a general Long formatting bug.

## Determinism

`ladder1` and `ladder2` produced identical tables and identical `result.tsv` rows for 1.8.0, 2.4.21 and 2.5.0-Beta2 (checked with `diff`). The rest of the table was compared as whole lines and matched.

## Regenerate

```sh
experiments/js-long-template/paisley.sh <label> 1.8.0,1.8.22,1.9.0,1.9.25,2.0.0,2.0.21,2.1.0,2.1.21,2.2.0,2.2.21,2.3.0,2.3.21,2.4.0,2.4.21,2.5.0-Beta1,2.5.0-Beta2
ssh paisley-park 'docker exec -w /work/tree drift-lane-b1 bash experiments/js-long-template/run.sh --report --out /work/runs/<label>'
```

Local (any host with the Drift Gradle wrapper and network access): `experiments/js-long-template/run.sh --versions <list> --out DIR`.

## Versions and tools

- Base commit: `a0dbff76` (`master`, working tree dirty with other lanes' work; this experiment owns only the paths listed in its README).
- Kotlin versions: from `org.jetbrains.kotlin:kotlin-gradle-plugin` maven-metadata, lastUpdated 20261021085506. Stable first and last release of each minor from 1.8 to 2.4. 2.5 has only pre-releases (Beta1 and Beta2), so both are listed.
- Gradle 8.5 and 8.14.3. JDK 21.0.12.1 in container `drift-cisim:3` on paisley-park, lane `b1`.
- The Node version is the one each Kotlin plugin downloads. It was not recorded per row.

## Limits

- Gradle compatibility for 2.0 to 2.3 is not checked against the Kotlin compatibility guide. Every row built and ran, so no row shows a Gradle-caused failure, but this does not confirm the Gradle range.
- The range starts at 1.8.0. A search below it would be needed to name a first bad version lower than 1.8.0.
