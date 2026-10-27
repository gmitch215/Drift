# Documentation

## Guides

- [Install](install.md): downloads, package managers, the install script and building from source.
- [Why](why.md): `drift why`, the one command that compares a working place with a failing one and explains the difference.
- [Try It](try-it.md): commands to run against the recorded fixtures, with their output.
- [Kotlin Portability Atlas](portability-atlas.md): the probes, the columns and how to read them.
- [Architecture](architecture.md): the modules, their dependencies and the investigation chain.
- [Shared Code](shared-code.md): how much of the code every target shares.
- [Limits](limits.md): what Drift cannot do, by platform.

## Reference

- [MANUAL.md](../MANUAL.md) walks through each Studio screen, with screenshots.
- [ADVANCED_USAGE.md](../ADVANCED_USAGE.md) covers every command and flag, with output from real runs.
- [TECHNICAL_REPORT.md](../TECHNICAL_REPORT.md) describes the design, the evidence and the numbers.
- Engine documentation for the modules is generated with Dokka by `./gradlew :dokkaGenerate` into
  `build/dokka/html`, and `docsSite` copies it to `engine/`. Drift ships as an application, and the
  modules are not a public API or a published library.
