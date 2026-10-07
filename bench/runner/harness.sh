#!/bin/sh
arm=$1
dir=${BENCH_DIR:-/bench}
cd "$dir" || exit 2
. "$dir/$arm.plan"
drift=${DRIFT_BIN:-/opt/drift}
if [ -x "$drift" ]; then
	echo "CAPSULE-BEGIN"
	"$drift" capture --label "$arm" --tools
	echo "CAPSULE-END"
fi
i=0
while [ "$i" -lt "$TRIALS" ]; do
	h=$(( (i * 2654435761 + SALT) % 4294967296 ))
	h=$(( (((h >> 16) ^ h) * 73244475) % 4294967296 ))
	h=$(( (((h >> 16) ^ h) * 73244475) % 4294967296 ))
	h=$(( (h >> 16) ^ h ))
	flake=0
	[ $(( h % 1000 )) -lt "$GATE" ] && flake=1
	out=$(export BENCH_TRIAL=$i BENCH_FLAKE=$flake; eval "timeout 60 $CMD" 2>&1)
	code=$?
	sig=0
	if [ "$code" -ne 0 ]; then
		if [ -n "$SIG" ] && printf '%s' "$out" | grep -qF -- "$SIG"; then sig=1; fi
		if [ -n "$SIGCODE" ] && [ "$code" -eq "$SIGCODE" ]; then sig=1; fi
	fi
	tail=$(printf '%s\n' "$out" | tail -n 1 | cut -c1-120 | tr '\t\r' '  ')
	printf 'TRIAL\t%s\t%s\t%s\t%s\t%s\n' "$i" "$code" "$flake" "$sig" "$tail"
	i=$((i + 1))
done
echo DONE
