#!/usr/bin/env bash
# usage: cold-cp.sh <n> here|there <path> here|there   copy /home/reader/<path> between containers
set -u
. "$(dirname "$0")/harness.sh"

n=${1:?scenario number}
from=${2:?source side}
path=${3:?path under /home/reader}
to=${4:?destination side}
run=$(cold_run_dir "$n")
[ -n "$run" ] || { echo "scenario $n is not up" >&2; exit 2; }
[ "$(cold_mode "$run")" = pair ] || { echo "scenario $n is single-machine; there is no copy target" >&2; exit 2; }
case "$n" in
	*[!0-9]*) echo "scenario must be a number" >&2; exit 2 ;;
esac
for side in "$from" "$to"; do
	case "$side" in
		here|there) ;;
		*) echo "side must be here or there" >&2; exit 2 ;;
	esac
done
case "$path" in
	/*|*..*|*[!A-Za-z0-9._/-]*) echo "path must be a plain relative path under /home/reader" >&2; exit 2 ;;
esac
dir=$(dirname "$path")
src=$(cold_container "$n" "$from")
dst=$(cold_container "$n" "$to")
desc="cp $from:$path -> $to:$dir/"
src_q=$(printf '%q' "$src:/home/reader/$path")
dst_q=$(printf '%q' "$dst:/home/reader/$dir")

printf '$ %s\n' "$desc" | tee -a "$run/transcript.log"
cold_ssh "docker cp $src_q -" | cold_ssh "docker cp - $dst_q" 2>&1 | tee -a "$run/transcript.log"
rc=${PIPESTATUS[0]}
cold_log "$n" "$from>$to" cp "$rc" "$path" "$run"
exit "$rc"
