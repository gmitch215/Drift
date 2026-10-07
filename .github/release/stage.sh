#!/bin/sh
set -eu

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)

digest() {
	if command -v sha256sum > /dev/null 2>&1; then
		sha256sum "$@"
	else
		shasum -a 256 "$@"
	fi
}

check() {
	if command -v sha256sum > /dev/null 2>&1; then
		sha256sum -c "$@"
	else
		shasum -a 256 -c "$@"
	fi
}

sidecar() {
	(cd "$1" && digest "$2" > "$2.sha256")
}

mode=${1:?usage: stage.sh file|archive|sums}
shift

case $mode in
	file)
		src=${1:?source file}
		name=${2:?asset name}
		mkdir -p dist
		cp "$src" "dist/$name"
		sidecar dist "$name"
		;;
	archive)
		exe=${1:?executable}
		name=${2:?asset name}
		mkdir -p dist
		out=$(cd dist && pwd)/$name
		tmp=$(mktemp -d)
		trap 'rm -rf "$tmp"' EXIT
		case $name in
			*.zip) bin=drift.exe ;;
			*) bin=drift ;;
		esac
		cp "$exe" "$tmp/$bin"
		cp "$root/LICENSE" "$tmp/LICENSE"
		cp "$here/cli-readme.txt" "$tmp/README.txt"
		case $name in
			*.zip)
				if command -v zip > /dev/null 2>&1; then
					(cd "$tmp" && zip -q "$out" "$bin" LICENSE README.txt)
				else
					(cd "$tmp" && 7z a -tzip -bd "$out" "$bin" LICENSE README.txt > /dev/null)
				fi
				;;
			*.tar.gz)
				COPYFILE_DISABLE=1 tar -czf "$out" -C "$tmp" "$bin" LICENSE README.txt
				;;
			*)
				echo "unknown archive type: $name" >&2
				exit 2
				;;
		esac
		sidecar dist "$name"
		;;
	sums)
		dir=${1:?directory}
		cd "$dir"
		for f in *.sha256; do
			check "$f"
		done
		for f in *; do
			case $f in
				*.sha256 | SHA256SUMS) ;;
				*) digest "$f" ;;
			esac
		done | LC_ALL=C sort -k 2 > .sums
		mv .sums SHA256SUMS
		;;
	*)
		echo "unknown mode: $mode" >&2
		exit 2
		;;
esac
