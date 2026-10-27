#!/usr/bin/env bash
set -u
label=$1
versions=$2
lane=drift-lane-b1
root=$HOME/drift-rig/lanes/b1
mkdir -p "$root/logs" "$root/runs"
for v in ${versions//,/ }; do
    avail=$(df -Pk / | awk 'NR==2{print $4}')
    if [ "$avail" -le 26214400 ]; then
        echo "stop before $v: / below 25 GB free"
        break
    fi
    until mkdir /tmp/paisley-net.lock 2>/dev/null; do sleep 30; done
    docker exec -w /work/tree "$lane" bash experiments/js-long-template/run.sh --versions "$v" --out "/work/runs/$label"
    rc=$?
    rmdir /tmp/paisley-net.lock
    echo "$v exit $rc at $(date -u +%H:%M:%S)"
done
echo done > "$root/logs/$label.done"
