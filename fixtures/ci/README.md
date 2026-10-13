# CI Import Fixtures

Synthetic inputs for the saved-log importers. No file here was fetched from a CI service;
each is written by hand in the shape of the real format.

- `github-raw.log`: a raw GitHub Actions job log with the runner image block and a failed step.
- `github-run.json`: the summary from `gh run view --json` for the same job.
- `gitlab-green.trace`, `gitlab-red.trace`: GitLab job traces with ANSI codes and section markers.
- `gitlab-pipeline.json`: a pipeline object as returned by the pipelines API.
- `gitlab-jobs.json`: a jobs array as returned by the pipeline jobs API; durations are fractional.
