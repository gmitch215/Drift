#!/usr/bin/env bash
# usage: cold-sh.sh <n> here|there '<command>'   run a command as the reader in a container
set -u
. "$(dirname "$0")/harness.sh"

n=${1:?scenario number}
side=${2:?here or there}
cmd=${3:?command}
run=$(cold_run_dir "$n")
[ -n "$run" ] || { echo "scenario $n is not up" >&2; exit 2; }
if [ "$side" = there ] && [ "$(cold_mode "$run")" = single ]; then
	echo "scenario $n is single-machine; there does not exist" >&2
	exit 2
fi
c=$(cold_container "$n" "$side")
out="$run/transcript.log"

printf '$ %s\n' "$cmd" | tee -a "$out"
cold_ssh "docker exec -w /home/reader/project $c sh -c $(printf '%q' "$cmd")" 2>&1 | tee -a "$out"
rc=${PIPESTATUS[0]}
cold_log "$n" "$side" sh "$rc" "$cmd" "$run"
exit "$rc"
