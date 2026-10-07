#!/bin/sh
set -eu

repo=${1:?usage: wait-assets.sh repository tag directory asset...}
tag=${2:?tag}
dir=${3:?directory}
shift 3

base=${RELEASE_BASE_URL:-https://github.com/$repo/releases/download/$tag}
attempts=${WAIT_ATTEMPTS:-18}
pause=${WAIT_SECONDS:-10}

mkdir -p "$dir"

fetch() {
	n=1
	while [ "$n" -le "$attempts" ]; do
		if curl -fsSL -o "$dir/$1" "$base/$1"; then
			return 0
		fi
		echo "$1 is not downloadable yet (attempt $n of $attempts)" >&2
		sleep "$pause"
		n=$((n + 1))
	done
	echo "$1 did not become downloadable from $base" >&2
	return 1
}

fetch SHA256SUMS
: > "$dir/.subset"
for name in "$@"; do
	fetch "$name"
	line=$(awk -v n="$name" '$2 == n' "$dir/SHA256SUMS")
	if [ -z "$line" ]; then
		echo "$name is not listed in SHA256SUMS" >&2
		exit 1
	fi
	echo "$line" >> "$dir/.subset"
done
(cd "$dir" && sha256sum -c .subset)
