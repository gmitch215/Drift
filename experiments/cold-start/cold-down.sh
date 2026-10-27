#!/usr/bin/env bash
# usage: cold-down.sh <n>   stop the here and there containers for scenario <n>
set -eu
. "$(dirname "$0")/harness.sh"

n=${1:?scenario number}
run=$(cold_run_dir "$n")
[ -n "$run" ] || { echo "scenario $n is not up" >&2; exit 2; }
names="$(cold_container "$n" here)"
[ "$(cold_mode "$run")" = single ] || names="$names $(cold_container "$n" there)"
cold_ssh "docker stop -t 2 $names" >/dev/null
printf 'ended=%s\n' "$(date -u +%s)" >> "$run/run.env"
echo "down: $n ($run)"
