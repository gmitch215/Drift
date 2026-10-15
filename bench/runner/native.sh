#!/bin/sh
root=$1
for d in "$root"/*/; do
    id=$(basename "$d")
    [ -f "$d/green.plan" ] || continue
    if [ -f "$d/native.skip" ]; then
        echo "$id skipped: $(cat "$d/native.skip")"
        continue
    fi
    for arm in green red; do
        [ -s "$d/$arm.out" ] && continue
        start=$(date +%s)
        (
            cd "$d" || exit 2
            set -e
            [ -f "$arm.env" ] && . "./$arm.env"
            BENCH_DIR=$(pwd) exec sh ./harness.sh "$arm"
        ) > "$d/$arm.out.tmp" 2> "$d/$arm.err"
        code=$?
        mv "$d/$arm.out.tmp" "$d/$arm.out"
        echo "$id $arm exit=$code secs=$(( $(date +%s) - start ))"
    done
done
echo "BATCH DONE"
