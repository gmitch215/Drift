# Drangler Fixtures

Sanitized CI data from runs of the drangler Docker E2E workflow: runs that passed (one green run
and nightlies) and runs that failed on a later commit. The tests, the capsule diff and the lab
use them as a real example of a pipeline that went from green to red.

## Layout

- `logs/<run id>.log`: job logs from `gh run view --log`, sanitized.
- `runs/<run id>.json`: run summary from `gh run view --json` (jobs, steps, conclusions,
  durations, head commit), without job URLs and database ids.
- `diffs/<base>-<head>.diff`: the change between the last green and the first failing commit,
  with context lines removed.
- `capsules/<run id>.json`: the runner environment of the Docker E2E job (OS image, runner and
  tool versions, package versions), read from the log. `capsules/<run id>.run.json` is the
  matching descriptor: outcome, event, head commit, failing step and durations.

## Sanitization

`LogSanitizer` in the `tools` module applies deterministic rules before any file is written.
A second pass, written separately from the rules, scans the output. The run stops without
writing the file if that pass finds something that looks like a secret, an address or an
identity. GitHub had already replaced registered secrets with `***` in the logs.

Each removal is replaced by a fixed placeholder such as `<uuid>` or `<org>`, so the original
value cannot be recovered from these files. The table counts removals across all files; the
example is the sanitized line.

## Removals

| Kind | Removes | Count | Redacted example |
| --- | --- | ---: | --- |
| diff-context | unchanged context lines of a diff | 122 | `<<removed: diff context>>` |
| hex-id | hex strings of 32 or more digits (hashes, ids) | 270 | `Commit: <hex>` |
| high-entropy | mixed-case tokens of 32 or more characters | 3 | `files     1 file(s), 10.2 kB streamed; 1 protected directory left out (<token>/sync)` |
| integrity | package integrity hashes | 88 | `": "2.0.0-rc.24", "workerd": ">1.20260305.0 <2.0.0-0" }, "optionalPeers": ["workerd"] }, "<integrity>"],` |
| ipv4 | IPv4 addresses other than loopback | 24 | `58: apache2: Could not reliably determine the server's fully qualified domain name, using <ip>. Set the 'ServerName' directive globally to suppress this message` |
| ipv4-loopback | loopback IPv4 addresses, kept as a marker | 52 | `age {"type":"reloadComplete","proxyData":{"userWorkerUrl":{"protocol":"http:","hostname":"<loopback>","port":"43697"},"userWorkerInspectorUrl":{"protocol":"ws:","hostname":"<loopback>","port":"39203",` |
| ipv6 | IPv6 addresses | 3 | `tp://localhost:8902/admin/config/people/simple_oauth/oauth-21?<query>","referer":"","ip":"<loopback>","timestamp":"1790937082"}` |
| long-line | lines over 500 characters (bundled source, telemetry) | 21 | `<<removed: long line>>` |
| object-dump | lines of the wrangler object dump (bundled worker source, config) | 1723 | `<<removed: object dump>>` |
| organization | the organization names | 308 | `  repository: <org>/drangler` |
| private-package | private package names | 207 | `+ <private-pkg>@0.1.0` |
| secret-name | values after a key, token, secret, password or auth name | 24 | `  key: <redacted>` |
| server-uid | database server ids | 6 | `0:32:08 0 [Note] Starting MariaDB 13.0.2-MariaDB-ubu2604 source revision <hex> server_uid <id> as process 94` |
| url-host | URL hosts that are not on the public allowlist | 46 | `fetching https://<host>/payloads/v1.0.1/<org>-worker-1.0.1.tar.gz (cdn)` |
| url-query | URL query strings and fragments | 3 | `e","uid":0,"request_uri":"http://localhost:8902/admin/config/people/simple_oauth/oauth-21?<query>","referer":"","ip":"<loopback>","timestamp":"1790937082"}` |
| username | account names | 43 | `+ @<user>/tinyimg@1.1.0` |
| uuid | UUIDs | 748 | `Worker ID: {<uuid>}` |

## Files

| File | Raw bytes | Kept bytes | Lines in | Lines out |
| --- | ---: | ---: | ---: | ---: |
| logs/35585969640.log | 185781 | 181167 | 1779 | 1779 |
| runs/35585969640.json | 9005 | 8435 | 1 | 1 |
| logs/36232485230.log | 187465 | 182831 | 1800 | 1800 |
| runs/36232485230.json | 9005 | 8435 | 1 | 1 |
| logs/36311117223.log | 187694 | 183060 | 1802 | 1802 |
| runs/36311117223.json | 9005 | 8435 | 1 | 1 |
| logs/36412389380.log | 187670 | 183036 | 1802 | 1802 |
| runs/36412389380.json | 9005 | 8435 | 1 | 1 |
| logs/36702683742.log | 187459 | 182825 | 1800 | 1800 |
| runs/36702683742.json | 9005 | 8435 | 1 | 1 |
| logs/36995781138.log | 415156 | 335812 | 3666 | 3093 |
| runs/36995781138.json | 9478 | 8908 | 1 | 1 |
| logs/37114464625.log | 394614 | 317620 | 3514 | 2939 |
| runs/37114464625.json | 9478 | 8908 | 1 | 1 |
| logs/37195797966.log | 413689 | 334399 | 3655 | 3083 |
| runs/37195797966.json | 9478 | 8908 | 1 | 1 |
| diffs/558d2a6-df6dce4.diff | 31814 | 15031 | 263 | 141 |

## Kept

- step names, conclusions, timestamps and durations from the run JSON
- the runner image block: OS, image and provisioner versions, runner version, Azure region
- tool versions printed by setup steps and package installs (private packages are masked)
- error lines, stack frames, exit codes, signals and transport errors (EPIPE, network lost)
- loopback addresses as `<loopback>` and temp directory names with their random suffix
- the head commit hash of each run, taken from the run JSON
- the literal `^[[` color markers, which are text in the saved logs, not escape bytes
## Not Included

The raw logs are not part of this repository. They are kept outside it, and the files here are
all that is needed to run the tests.

## Regenerating

The raw directory holds `run-<id>.log` and `run-<id>.json` for each run and
`diff-<base>-<head>.patch` for each diff:

```sh
./gradlew :tools:jvmRun --args="freeze <raw-dir> fixtures/drangler"
./gradlew :tools:jvmRun --args="capsules fixtures/drangler"
```

`freeze` rewrites `logs`, `runs`, `diffs` and this file. Only the counts and byte totals in the
two tables depend on the raw input; the rest of the file is fixed text.
