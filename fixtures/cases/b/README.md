Flagship case B: one Kotlin source that prints different results on Native and Wasm.

| file | content |
| --- | --- |
| `resume-offset.driftcase` | the packed certificate of `drift solve` over the two capsules in `environments/` |
| `program/` | the Kotlin source, a Gradle build for Native and Wasm, `run.mjs` and `stage.sh` |

The program calls `"line one\nline two".indexOf("", 40)`. The JVM and Kotlin/Native return the length of the text (17), and Kotlin/Wasm returns -1 (`kotlin.string.split-replace-lines`, line `indexOf-empty`, in the Atlas). `run.mjs` runs the Native build, or the Wasm build when `DRIFT_RUNTIME_PLATFORM=wasm`, inside `node:24-bookworm-slim`.

To rebuild the inputs and run the solve (needs Docker, Node and a Linux arm64 `drift` executable for the captures):

```sh
fixtures/cases/b/program/stage.sh ./gradlew /tmp/resume-ws
tar -xf fixtures/cases/b/resume-offset.driftcase environments/passing.json environments/failing.json
drift solve environments/passing.json environments/failing.json \
  --run "node run.mjs" --fail-when "FAIL cursor" --workspace /tmp/resume-ws --case case
drift pack case --out resume-offset.driftcase
drift verify fixtures/cases/b/resume-offset.driftcase
```

`drift verify` re-derives the certificate from the recorded trials and does not run the containers again.
