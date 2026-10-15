Flagship case D: a PHP 7.4 library moved to PHP 8.3, with the prediction frozen before the move.

| file | content |
| --- | --- |
| `selection.json`, `selection.json.sha256` | the pool of 12, the selection rule and the run order, sealed before any capture or test run |
| `outcomes.json` | one row per candidate in order: not reconstructible, reconstructible, or not run |
| `capture-php-7-4.json` | `drift capture --project --tools` of `lcobucci/jwt` 4.1.4 inside PHP 7.4.33, after `composer install` |
| `prediction.json`, `prediction.txt` | `drift upgrade-check capture-php-7-4.json --to php=8.3` |
| `score-protocol.json` | how a prediction is scored and which message pattern attributes a failure to which rule |
| `prediction.sha256`, `rules-tree.sha256` | hashes of everything above, the rule tree and both executables, taken before the PHP 8.3 run |
| `score.json` | the score, the migration findings and one supplementary run |
| `post-fix.json` | not part of the frozen protocol: the same capture rerun after `upgrade-check` split version-only matches into their own group; see below |
| `capture-php-7-4-p3-lock.json`, `post-fix-p3-lock.json` | not part of the frozen protocol: the frozen capture with its `project.composer.*` keys rescanned from the lcobucci/jwt 4.1.4 tree (they now carry `requires.php` per locked package), and `upgrade-check --to php=8.3` on it with 298 rules; see below |
| `follow-up-rules.md` | rule ideas from the result; none was applied |
| `runs/` | the composer and PHPUnit logs |
| `scripts/` | the sealer, the image, the old and new stack runners, the extra deprecation scan and the scorer |

Order of events: the selection was sealed at 08:33 UTC on 2026-10-13, the prediction at 08:52 UTC, and both hashes are in `.claude/batch/waves.md`. The first candidate in the order, `sebastianbergmann/comparator` 3.0.3, does not install on PHP 7.4 (phpunit 8.5 needs `sebastian/comparator` 3.0.7 or newer and the root package is 3.0.3). The second, `lcobucci/jwt` 4.1.4, installs from its committed lock and passes 280 tests on PHP 7.4.33, so it is the case.

Result on the frozen protocol: 11 will-break and 1 may-break finding, 0 true positives, 12 false positives, 0 misses. The suite passed on PHP 8.3.35 (280 tests, 522 assertions) with no failure. Reading that as "the predictions were wrong" would overstate it, and reading it as "the migration is safe" would too:

- All 12 rules fire on the version crossing alone. None reads a source fact, and the capture had none of the APIs they name.
- The scored run is not the old dependency set. `composer install` from the lock stops on PHP 8.3 (`phpspec/prophecy` 1.12.2 caps PHP below 8.1), and the next step in the protocol, `composer update`, moved 60 packages including PHPUnit 9.5.0 to 9.6.38.
- With the lock held (`--ignore-platform-reqs`, run after the freeze and not scored), PHP stops after 63 of 280 tests with a fatal error from `mikey179/vfsstream` 1.6.8: `getIterator()` is incompatible with `IteratorAggregate::getIterator()` (PHP 8.1 tentative return types). The catalog has no rule for it and it was not predicted.

Reproduce the stacks (needs Docker with network access to Packagist and GitHub):

```sh
docker build -q -t drift-case-d-php74 --build-arg PHP=7.4-cli-bullseye fixtures/cases/d/scripts
docker build -q -t drift-case-d-php83 --build-arg PHP=8.3-cli-bookworm fixtures/cases/d/scripts
git clone https://github.com/lcobucci/jwt.git /tmp/jwt-old && git -C /tmp/jwt-old checkout 4.1.4
fixtures/cases/d/scripts/old-stack.sh /tmp/jwt-old /tmp/jwt-logs
```

`new-stack.sh` runs the PHP 8.3 ladder on a second clone the same way, and `emit-scan.sh <dir> <image> <log>` reruns a suite with deprecations, notices and warnings fatal.

`seal.mjs` cannot reproduce `selection.json` later, because it reads a live GitHub search; the sealed file and its hash are the record. The prediction comes from the macOS executable whose hash is in `prediction.sha256`; a newer build has more rules and gives a different result. That executable reports 260 rules while the tree at the freeze held 296 YAML files; the difference was not reconciled, so the executable hash is the binding record, not `rules-tree.sha256`. The capture ran with the Linux arm64 executable listed there, in the image built by `scripts/Dockerfile` (PHP 7.4 on Debian bullseye through `archive.debian.org`, Composer 2.10.3, and the extensions `intl zip bcmath gmp pcntl` added to both images).

After the freeze, `upgrade-check` was changed so a rule whose matched clauses read only the target version keys is listed under "Version boundary crossed" and not under "Will break". `post-fix.json` holds the rerun of the frozen capture with the current tree (296 rules): will break 0, may break 1, version boundary crossed 11 (the same 11 rules), unknown 0. Nothing in it changes the frozen score or the unpredicted vfsStream break; `selection.json`, `prediction.json` and `score.json` are untouched.

The scanner now records the `require.php` constraint of every package in `composer.lock`, and `upgrade-check` compares it with the target. `capture-php-7-4-p3-lock.json` is the frozen capture with the `project.composer.*` attributes replaced by a scan of the 4.1.4 tree (78 packages with a constraint; the version keys are identical to the frozen ones), and `post-fix-p3-lock.json` is the rerun. Its `will-break-packages` lists one package: `phpspec/prophecy` 1.12.2, constraint `^7.2 || ~8.0, <8.1`, the same text and the same package as the `composer install` failure on PHP 8.3 in `runs/jwt-new-1-install.log`. The other 77 constraints allow PHP 8.3. The rule findings are the ones in the earlier rerun plus the vfsStream rule added after it. The frozen score and `prediction.json` are untouched.
