#!/usr/bin/env sh
set -u
version="$1"
target="$2"
start=$(date +%s)
echo "batch $version $target start $(date -u +%FT%TZ)"
sh /rig/ladder/ladder.sh compile "$version" "$target" && sh /rig/ladder/ladder.sh run "$version" "$target"
sh /rig/ladder/ladder.sh assemble "$version" "$target"
echo "batch $version $target done in $(($(date +%s) - start)) s"
