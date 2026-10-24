#!/bin/sh
root=$1
pull=0
[ "$2" = "--pull" ] && pull=1
DOCKER_CONFIG=${TMPDIR:-/tmp}/drift-solve-docker-config
mkdir -p "$DOCKER_CONFIG"
export DOCKER_CONFIG
sizes=${SIZES:-$root/image-sizes.txt}
tags=$(cat "$root"/*/images | sort -u)
if [ "$pull" -eq 0 ]; then
    echo "$tags"
    exit 0
fi
ordered=$(
    for tag in $tags; do
        size=$(awk -v t="$tag" '$1 == t {print $2}' "$sizes" 2> /dev/null)
        echo "${size:-99999999999999} $tag"
    done | sort -n | awk '{print $2}'
)
for tag in $ordered; do
    docker image inspect "$tag" > /dev/null 2>&1 && continue
    avail=$(df -Pk / | awk 'NR==2 {print $4}')
    if [ "$avail" -lt 26214400 ]; then
        echo "STOP low disk: $avail KB free"
        exit 3
    fi
    echo "pull $tag"
    docker pull --quiet "$tag" > /dev/null || echo "FAILED $tag"
done
echo "PREPULL DONE"
