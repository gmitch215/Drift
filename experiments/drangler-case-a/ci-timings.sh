#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
logs=${LOGS:-$here/../../fixtures/drangler/logs}
limit=${LIMIT_MS:-5000}

[ -d "$logs" ] || { echo "no log directory $logs" >&2; exit 1; }

rows=$(for f in "$logs"/*.log; do
    sed -E 's/\x1b\[[0-9;]*m//g; s/\^\[\[[0-9;]*m//g' "$f" | awk -v id="$(basename "$f" .log)" '
        /converts the whole dump/ { c = $NF; sub(/ms$/, "", c) }
        /replays into a plain SQLite/ { r = $NF; sub(/ms$/, "", r) }
        /(✓|×).*loads into a real Durable Object/ { st = ($0 ~ /×/) ? "red" : "green" }
        END { if (c != "" && r != "" && st != "") print id, c, r, c + r, st }'
done)

echo "run converts_ms replays_ms sync_ms outcome"
echo "$rows"
echo "$rows" | awk -v limit="$limit" '
    $5 == "green" { g++; if ($4 > gmax) gmax = $4 }
    $5 == "red" { r++; if (rmin == "" || $4 < rmin) rmin = $4 }
    END {
        printf "green runs %d, longest sync time %d ms; red runs %d, shortest sync time %d ms; limit %d ms\n", g, gmax, r, rmin, limit
        if (g == 0 || r == 0 || gmax >= limit || rmin <= limit) { print "FAIL: the sync time does not separate green from red at the limit"; exit 1 }
        print "PASS: every green run is under the limit and every red run is over it"
    }'
