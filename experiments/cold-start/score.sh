#!/usr/bin/env bash
# usage: score.sh <run-dir>   score one cold run against its answer key
set -u
. "$(dirname "$0")/harness.sh"

run=${1:?run directory}
name=$(basename "$run")
n=${name%%-*}
rest=${name#*-}
variant=""
case "$rest" in alt-*) variant=alt ;; esac
key="$COLD_DIR/answers/$n${variant:+-$variant}.md"
answer="$run/answer.md"

[ -f "$key" ] || { echo "no answer key for $name: $key" >&2; exit 2; }
if grep -q '^Status: UNVERIFIED' "$key"; then
	echo "scenario $n: answer key is UNVERIFIED, run not scored"
	exit 2
fi

total=0
cmds=0
cmds_old=0
if [ -f "$run/commands.log" ]; then
	total=$(wc -l < "$run/commands.log" | tr -d ' ')
	cmds=$(awk -F'\t' '$4=="cp" || $6 ~ /(^|[ ;&|(])drift +[a-z]/ {n++} END {print n+0}' "$run/commands.log")
	cmds_old=$(awk -F'\t' '$4=="cp" || $6 ~ /(^|[ ;&|(])drift( |$)/ {n++} END {print n+0}' "$run/commands.log")
fi

secs=""
started=$(sed -n 's/^started=//p' "$run/run.env" 2>/dev/null)
if [ -f "$answer" ] && [ -n "$started" ]; then
	mtime=$(stat -f %m "$answer" 2>/dev/null || stat -c %Y "$answer")
	secs=$((mtime - started))
fi

accept=$(awk '/^## Accept/{f=1; next} /^## /{f=0} f && /^- /{sub(/^- /, ""); print}' "$key")
matched=no
if [ -f "$answer" ]; then
	while IFS= read -r a; do
		[ -n "$a" ] || continue
		if grep -Fqi -- "$a" "$answer"; then matched=yes; break; fi
	done <<< "$accept"
fi

named=no
if [ -f "$run/transcript.log" ]; then
	drift_out=$(awk '/^\$ /{blk = ($0 ~ /drift /)} blk' "$run/transcript.log")
	while IFS= read -r a; do
		[ -n "$a" ] || continue
		if printf '%s\n' "$drift_out" | grep -Fqi -- "$a"; then named=yes; break; fi
	done <<< "$accept"
fi

reasons=""
[ "$matched" = yes ] || reasons="$reasons no-matching-answer;"
[ "$cmds" -le 5 ] || reasons="$reasons commands-$cmds-over-5;"
if [ -z "$secs" ]; then
	reasons="$reasons no-answer-time;"
elif [ "$secs" -gt 600 ]; then
	reasons="$reasons seconds-$secs-over-600;"
fi
verdict=PASS
[ -z "$reasons" ] || verdict="FAIL ($reasons)"

echo "run: $run"
echo "scenario: $n${variant:+ variant $variant}"
echo "commands led by the README (drift and copies): $cmds (limit 5); all commands: $total; earlier rule: $cmds_old"
echo "seconds: ${secs:-none} (limit 600)"
echo "answer matches key: $matched"
echo "drift output mentions an accepted term (heuristic, a person confirms by reading the transcript): $named"
echo "verdict: $verdict"
echo "confusion:"
[ -f "$answer" ] && awk '/^## Confusion/{f=1; next} /^## /{f=0} f' "$answer"
exit 0
