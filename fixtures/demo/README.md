# Demo Recordings

`node-groupby` holds the recordings that `drift demo` replays. Both machines were recorded on
2026-10-31 on a Linux x64 host with `drift-linux-x64-final8`, one container per machine:

- `fails.json` is `drift why --save` run inside `node:20-slim` (image created 2026-04-22).
- `works.json` is `drift why --save` run inside `node:22-slim` (image created 2026-09-23).

The files are unedited. Both captures carry the label `here` because that is what `--save` writes.
The two captures differ in the Node version, the npm version and the time zone database version.
