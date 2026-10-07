#!/usr/bin/env sh
# shellcheck disable=SC2329,SC2016
set -u
exe="${1:?usage: test-installers.sh <path to the drift executable> [work dir]}"
work="${2:-${TMPDIR:-/tmp}/drift-installers}"
root="$(cd "$(dirname "$0")/.." && pwd)"
failed=0

case "$(uname -s)" in
	Darwin) os=macos ;;
	*) os=linux ;;
esac
case "$(uname -m)" in
	arm64 | aarch64) arch=arm64 ;;
	*) arch=x64 ;;
esac
version="$("$exe" --version | sed 's/^drift version \([^ ]*\) .*/\1/')"
asset="drift-$version-$os-$arch.tar.gz"

sha256() {
	if command -v sha256sum > /dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi
}

check() {
	desc="$1"
	shift
	if "$@" > /dev/null 2>&1; then
		printf 'ok   %s\n' "$desc"
	else
		printf 'FAIL %s\n' "$desc"
		failed=1
	fi
}

snapshot() {
	(cd "$1" && find . | sort && find . -type f | sort | while read -r f; do sha256 "$f"; done)
}

unchanged() {
	snapshot "$home" | cmp -s - "$1"
}

code_is() {
	want="$1"
	shift
	"$@" > /dev/null 2>&1
	[ $? -eq "$want" ]
}

count_is() {
	[ "$(grep -c "$2" "$3")" = "$1" ]
}

mkdir -p "$work"
rm -rf "${work:?}/release" "${work:?}/bad" "${work:?}/home" "${work:?}/stage"
mkdir -p "$work/release/v$version" "$work/bad/v$version" "$work/stage" "$work/home"
cp "$exe" "$work/stage/drift"
chmod 755 "$work/stage/drift"
tar -czf "$work/release/v$version/$asset" -C "$work/stage" drift
(cd "$work/release/v$version" && sha256 "$asset" > "$asset.sha256")
cp "$work/release/v$version/$asset" "$work/bad/v$version/$asset"
cp "$work/release/v$version/$asset.sha256" "$work/bad/v$version/$asset.sha256"
printf 'x' >> "$work/bad/v$version/$asset"

home="$work/home"
printf 'export A=1\nalias ll="ls -l"' > "$home/.zshenv"
printf '# profile\n' > "$home/.profile"
snapshot "$home" > "$work/before.txt"

run() {
	env -i HOME="$home" PATH="/usr/bin:/bin" SHELL=/bin/zsh TERM=dumb \
		DRIFT_INSTALL_BASE_URL="file://${base:-$work/release}" DRIFT_VERSION="$version" \
		TMPDIR="$work" /bin/sh "$root/install.sh" "$@"
}

dry() {
	run --dry-run > "$work/dry.out" 2> "$work/dry.err"
}

check "dry run exits 0" dry
check "dry run changed nothing" unchanged "$work/before.txt"
check "dry run lists the profile edits" grep -q 'append to' "$work/dry.out"

install_all() {
	run > "$work/install.out" 2> "$work/install.err"
}

check "install exits 0" install_all
check "installed binary equals the executable" cmp -s "$exe" "$home/.local/bin/drift"
check "installed binary is executable" test -x "$home/.local/bin/drift"
check "profile has one marker block" count_is 1 '^# >>> drift >>>$' "$home/.profile"
check "zshenv has one marker block" count_is 1 '^# >>> drift >>>$' "$home/.zshenv"

sourced() {
	env -i HOME="$home" PATH="/usr/bin:/bin" /bin/sh -c "$1" > "$work/sourced.out" 2>&1
}

check "drift runs after sourcing the profile twice" sourced '. "$HOME/.profile"; . "$HOME/.profile"; drift --version'
check "the version line is printed" grep -q "^drift version $version" "$work/sourced.out"
check "sourcing twice adds the directory once" sourced '. "$HOME/.profile"; . "$HOME/.profile"; echo "$PATH" | tr ":" "\n" | grep -c "/.local/bin$" | grep -qx 1'

snapshot "$home" > "$work/installed.txt"
check "second run exits 0" install_all
check "second run changed nothing" unchanged "$work/installed.txt"

installed() {
	env -i HOME="$home" PATH="/usr/bin:/bin" "$home/.local/bin/drift" "$@" > "$work/drift.out" 2>&1
}

check "uninstall dry run exits 0" installed uninstall --dry-run
check "uninstall dry run changed nothing" unchanged "$work/installed.txt"
check "uninstall exits 0" installed uninstall
check "uninstall restored every byte" unchanged "$work/before.txt"

nothing_left() {
	[ -z "$(find "$home" -name '*drift*')" ]
}

check "nothing named drift is left in the home directory" nothing_left
fresh() {
	env -i HOME="$home" PATH="/usr/bin:/bin" "$exe" "$@" > "$work/drift.out" 2>&1
}

check "a second uninstall exits 0" fresh uninstall
check "a second uninstall says there is no receipt" grep -q 'nothing removed' "$work/drift.out"

bad_run() {
	base="$work/bad" run > "$work/bad.out" 2> "$work/bad.err"
}

check "a corrupted asset exits 1" code_is 1 bad_run
check "a corrupted asset is named as a checksum mismatch" grep -q 'checksum mismatch' "$work/bad.err"
check "a corrupted asset changed nothing" unchanged "$work/before.txt"

offline_run() {
	base="$work/nowhere" run > "$work/net.out" 2> "$work/net.err"
}

check "no network exits 1" code_is 1 offline_run
check "no network says it cannot download" grep -q 'cannot download' "$work/net.err"
check "no network changed nothing" unchanged "$work/before.txt"

no_path() {
	run --no-modify-path > "$work/nopath.out" 2> "$work/nopath.err"
}

check "install with --no-modify-path exits 0" no_path
check "no PATH edit is recorded" grep -q '"pathEdits":\[\]' "$home/.config/drift/install-receipt.json"
check "the profile was not touched" count_is 0 'drift' "$home/.profile"
check "the zshenv was not touched" count_is 0 'drift' "$home/.zshenv"
check "uninstall after --no-modify-path exits 0" installed uninstall
check "uninstall after --no-modify-path restored every byte" unchanged "$work/before.txt"

conflicting() {
	run --user --global > /dev/null 2>&1
}

check "conflicting flags exit 2" code_is 2 conflicting

exit "$failed"
