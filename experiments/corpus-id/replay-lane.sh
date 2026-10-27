#!/usr/bin/env bash
set -euo pipefail

stage=${1:?a, b or c}
dir=${2:?replay directory}
variant=${3:-}
bin=${DRIFT:-drift}

if [ -n "$variant" ]; then
    out="out-$stage-$variant"
    reply="$variant.txt"
else
    out="out-$stage"
    reply="txt"
fi

mkdir -p "$dir/work" "$dir/$out"
for f in "$dir"/in/*.md; do
    b=$(basename "$f" .md)
    work="$dir/work/$b"
    mkdir -p "$work"
    if [ "$stage" = c ]; then
        [ -f "$dir/reply/$b.works.txt" ] && [ -f "$dir/sessions-b/$b.json" ] || continue
        mkdir -p "$work/.drift/why"
        cp "$dir/sessions-b/$b.json" "$work/.drift/why/session.json"
        (cd "$work" && "$bin" why --reply --works --json < "$dir/reply/$b.works.txt" > "$dir/out-c/$b.json" 2> "$dir/out-c/$b.err" || true)
    elif [ "$stage" = a ]; then
        (cd "$work" && "$bin" why --json "$f" > "$dir/out-a/$b.json" 2> "$dir/out-a/$b.err" || true)
        if [ -f "$work/.drift/why/session.json" ]; then
            cp "$work/.drift/why/session.json" "$dir/out-a/$b.session.json"
        fi
    else
        [ -f "$dir/reply/$b.$reply" ] || continue
        mkdir -p "$work/.drift/why"
        cp "$dir/sessions/$b.json" "$work/.drift/why/session.json"
        (cd "$work" && "$bin" why --reply --json < "$dir/reply/$b.$reply" > "$dir/$out/$b.json" 2> "$dir/$out/$b.err" || true)
        if [ -z "$variant" ] && [ -f "$work/.drift/why/session.json" ]; then
            mkdir -p "$dir/sessions-b"
            cp "$work/.drift/why/session.json" "$dir/sessions-b/$b.json"
        fi
    fi
done
echo "stage $stage: $(ls "$dir/$out"/*.json 2>/dev/null | wc -l) outputs"
