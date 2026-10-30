#!/usr/bin/env bash
# usage: run.sh [readers per persona] [out dir]   first-look test of the README with isolated Haiku readers
set -eu

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
per=${1:-2}
out=${2:-$repo/experiments/results/first-look/$(date -u +%Y%m%dT%H%M%SZ)}
mkdir -p "$out"

stage() {
    dir=$(mktemp -d "${TMPDIR:-/tmp}/drift-first-look.XXXXXX")
    cp "$repo/README.md" "$repo/ADVANCED_USAGE.md" "$repo/MANUAL.md" "$dir/"
    cp -R "$repo/docs" "$dir/docs"
    echo "$dir"
}

persona() {
    case $1 in
        judge)
            echo "You are a software engineer judging a student coding contest. You score creativity, use of Kotlin Multiplatform and code conventions, and you must be able to launch the project from its README. You have five minutes for this project."
            ;;
        junior)
            echo "You are a junior developer. Your CI job failed and passes on your laptop. Someone linked you to this project's README and said it might help. You have never heard of it."
            ;;
        maintainer)
            echo "You maintain an open source project. A user filed a bug report that only fails on their machine, and you cannot reproduce it. A colleague linked you to this README. You have five minutes."
            ;;
    esac
}

ask() {
    cat <<EOF
$(persona "$1")

The files in the current folder are everything you know about this project: README.md first, and the pages it links in docs/. You can only read files; you cannot run commands, search the web or open any other file. Spend at most about five minutes of reading, then answer.

Reply with exactly these sections and nothing else:
## What it is - one sentence in your own words.
## Who it is for - one sentence.
## What I would type first - the exact command, and what you expect to see.
## Where I got lost - each point where you were confused, stopped or had to guess, with the README line quoted. Write "none" if nothing.
## Keep reading or leave - which, and the one reason.
## Scores - three lines, each 1 to 5: understood what it does; knew what to run first; believed it works.
EOF
}

run_one() {
    persona=$1
    n=$2
    dir=$(stage)
    (cd "$dir" && timeout 600 claude -p "$(ask "$persona")" --model haiku --max-turns 14 \
        --tools Read,Glob,Grep --allowedTools Read Glob Grep) > "$out/$persona-$n.md" 2> "$out/$persona-$n.err" || true
    trash "$dir"
}

for persona in judge junior maintainer; do
    for n in $(seq 1 "$per"); do
        run_one "$persona" "$n" &
    done
done
wait
echo "$out"
