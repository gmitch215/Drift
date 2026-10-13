#!/bin/sh
# usage: solve-candidate.sh DRIFT OUTDIR GREEN RED WORKSPACE FAIL_WHEN RUN [DRIFT_LINUX_BINARY]
set -eu
drift=$1
out=$2
green=$3
red=$4
ws=$5
failwhen=$6
run=$7
bin=${8:-}
mkdir -p "$out"
"$drift" diagnose --frame environment --detail summary --name case "$green" "$red" >"$out/diagnose.txt" 2>&1 || true
set +e
if [ -n "$bin" ]; then
    "$drift" solve --run "$run" --fail-when "$failwhen" --workspace "$ws" --drift-binary "$bin" \
        --exit-status --case "$out/case" --name case "$green" "$red" >"$out/solve.out" 2>"$out/solve.err"
else
    "$drift" solve --run "$run" --fail-when "$failwhen" --workspace "$ws" \
        --exit-status --case "$out/case" --name case "$green" "$red" >"$out/solve.out" 2>"$out/solve.err"
fi
echo $? >"$out/solve.exit"
