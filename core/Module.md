# Module core

Capsule model, canonical JSON, SHA-256, fixed-point math, and the diagnosis engine built on them. All code is common Kotlin, so every target produces the same bytes.

Packages cover the capsule model (`model`), hashing and JSON (`hash`, `json`, `math`), comparison (`diff`, `delta`, `spectrum`, `rank`), CI history (`history`, `symptom`), planning (`plan`, `know`), redaction (`redact`), case files (`case`) and the Lab (`lab`): interventions, the `solve` loop, `ingest`, the `.driftcase` archive and `verify`.
