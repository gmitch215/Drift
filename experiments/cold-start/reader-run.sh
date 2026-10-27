#!/usr/bin/env bash
# usage: reader-run.sh <n> [tag]   start scenario <n>, run one fresh Haiku reader outside the repository, stop it, score it
set -u
here=$(cd "$(dirname "$0")" && pwd)
. "$here/harness.sh"

n=${1:?scenario number}
tag=${2:-}

"$here/cold-up.sh" "$n" > /dev/null || { echo "cold-up failed for scenario $n" >&2; exit 2; }
run=$(cat "$COLD_RUN_ROOT/current-$n")
prompt=$("$here/reader-prompt.sh" "$n" "$run")
cwd=$(mktemp -d "${TMPDIR:-/tmp}/cold-reader.XXXXXX")

(
	cd "$cwd" &&
		timeout "${COLD_READER_TIMEOUT:-900}" claude -p "$prompt" --model haiku --max-turns 60 \
			--no-session-persistence \
			--allowedTools "Bash($here/cold-sh.sh:*)" "Bash($here/cold-cp.sh:*)"
) > "$run/answer.md" 2> "$run/reader.log"
echo "reader exit $?" >> "$run/reader.log"

"$here/cold-down.sh" "$n" > /dev/null
echo "== scenario $n${tag:+ $tag}: $(basename "$run")"
"$here/score.sh" "$run"
