# CI Import Fixtures

Inputs for the saved-log importers and for `drift ci`. The files in this directory are written by
hand in the shape of the real format; `live/` holds recordings.

- `github-raw.log`: a raw GitHub Actions job log with the runner image block and a failed step.
- `github-run.json`: the summary from `gh run view --json` for the same job.
- `gitlab-green.trace`, `gitlab-red.trace`: GitLab job traces with ANSI codes and section markers.
- `gitlab-pipeline.json`: a pipeline object as returned by the pipelines API.
- `gitlab-jobs.json`: a jobs array as returned by the pipeline jobs API; durations are fractional.

## Live Recordings

`live/` holds answers recorded from public CI data with read-only calls. `drift ci`
replays them through a fake host; the golden files in `live/golden` are its printed result.

- `live/github`: runs of `gmitch215/Drift`, workflow Build Project on `master`. The failing run is
  40042665061 (c775d8f) and the passing run is 38452229524 (da0505e).
  - `run-list-failure.json` and `run-list-success.json`: `gh run list -R gmitch215/Drift ... --json
    databaseId,workflowName,workflowDatabaseId,headSha,headBranch,url,createdAt,event,conclusion`,
    cut to the first three entries.
  - `run-red.json` and `run-green.json`: `gh run view ID --json databaseId,conclusion,jobs`, whole.
  - `log-*-linux-x64.log` and `log-*-macos-arm64.log`: `gh run view ID --log --job JOBID`, cut to
    the lines up to `Complete job name:` (runner version, provisioner, operating system, runner
    image and permissions), plus the 40 lines before the first `BUILD FAILED` or `BUILD SUCCESSFUL`
    through the first `##[error]` line. The byte order mark on the first line is kept.
- `live/gitlab`: the public project `gitlab-org/cli`, branch `main`, job `govulncheck`. This
  repository has no GitLab pipelines, so it supplies no GitLab pair. The failing pipeline is
  2970143654 and the passing one is 2969877432.
  - `pipelines-failed.json` and `pipelines-success.json`: `glab api` on `projects/gitlab-org%2Fcli/
    pipelines` with `status`, `per_page`, `ref` and `source=push`, trimmed to a few entries (the
    success list keeps two pipelines newer than the failing one and two older).
  - `jobs-red.json` and `jobs-green.json`: the pipeline jobs endpoint, cut to the fields `id`,
    `name`, `stage`, `status`, `allow_failure`, `duration` and `web_url`.
  - `trace-red.txt` and `trace-green.txt`: the job trace endpoint, with the lines after the
    `step_script` section removed (artifact upload lines carry a partial job token); the failing
    trace keeps its final `ERROR: Job failed` line.

Every file was searched for tokens, passwords and email addresses before it was kept; the only
secret-shaped text is the `***` that Actions prints for masked values.
