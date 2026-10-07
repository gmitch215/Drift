# Homebrew Tap

Formula and cask for [Drift](https://github.com/gmitch215/Drift), rendered from the release assets
of each version.

```sh
brew install gmitch215/tap/drift
brew install --cask gmitch215/tap/drift-studio
```

Use the full `gmitch215/tap/` path. A third-party tap already ships a formula named `drift`, and a
bare `brew install drift` can pick it up instead.

## Formula

`drift` installs the command line tool for Apple silicon macOS, Linux x64 and Linux arm64. There is
no Intel macOS build.

## Cask

`drift-studio` installs the desktop app on Apple silicon macOS. The app is not signed with a
Developer ID or notarized, so the cask removes the quarantine attribute after installing. Without
that, Gatekeeper blocks the first launch.
