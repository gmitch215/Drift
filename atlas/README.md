Hand-maintained data behind the Atlas. Everything the Atlas says about why a probe differs lives
here; the results themselves are the transcripts in `fixtures/atlas`.

| file | content |
| --- | --- |
| `classification.yml` | one entry per probe: `documented`, `platform-defined` or `unclassified`, the cause classes, the references with a short quote, the issue ids with the state read on the research date, which columns differ, and for `unclassified` entries what was searched |
| `columns.yml` | how each column was measured |
| `repros/<probe>.md` | a repro draft for each unclassified probe that differs between columns; each starts with "Draft for human review. Not filed." and is never sent anywhere |

The build converts `classification.yml` to JSON and embeds it in `scan`, so the dataset, `drift atlas
show` and Studio read one source. `./gradlew atlasSite` builds the static site into
`build/atlas-site`: the Studio web build at the root, `data/dataset.json`, `data/classification.json`
and one page per probe under `probe/`.

An entry is `documented` when a page read in full states the behavior as unspecified, platform
dependent or different, for every differing line. It is `platform-defined` when a platform
specification delegates it. Anything else is `unclassified`, which means no explanation was found
in the sources searched. It does not call the behavior a defect.
