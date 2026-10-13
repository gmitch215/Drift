#!/bin/sh
# usage: capture.sh DRIFT_LINUX_BINARY WORKSPACE IMAGE LABEL OUT [PRE_COMMAND]
set -eu
bin=$1
ws=$2
image=$3
label=$4
out=$5
pre=${6:-true}
docker run --rm -v "$(dirname "$bin")":/d:ro -v "$ws":/work -w /work --entrypoint sh "$image" \
    -c "$pre >/dev/null 2>&1; /d/$(basename "$bin") capture --tools --probes --label $label" >"$out"
