# Shared Code

Kotlin common code holds most of the program. `core` and `scan` have no platform source sets, and
`host` holds every `expect` and `actual` pair. The share of non-comment lines under `src/common/main`
in each module, as `./gradlew sharedRatio` measures it:

<p data-shared-ratio>Shared code 93.8%: host 58.1%, core 100.0%, scan 100.0%, cli 83.7%, studio 95.0%, bench 82.5%</p>

`./gradlew sharedRatio` prints the common and platform line counts per module and fails the build
when the total falls below its floor, when `host` exceeds its platform-line budget or when `core` has
any platform lines. It also fails when the line above is out of date; `./gradlew sharedRatioReadme`
rewrites it.

The platform lines of `host` are the process runners (POSIX, Windows and JVM) and the file and
environment access. The platform lines of `cli` are the file and socket code behind `install` and
`serve`. The ratio counts the default targets: JVM, Linux, macOS, Windows and Wasm. `core` and the
common code of `bench` use no `Double` or `Float`, so their results are the same on every target.
See [Architecture](architecture.md) for the module layout.
