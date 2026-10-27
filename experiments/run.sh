#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/.." && pwd)
host=${PAISLEY_HOST:-paisley-park}
image=${PAISLEY_IMAGE:-drift-cisim:3}
mem=${PAISLEY_MEM:-10g}
cpus=${PAISLEY_CPUS:-8}
rig='$HOME/drift-rig'

usage() {
    echo "usage: run.sh <name> [--paisley [--fetch]] [experiment args]"
    echo "experiments:"
    for d in "$here"/*/run.sh; do echo "  $(basename "$(dirname "$d")")"; done
}

[ $# -ge 1 ] || { usage >&2; exit 64; }
case $1 in -h | --help) usage; exit 0 ;; esac
name=$1
shift
[ -x "$here/$name/run.sh" ] || { echo "no experiment $name" >&2; usage >&2; exit 64; }

if [ "${1:-}" != --paisley ]; then
    exec "$here/$name/run.sh" "$@"
fi
shift

dest=$rig/experiments/$name
if [ "${1:-}" = --fetch ]; then
    shift
    ssh "$host" "cat $dest/run.done" || { echo "not finished" >&2; exit 1; }
    mkdir -p "$here/results/$name/runs"
    ssh "$host" "cd $dest/experiments/results/$name/runs && tar -cf - ." | tar -xf - -C "$here/results/$name/runs"
    exit 0
fi

export COPYFILE_DISABLE=1
ssh "$host" 'test "$(df -Pk / | awk "NR==2{print \$4}")" -gt 26214400' ||
    { echo "paisley / below 25 GB free" >&2; exit 98; }
ssh "$host" "mkdir -p $dest $rig/gradle $rig/konan"
(cd "$repo" && tar --no-xattrs -cf - gradlew gradle/wrapper "experiments/$name" experiments/run.sh) |
    ssh "$host" "tar -xf - -C $dest"
args=$(printf ' %q' "$@")
job="docker run --rm --user \"\$(id -u):\$(id -g)\" --memory $mem --memory-swap $mem --cpus $cpus \
-e HOME=/work/home -e CI=true -e GRADLE_USER_HOME=/work/gradle -e KONAN_DATA_DIR=/konan \
-v $dest:/work/tree -v $rig/gradle:/work/gradle -v $rig/konan:/konan -w /work/tree $image \
experiments/$name/run.sh$args"
if [ "${NET:-1}" = 1 ]; then
    job="mkdir /tmp/paisley-net.lock && { $job; s=\$?; rmdir /tmp/paisley-net.lock; exit \$s; }"
fi
ssh "$host" "cd $dest && if [ -e run.done ]; then echo previous run.done exists, move it with gio trash >&2; exit 3; fi; nohup setsid bash -c '( $job ) > run.log 2>&1; echo rc=\$? > run.done' > /dev/null 2>&1 & echo started"
echo "check: ssh $host 'cat $dest/run.done; tail $dest/run.log'"
echo "fetch: experiments/run.sh $name --paisley --fetch"
