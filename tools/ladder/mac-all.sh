#!/usr/bin/env sh
set -u
here="$(dirname "$0")"
rig="${LADDER_RIG:?set LADDER_RIG to the mac rig directory}"
for spec in "$@"; do
	version="${spec%%:*}"
	for target in $(echo "${spec#*:}" | tr ',' ' '); do
		echo "mac $version $target start $(date -u +%FT%TZ)"
		sh "$here/mac.sh" "$version" "$target" > "$rig/ladder/logs/$version-$target.log" 2>&1
		echo "mac $version $target exit $? at $(date -u +%FT%TZ)"
	done
done
