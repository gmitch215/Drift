# Atlas Libraries

Question: do the kotlinx libraries print the same transcript on jvm, linuxX64, js and wasmJs?

Method: 25 probes (`experiments/atlas-libs/src/commonMain`) run on each target; the transcripts are compared line by line. Two runs produced byte-identical transcripts on all four targets. Transcripts: `transcripts/`; table: `compare.md`.

## Versions

| component | version | published |
| --- | --- | --- |
| Kotlin | 2.4.20 | |
| kotlinx-datetime | 0.8.0 | 2026-05-07 |
| kotlinx-serialization-json | 1.12.0 | 2026-10-19 |
| kotlinx-io-core | 0.9.1 | 2026-06-26 |
| kotlinx-coroutines-core | 1.11.0 | 2026-05-07 |
| @js-joda/timezone | 3.1.0 | 2026-10-07 |

Publication dates come from the Maven Central directory listings and the npm registry. Host: paisley-park, Ubuntu container `drift-cisim:3`, JDK 21.0.12, Node 24.20.0 (Gradle-managed Node for js and wasmJs may differ), 8 CPUs, 8 GB.

## Results

| library | probes | lines compared | lines that differ | documented-contract |
| --- | ---: | ---: | ---: | ---: |
| kotlinx-datetime | 11 | 260 | 67 | 0 |
| kotlinx.serialization | 6 | 223 | 66 | 0 |
| kotlinx-io | 3 | 161 | 16 | 0 |
| kotlinx-coroutines | 5 | 50 | 1 | 0 |

The 150 differing lines classify as 81 `no-correctness-claim` and 69 `unspecified`. Causes:

- Linux time zones: the container has no tz database, so every zone line on linuxX64 is an error. These lines are not a comparison; rerun on a Linux host with tzdata.
- Double and Float formatting and parsing in the standard library on JS and Wasm.
- `decodeToString` and `encodeToByteArray` on malformed input (JVM counts and replaces differently; the pages do not define either).
- Africa/Casablanca offsets after 2025 on JS and Wasm (`@js-joda/timezone` data).
- One Kotlin/JS compiler defect: a Long in a string template inside an inlined lambda prints through a double (`9223372036854776000`). It reproduces without any kotlinx library on 2.4.20 and 2.5.0-Beta2 and is drafted in `build/kotlin-js-issues.md`.

## Verdict

No kotlinx library has a cross-target difference that a documentation sentence decides. The experiment found one Kotlin/JS compiler defect and one standard-library lead.

Base commit `a0dbff76` with uncommitted changes. Regenerate with `experiments/run.sh atlas-libs --paisley` and copy the accepted run's transcripts into `transcripts/`.
