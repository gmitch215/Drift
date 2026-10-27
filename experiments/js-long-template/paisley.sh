#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
usage() { echo "usage: paisley.sh <label> <v1,v2,...>   (lane b1; label must be new)" >&2; exit 64; }
label=${1:?}
versions=${2:?}
[ $# -eq 2 ] || usage

export COPYFILE_DISABLE=1
"$repo/.claude/batch/paisley-lane.sh" sync b1 experiments/js-long-template
ssh paisley-park "mkdir -p ~/drift-rig/lanes/b1/loops ~/drift-rig/lanes/b1/logs && cat > ~/drift-rig/lanes/b1/loops/$label.sh" < "$here/paisley-loop.sh"
ssh paisley-park "cd ~/drift-rig/lanes/b1 && if [ -e logs/$label.log ]; then echo 'label $label is used' >&2; exit 3; fi; nohup setsid bash loops/$label.sh $label $versions > logs/$label.log 2>&1 < /dev/null & echo started $label"
