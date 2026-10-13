#!/usr/bin/env sh
set -eu

here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -r "$tmp"' EXIT

RUNS=3 WARMUP=1 "$here/perf.sh" native /bin/echo "$tmp/t-echo.json" test echo
RUNS=3 WARMUP=1 "$here/perf.sh" wasm "$here" "$tmp/t-wasm.json" test wasm "$here/measure.py"

table=$(python3 "$here/summarize.py" "$tmp")
echo "$table"
echo "$table" | grep -q '^| test | echo | ' || { echo "missing echo row" >&2; exit 1; }
echo "$table" | grep -q '^| test | wasm | n/a | n/a | n/a | n/a | ' || { echo "missing wasm row" >&2; exit 1; }
