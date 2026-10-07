#!/bin/sh
set -eu

REPO_URL="https://github.com/gmitch215/Drift"

usage() {
	cat <<'EOF'
Install the drift command line tool.

usage: install.sh [--user | --global] [--dir PATH] [--no-modify-path] [--dry-run]

  --user             install for the current user (the default)
  --global           install for every user; never uses sudo
  --dir PATH         absolute directory to install into
  --no-modify-path   leave shell profiles alone
  --dry-run          print every change without making any
  -h, --help         print this help

The flags are handed to the downloaded executable's `drift install`.

environment:
  DRIFT_INSTALL_BASE_URL   release download URL, laid out as BASE/v<version>/<asset>
                           (default: the GitHub releases of the repository)
  DRIFT_VERSION            version to install, for example 1.0.0 (default: the latest release;
                           required when DRIFT_INSTALL_BASE_URL is set)
  DRIFT_NO_MODIFY_PATH=1   same as --no-modify-path
EOF
}

say() {
	printf 'drift-install: %s\n' "$*" >&2
}

die() {
	say "error: $*"
	exit 1
}

user=0
global=0
no_modify_path=0
dry_run=0
dir=
while [ $# -gt 0 ]; do
	case "$1" in
		--user) user=1 ;;
		--global) global=1 ;;
		--no-modify-path) no_modify_path=1 ;;
		--dry-run) dry_run=1 ;;
		--dir)
			[ $# -ge 2 ] || {
				say "error: --dir needs a value"
				exit 2
			}
			dir=$2
			shift
			;;
		--dir=*) dir=${1#--dir=} ;;
		-h | --help)
			usage
			exit 0
			;;
		*)
			say "error: unknown option: $1"
			usage >&2
			exit 2
			;;
	esac
	shift
done
if [ "$user" = 1 ] && [ "$global" = 1 ]; then
	say "error: --user and --global cannot be combined"
	exit 2
fi

case "$(uname -s)" in
	Darwin) os=macos ;;
	Linux) os=linux ;;
	MINGW* | MSYS* | CYGWIN*) die "this is Windows; use install.ps1 instead" ;;
	*) die "unsupported operating system: $(uname -s)" ;;
esac

case "$(uname -m)" in
	arm64 | aarch64) arch=arm64 ;;
	x86_64 | amd64) arch=x64 ;;
	*) die "unsupported architecture: $(uname -m)" ;;
esac

if [ "$os" = macos ] && [ "$arch" = x64 ]; then
	if [ "$(sysctl -n hw.optional.arm64 2> /dev/null || true)" = 1 ]; then
		arch=arm64
	fi
fi

if [ "$os" = linux ]; then
	if command -v ldd > /dev/null 2>&1; then
		if ldd --version 2>&1 | grep -qi musl; then musl=1; else musl=0; fi
	elif ls /lib/ld-musl-* > /dev/null 2>&1; then
		musl=1
	else
		musl=0
	fi
	if [ "$musl" = 1 ]; then
		die "the Linux executable is built for glibc and this system uses musl; no asset is available"
	fi
fi

case "$os-$arch" in
	macos-arm64 | linux-x64 | linux-arm64) ;;
	*) die "no release asset is built for $os on $arch" ;;
esac

if command -v curl > /dev/null 2>&1; then
	downloader=curl
elif command -v wget > /dev/null 2>&1; then
	downloader=wget
else
	die "curl or wget is required"
fi

if command -v sha256sum > /dev/null 2>&1; then
	hasher=sha256sum
elif command -v shasum > /dev/null 2>&1; then
	hasher=shasum
else
	die "sha256sum or shasum is required"
fi

sha256_of() {
	if [ "$hasher" = sha256sum ]; then
		sha256sum "$1"
	else
		shasum -a 256 "$1"
	fi
}

fetch() {
	if [ "$downloader" = curl ]; then
		curl -fsSL -o "$2" "$1"
	else
		wget -q -O "$2" "$1"
	fi
}

latest_tag() {
	if [ "$downloader" = curl ]; then
		url=$(curl -fsSL -o /dev/null -w '%{url_effective}' "$REPO_URL/releases/latest") || return 1
	else
		url=$(wget -q -S -O /dev/null --max-redirect=0 "$REPO_URL/releases/latest" 2>&1 |
			sed -n 's/^ *[Ll]ocation: *//p' | tr -d '\r' | head -n 1) || true
	fi
	tag=${url##*/}
	case "$tag" in
		v[0-9]*) printf '%s\n' "$tag" ;;
		*) return 1 ;;
	esac
}

base=${DRIFT_INSTALL_BASE_URL:-}
version=${DRIFT_VERSION:-}
if [ -n "$base" ] && [ -z "$version" ]; then
	die "set DRIFT_VERSION when DRIFT_INSTALL_BASE_URL is set"
fi
if [ -z "$base" ]; then
	base="$REPO_URL/releases/download"
fi
version=${version#v}
if [ -z "$version" ]; then
	tag=$(latest_tag) || die "cannot find the latest release of $REPO_URL; set DRIFT_VERSION"
	version=${tag#v}
fi

asset="drift-$version-$os-$arch.tar.gz"
url="${base%/}/v$version/$asset"

tmp=$(mktemp -d "${TMPDIR:-/tmp}/drift-install.XXXXXX") || die "cannot create a temporary directory"
trap 'rm -rf "$tmp"' EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

say "downloading $asset"
fetch "$url" "$tmp/$asset" || die "cannot download $url"
fetch "$url.sha256" "$tmp/$asset.sha256" || die "cannot download $url.sha256"

read -r expected _ < "$tmp/$asset.sha256" || true
case "$expected" in
	*[!0-9A-Fa-f]* | "") die "$asset.sha256 does not start with a sha256 digest" ;;
esac
if [ "${#expected}" -ne 64 ]; then
	die "$asset.sha256 does not start with a sha256 digest"
fi
actual=$(sha256_of "$tmp/$asset")
actual=${actual%% *}
expected=$(printf '%s' "$expected" | tr 'A-F' 'a-f')
if [ "$actual" != "$expected" ]; then
	die "checksum mismatch for $asset (expected $expected, got $actual); nothing was installed"
fi
say "sha256 verified"

mkdir "$tmp/extract"
tar -xzf "$tmp/$asset" -C "$tmp/extract" || die "cannot extract $asset"
bin=
for candidate in "$tmp/extract/drift" "$tmp/extract"/*/drift; do
	if [ -f "$candidate" ]; then
		bin=$candidate
		break
	fi
done
[ -n "$bin" ] || die "$asset does not contain a drift executable"
chmod 755 "$bin"
"$bin" --version > /dev/null 2>&1 || die "the downloaded executable does not run on this system"

set --
if [ "$user" = 1 ]; then set -- "$@" --user; fi
if [ "$global" = 1 ]; then set -- "$@" --global; fi
if [ "$no_modify_path" = 1 ]; then set -- "$@" --no-modify-path; fi
if [ "$dry_run" = 1 ]; then set -- "$@" --dry-run; fi
if [ -n "$dir" ]; then set -- "$@" --dir "$dir"; fi

status=0
"$bin" install "$@" || status=$?
if [ "$status" -ne 0 ] && [ "$global" = 1 ] && [ "$(id -u)" != 0 ]; then
	say "a global install needs write access to the install directory; run this installer again as root"
fi
exit "$status"
