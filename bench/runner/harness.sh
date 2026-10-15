#!/bin/sh
gate_hash() {
    h=$(( ($1 * 2654435761 + $2) % 4294967296 ))
    h=$(( (((h >> 16) ^ h) * 73244475) % 4294967296 ))
    h=$(( (((h >> 16) ^ h) * 73244475) % 4294967296 ))
    h=$(( (h >> 16) ^ h ))
}
limit() {
    if command -v timeout > /dev/null 2>&1; then
        timeout 60 "$@"
    else
        perl -e 'alarm 60; exec @ARGV' -- "$@"
    fi
}
if [ "$1" = "--hash" ]; then
    while read -r trial salt; do
        gate_hash "$trial" "$salt"
        echo "$h"
    done
    exit 0
fi
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
    gate_hash "$i" "$SALT"
    flake=0
    [ $(( h % 1000 )) -lt "$GATE" ] && flake=1
    out=$(export BENCH_TRIAL=$i BENCH_FLAKE=$flake; eval "limit $CMD" 2>&1)
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
