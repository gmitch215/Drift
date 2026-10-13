Follow-up rule ideas from case D. Written after the score; nothing here changed `rules/` or the frozen prediction, and nothing here was measured on a second project.

| idea | what the case showed | what the rule would read |
| --- | --- | --- |
| Gate `php-8-0-create-function-each-removed` on the call facts | it was a false positive; the capture had no `project.php.calls.each` or `project.php.calls.create_function` and the scanner emits both | `project.php.calls.each`, `project.php.calls.create_function`, with the version crossing |
| Gate the other removed-API rules on a source fact | `php-8-0-implode-reversed-arguments-removed` and `php-8-0-at-operator-no-longer-silences-fatal` fired on a version crossing alone; all 12 findings are version crossings and none of the 12 rules reads a source fact | a scanner call key per API; none exists for `implode`, `@` or `htmlspecialchars` yet |
| Locked package that excludes the target PHP | `composer install` from the lock stopped on `phpspec/prophecy 1.12.2` (`php ^7.2 \|\| ~8.0, <8.1`), before any test ran; the capture held the version | a table of locked package versions with an upper PHP bound, read from `project.composer.<vendor>/<package>` |
| Tentative return types (PHP 8.1) | with the lock held, `mikey179/vfsstream` 1.6.8 stopped the run with a fatal error on `getIterator()` | `project.composer.mikey179/vfsstream` below the release that added the attribute (UNVERIFIED which release); a source fact for classes that implement an internal interface |
| Dependency drift under `composer update` | the scored run moved phpunit 9.5.0 to 9.6.38 and 59 other packages | outside a capture of today; document as a limit |

The first two rows would have removed false positives on this project. The third and fourth would have named the only two things that broke. None of them can be accepted on this evidence alone: one project, one outcome.
