# Packaging

Templates for the Homebrew tap and the Chocolatey packages. `.github/workflows/release.yml` renders
them after each release, and the `homebrew` and `chocolatey` jobs publish the result.

| Path | Output |
| --- | --- |
| `homebrew/Formula/drift.rb` | CLI formula for the tap `gmitch215/homebrew-tap` |
| `homebrew/Casks/drift-studio.rb` | desktop app cask for the same tap |
| `chocolatey/drift/` | CLI package from the Windows zip |
| `chocolatey/drift-studio/` | desktop app package, silent MSI install |

## Rendering

```sh
./gradlew renderPackaging -Pdrift.sums=path/to/SHA256SUMS
```

The task copies this directory to `build/packaging` and fills three tokens:

- `@version@`: the Gradle project version
- `@sha256:<suffix>@`: the digest of `drift-<version>-<suffix>` in `SHA256SUMS`, for example
  `@sha256:macos-arm64.dmg@`
- `@license@`: the text of the root `LICENSE`

A token that names an asset missing from `SHA256SUMS` fails the build.

## Publishing

The jobs run only when the `suffix` input is empty. With `dry-run` they render, check and pack, and
upload the results as workflow artifacts without pushing.

| Secret | Used by |
| --- | --- |
| `HOMEBREW_TAP_TOKEN` | `homebrew`: contents write access to `gmitch215/homebrew-tap` |
| `CHOCOLATEY_API_KEY` | `chocolatey`: the push key of the community repository |

## Checks

`chocolatey/verify.ps1` runs the rendered install and uninstall scripts against stubbed Chocolatey
helpers and compares the URL, checksum, silent arguments and exit codes with `SHA256SUMS`.
`.github/release/test-wait-assets.sh` covers the download and checksum wait that precedes rendering.
