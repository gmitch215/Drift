# Try It

The examples run from the repository root against the files in `fixtures/`. They assume `drift` is
on your path, or the `drift` alias from [Install](install.md#command-line). Each one needs only the
executable, except where it says otherwise. Output is from Linux x64.

**Capture this machine.** The capsule is JSON, so the digest changes with the machine.

```sh
drift capture --label ci > ci.json
sha256sum ci.json
```

On macOS, use `shasum -a 256` for the second command.

**Diagnose a real failure.** `drift diagnose` compares two capsules. `--detail` prints text
instead of JSON, at three levels (`summary`, `detail`, `full`). The first lines of `detail`:

```sh
G=fixtures/drangler/capsules/36702683742.json
R=fixtures/drangler/capsules/36995781138.json
drift diagnose --detail detail $G $R | head -9
```

```
9 changes differ with the same support, and tool.node.version ranks first only by hand-set weights,
so the data does not name one cause.

Candidates, best first. Scores come from hand-set weights (hand-set-1, uncalibrated); they order
  candidates and are not probabilities.
  1. tool.node.version (tier difference, basis inferred, in bundle-1)
      evidence: attribute tool.node.version; no run history behind it
  2. ci.provisioner.build-date (tier difference, basis inferred, in bundle-1)
      evidence: attribute ci.provisioner.build-date; no run history behind it
```

The full output also lists the bundle and the proposed next experiment.

**Verify a packed case.** `fixtures/lab/cases` holds four solved cases. `verify` runs 14 checks on the
archive itself.

```sh
drift verify fixtures/lab/cases/locale-tz-date-2.driftcase | tail -3
```

```
verdict: CONFIRMED: this dimension causes the failure: env.TZ. Setting it to the failing values moved the failure count from 0 of 5 to 5 of 5 (exact one-sided p 0.003968) and back from 5 of 5 to 0 of 5 when the failing side was set to the passing values (p 0.003968)
experiments are not re-run; the recorded results are taken as given
verify: ok
```

**Reproduce an environment.** This writes a `Dockerfile`, `run.sh` and `manifest.json` into `repro/`
and does not run Docker. The manifest lists what a container cannot mirror.

```sh
drift reproduce fixtures/lab/node-alpine.json --run "npm test" --out repro
```

```
wrote 3 files to repro
23 attributes: 10 mirrored, 4 partially mirrored, 9 not mirrored; base image node:22.12.0-alpine3.20
This is not the original environment. Attributes that are not mirrored are listed with their reasons
  at --detail detail.
```

**Record the Atlas for this machine.** The transcript holds one section per probe. Where an
executable was linked changes three lines of `kotlin.math.transcendental-bits` (`tan1`, `asin-half`
and `acos-half`, in the last digit), and the Linux executable used here differs from
`fixtures/atlas/linux.txt` in exactly those lines.

```sh
drift atlas record --out tr
```

```
wrote 38 probes for linux on kotlin 2.4.20 to tr/linux.txt
```

**Install into a scratch directory.** `--dry-run` prints every change and makes none. Without it, the
same command installs, and `drift uninstall` removes what the receipt lists.

```sh
drift install --dir "$PWD/scratch-bin" --no-modify-path --dry-run
```

```
dry run: nothing is changed
scope: user
create directory /path/to/scratch-bin
write /path/to/scratch-bin/drift (mode 0755, 12102072 bytes)
create directory ~/.config
create directory ~/.config/drift
PATH is not modified
write receipt ~/.config/drift/install-receipt.json
```

**Serve Studio.** This needs a Studio web build. Stage it once, then serve it on a free port and fetch
the page headers:

```sh
./gradlew :cli:stageStudio
drift serve --dir cli/build/serve/studio --port 0
```

```
drift serve 1.0.0: serving /path/to/studio
http://localhost:56990/
http://127.0.0.1:56990/
http://[::1]:56990/
press Ctrl+C to stop
```

```sh
curl -sI http://localhost:56990/
```

```
HTTP/1.1 200 OK
Content-Type: text/html; charset=utf-8
Cache-Control: no-cache
Content-Length: 508
Connection: close
X-Content-Type-Options: nosniff
Cross-Origin-Resource-Policy: same-origin
```

**Flagship cases.** `fixtures/cases` holds the longer cases that the technical report describes.
Case B is one Kotlin program that prints different results on Native and Wasm; case C holds six
incidents, among them Lombok on JDK 21. Verifying them reads the packed certificate and starts no
container:

```sh
drift verify fixtures/cases/b/resume-offset.driftcase | tail -3
```

```
verdict: CONFIRMED: this dimension causes the failure: runtime.platform. Setting it to the failing values moved the failure count from 0 of 5 to 5 of 5 (exact one-sided p 0.003968) and back from 5 of 5 to 0 of 5 when the failing side was set to the passing values (p 0.003968)
experiments are not re-run; the recorded results are taken as given
verify: ok
```

```sh
drift verify fixtures/cases/c/INC-11.driftcase | tail -3
```

```
verdict: CONFIRMED EFFECT (bundle): the arms differ in 2 attributes, so the effect is the bundle's and not one member's: env.JAVA_VERSION, tool.java.version. Setting tool.java.version moved the failure count from 0 of 5 to 5 of 5 (exact one-sided p 0.003968) and back from 5 of 5 to 0 of 5 when the failing side was set to the passing values (p 0.003968). No bundle-splitting arm was possible: only tool.java.version can be set on its own; the other members travel with the base image.
experiments are not re-run; the recorded results are taken as given
verify: ok
```
