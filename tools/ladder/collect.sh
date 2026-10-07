#!/usr/bin/env sh
set -u
work="$1"
out="$2"
for dir in "$work"/*-*/; do
	name="$(basename "$dir")"
	version="${name%%-*}"
	target="${name#*-}"
	case "$target" in
	jvm | linux | macos | js-legacy | js-ir) ;;
	*) continue ;;
	esac
	[ -s "$dir/run.out" ] && [ -s "$dir/transcript.txt" ] || continue
	mkdir -p "$out/$version"
	cp "$dir/transcript.txt" "$out/$version/$target.txt"
done
