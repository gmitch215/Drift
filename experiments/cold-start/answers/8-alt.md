# Answer key, scenario 8, variant alt (never shown to readers)

Report: `scenarios/8/alt/issue.md`. The suite stops with `Too many open files` on the runner and passes on the laptop.
Attribute: `limits.nofile` (the runner's open-file limit is too low for the suite).
Fix: raise the open-file limit on the runner, or close files in the suite.

## Accept
- limits.nofile
- nofile
- open file limit
- open-file limit
- too many open files
- ulimit
