#!/usr/bin/env bash
# usage: run.sh --check   validate the scenarios, the answer keys and the scorer (no Docker, no network)
set -eu
here=$(cd "$(dirname "$0")" && pwd)

case "${1:-}" in
--check) ;;
*)
	echo "usage: run.sh --check" >&2
	echo "bring a scenario up with cold-up.sh <n> [alt], score a run with score.sh <run-dir>" >&2
	exit 2
	;;
esac

fail=0
for f in harness.sh cold-up.sh cold-down.sh cold-sh.sh cold-cp.sh score.sh run.sh; do
	bash -n "$here/$f" || { echo "syntax: $f"; fail=1; }
done

for n in 1 2 3 4 5 6 7 8; do
	for f in brief.md scenario.env; do
		[ -f "$here/scenarios/$n/$f" ] || { echo "missing scenarios/$n/$f"; fail=1; }
	done
	[ -d "$here/scenarios/$n/project" ] || { echo "missing scenarios/$n/project"; fail=1; }
	[ -f "$here/answers/$n.md" ] || { echo "missing answers/$n.md"; fail=1; }
	grep -q '^## Accept' "$here/answers/$n.md" || { echo "answers/$n.md has no Accept list"; fail=1; }
done
[ -f "$here/answers/8-alt.md" ] || { echo "missing answers/8-alt.md"; fail=1; }
[ -f "$here/scenarios/8/alt/issue.md" ] || { echo "missing scenarios/8/alt/issue.md"; fail=1; }
for n in 9 10 11 12 13 14; do
	for f in brief.md scenario.env; do
		[ -f "$here/scenarios/$n/$f" ] || { echo "missing scenarios/$n/$f"; fail=1; }
	done
	[ -d "$here/scenarios/$n/project" ] || { echo "missing scenarios/$n/project"; fail=1; }
	[ -f "$here/answers/$n.md" ] || { echo "missing answers/$n.md"; fail=1; }
	grep -q '^## Accept' "$here/answers/$n.md" || { echo "answers/$n.md has no Accept list"; fail=1; }
	if [ "$n" = 12 ]; then
		grep -q '^MODE=single$' "$here/scenarios/$n/scenario.env" || { echo "scenarios/$n is not single-machine"; fail=1; }
	else
		grep -q '^THERE_IMAGE=' "$here/scenarios/$n/scenario.env" || { echo "scenarios/$n has no THERE_IMAGE"; fail=1; }
	fi
done
for f in README.md READER.md SCORING.md; do
	[ -f "$here/$f" ] || { echo "missing $f"; fail=1; }
done

tmp=$(mktemp -d)
mkdir "$tmp/1-20000101T000000Z"
printf 'started=%s\n' "$(date -u +%s)" > "$tmp/1-20000101T000000Z/run.env"
printf 'a\nb\nc\n' > "$tmp/1-20000101T000000Z/commands.log"
printf '## Confusion\n- none\n' > "$tmp/1-20000101T000000Z/answer.md"
printf 'the cause is node version (tool.node.version)\n' >> "$tmp/1-20000101T000000Z/answer.md"
out=$("$here/score.sh" "$tmp/1-20000101T000000Z")
rm -r "$tmp"
case "$out" in
*"answer matches key: yes"*"verdict: PASS"*) ;;
*) echo "score.sh self-test failed:"; echo "$out"; fail=1 ;;
esac

tmp=$(mktemp -d)
mkdir "$tmp/9-20000101T000000Z"
printf 'scenario=9\nmode=single\nstarted=%s\n' "$(date -u +%s)" > "$tmp/9-20000101T000000Z/run.env"
printf '%s\n' "$tmp/9-20000101T000000Z" > "$tmp/current-9"
rc=0
out=$(COLD_RUN_ROOT="$tmp" "$here/cold-sh.sh" 9 there true 2>&1) || rc=$?
rm -r "$tmp"
case "$rc$out" in
	2*single-machine*) ;;
	*) echo "single-machine guard failed (rc=$rc): $out"; fail=1 ;;
esac

[ "$fail" -eq 0 ] && echo "cold-start check: ok (14 scenarios, 15 keys, single-machine guard, scorer self-test)" || exit 1
