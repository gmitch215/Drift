#!/usr/bin/env sh
set -eu
cache="${LADDER_CACHE:-$HOME/drift-rig/cache/ladder}"
base=https://github.com/JetBrains/kotlin/releases/download
mkdir -p "$cache/dl" "$cache/tc"
for v in "$@"; do
	zip="$cache/dl/kotlin-compiler-$v.zip"
	if [ ! -d "$cache/tc/kotlinc-$v" ]; then
		[ -f "$zip" ] || curl -fsSL --limit-rate 15m -o "$zip" "$base/v$v/kotlin-compiler-$v.zip"
		unzip -q "$zip" -d "$cache/tc/unzip-$v"
		mv "$cache/tc/unzip-$v/kotlinc" "$cache/tc/kotlinc-$v"
		echo "compiler $v ok"
	fi
	[ "${LADDER_NATIVE:-1}" = 1 ] || continue
	major="${v%%.*}"
	minor="${v#*.}"
	minor="${minor%%.*}"
	os="${LADDER_OS:-linux-x86_64}"
	if [ "$major" = 2 ]; then
		name="kotlin-native-prebuilt-$os-$v"
	elif [ "$minor" -ge 5 ]; then
		name="kotlin-native-$os-$v"
	elif [ "$os" = linux-x86_64 ]; then
		name="kotlin-native-linux-$v"
	else
		continue
	fi
	if [ ! -d "$cache/tc/$name" ]; then
		[ -f "$cache/dl/$name.tar.gz" ] || curl -fsSL --limit-rate 15m -o "$cache/dl/$name.tar.gz" "$base/v$v/$name.tar.gz"
		tar -xzf "$cache/dl/$name.tar.gz" -C "$cache/tc"
		echo "native $v ok"
	fi
done
