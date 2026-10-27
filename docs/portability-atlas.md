# Kotlin Portability Atlas

Kotlin code that compiles everywhere does not always print the same thing everywhere. The Atlas holds
38 probes of Kotlin behavior. Each probe is a small program with a recorded output per target.

## Coverage

Sixteen columns are recorded on Kotlin 2.4.20: the JVM on the recording machine and on JDK 17, 21 and
25, Linux x64 and arm64 and macOS native, Wasm on Node, Chromium, Firefox and WebKit, Kotlin/JS IR on
Node, Android, the iOS simulator and two Cloudflare Workers runtimes. Five more (JVM, macOS native,
Wasm, Android and iOS) are recorded on Kotlin 2.5.0-Beta1, and a ladder of 14 compiler versions from
1.3.72 to 2.4.21 covers the JVM, macOS, Linux and Kotlin/JS targets that have a standalone compiler.

32 probes differ between targets on 2.4.20, and 18 on 2.5.0-Beta1. Every probe carries a
classification from primary sources: 10 are `documented` (a Kotlin page states the behavior), 4 are
`platform-defined` (a JDK or ECMAScript page does) and 24 are `unclassified`, with the search that
found no explanation. A divergence records a difference between targets. Some are bugs and some are
documented behavior. `drift atlas diff` lists what changed between two compiler versions.

## Studio

Studio's Atlas tab holds the matrix. It groups columns by target family and adds a column for the
device it runs on, measured live when the tab opens. A predict-and-reveal mode asks for the output
you expect on a target, then shows the recorded one. [The Studio manual](../MANUAL.md) shows each
screen.

## Command Line

`drift atlas record`, `build`, `compare` and `show` do the same work from the command line. See
[the usage guide](../ADVANCED_USAGE.md#atlas) for every flag.

`./gradlew atlasSite` builds the static Atlas site: Studio, a page per probe and the dataset as
JSON. The published copy is at <https://drift.gmitch215.dev/atlas/>.

## Hardware and Build Host

Some probes depend on hardware and on the build host. NaN bit patterns and the last digits of some
math functions differ between CPUs, and a Linux executable linked on Ubuntu prints different digits
for three math probes than one linked on macOS. Each column is one run. See [Limits](limits.md).
