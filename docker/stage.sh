#!/usr/bin/env bash
set -euo pipefail

# usage: stage.sh <context dir> <studio dir> <linux-x64 archive dir> <linux-arm64 archive dir>
ctx=${1:?context dir}
studio=${2:?studio dir}
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)

rm -rf "$ctx"
mkdir -p "$ctx/bin/amd64" "$ctx/bin/arm64" "$ctx/doc"
for pair in "amd64:${3:?x64 archive dir}" "arm64:${4:?arm64 archive dir}"; do
    arch=${pair%%:*}
    dir=${pair#*:}
    (cd "$dir" && sha256sum -c ./*.sha256)
    tar -xzf "$dir"/*.tar.gz -C "$ctx/bin/$arch" drift
done
cp -R "$studio" "$ctx/studio"
cp "$root/LICENSE" "$root/notices/THIRD-PARTY-NOTICES.txt" "$ctx/doc/"
