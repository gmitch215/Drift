#!/usr/bin/env bash
set -euo pipefail

in=${1:?input directory of issue files}
out=${2:?output directory}
bin=${DRIFT:-drift}

mkdir -p "$out"
for f in "$in"/*.md; do
    b=$(basename "$f" .md)
    "$bin" triage --json "$f" > "$out/$b.json" 2> "$out/$b.err" || echo "exit $? on $b" >> "$out/failures.txt"
done
echo "$(ls "$in" | wc -l) inputs, $(ls "$out"/*.json | wc -l) outputs, $(cat "$out/failures.txt" 2>/dev/null | wc -l) failures"
