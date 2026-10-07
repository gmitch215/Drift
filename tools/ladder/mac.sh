#!/usr/bin/env sh
set -u
version="$1"
target="$2"
homes="$HOME/Library/Java/JavaVirtualMachines"
jdk21="$homes/temurin-21.0.8/Contents/Home"
rig="${LADDER_RIG:?set LADDER_RIG to the mac rig directory}"
work="$rig/ladder/work/$version-$target"
script="$(dirname "$0")/ladder.sh"
mkdir -p "$work"
chmod +x "$rig/cache/ladder/tc/kotlinc-$version/bin/"* 2> /dev/null
run() {
	JAVA_HOME="$1" PATH="$1/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin" LADDER_RIG="$rig" \
		sh "$script" "$2" "$version" "$target"
}
case "$target" in
macos) fallback="/Library/Internet Plug-Ins/JavaAppletPlugin.plugin/Contents/Home" ;;
*) fallback="$homes/temurin-11.0.28/Contents/Home" ;;
esac
for jdk in "$jdk21" "$fallback"; do
	if run "$jdk" compile; then
		echo "$jdk" > "$work/jdk.txt"
		run "$jdk21" run && run "$jdk21" assemble
		exit $?
	fi
	echo "compile failed on $jdk"
done
exit 1
