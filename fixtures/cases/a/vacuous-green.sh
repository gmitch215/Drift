#!/bin/sh
set -eu
logs=${1:-fixtures/drangler/logs}
greens="35585969640 36232485230 36311117223 36412389380 36702683742"
reds="36995781138 37114464625 37195797966"

plain() {
    sed 's/\^\[\[[0-9;]*m//g' "$logs/$1.log"
}

count() {
    plain "$1" | grep -c -F -- "$2" || true
}

printf '%-12s %-13s %-9s %s\n' 'test' 'green runs' 'red runs' 'failing test'
while IFS='|' read -r label name; do
    g=0
    r=0
    for id in $greens; do
        n=$(plain "$id" | grep -F -- "✓ $name" | wc -l | tr -d ' ')
        [ "$n" -gt 0 ] && g=$((g + 1))
    done
    for id in $reds; do
        n=$(plain "$id" | grep -F -- "× $name" | wc -l | tr -d ' ')
        [ "$n" -gt 0 ] && r=$((r + 1))
    done
    printf '%-12s %-13s %-9s %s\n' "$label" "$g of 5 passed" "$r of 3 failed" "$name"
done <<'TESTS'
to-worker|the converted dump loads into a real Durable Object with the bytes intact
to-worker|what the migration REPORTED matches what actually moved
modify|requires simple_oauth, uploads simple_oauth_21 and enables it
modify|reports a module whose enable is refused and leaves the site serving
TESTS
