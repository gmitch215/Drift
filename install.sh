#!/bin/sh
set -eu

REPO_URL=${DRIFT_INSTALL_REPO_URL:-https://github.com/gmitch215/Drift}

usage() {
    cat <<'EOF'
Install the drift command line tool. When no release exists yet, it builds drift from source.

usage: install.sh [--user | --global] [--dir PATH] [--no-modify-path] [--dry-run]
                  [--from-source] [--ref REF]

  --user             install for the current user (the default)
  --global           install for every user; never uses sudo
  --dir PATH         absolute directory to install into
  --no-modify-path   leave shell profiles alone
  --dry-run          print every change without making any
  --from-source      build drift from source instead of downloading a release
  --ref REF          branch, tag or commit to build; implies --from-source (default: master)
  -h, --help         print this help

The flags are handed to the downloaded executable's `drift install`.

environment:
  DRIFT_INSTALL_BASE_URL   release download URL, laid out as BASE/v<version>/<asset>
                           (default: the GitHub releases of the repository)
  DRIFT_VERSION            version to install, for example 1.0.0 (default: the latest release;
                           required when DRIFT_INSTALL_BASE_URL is set)
  DRIFT_NO_MODIFY_PATH=1   same as --no-modify-path
  DRIFT_FROM_SOURCE=1      same as --from-source
  DRIFT_REF=REF            same as --ref
  DRIFT_SOURCE_URL         git URL or local path to clone when building from source
                           (default: the GitHub repository)
  DRIFT_INSTALL_REPO_URL   repository URL for releases/latest and source archives
                           (default: the GitHub repository)
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
keep=0
from_source=0
ref=${DRIFT_REF:-}
if [ "${DRIFT_FROM_SOURCE:-}" = 1 ]; then
    from_source=1
fi
while [ $# -gt 0 ]; do
    case "$1" in
        --user) user=1 ;;
        --global) global=1 ;;
        --no-modify-path) no_modify_path=1 ;;
        --dry-run) dry_run=1 ;;
        --from-source) from_source=1 ;;
        --ref)
            [ $# -ge 2 ] || {
                say "error: --ref needs a value"
                exit 2
            }
            ref=$2
            shift
            ;;
        --ref=*) ref=${1#--ref=} ;;
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
if [ -n "$ref" ]; then
    from_source=1
    case "$ref" in
        -* | *[!A-Za-z0-9._/-]*) die "--ref takes a branch, tag or commit name, not $ref" ;;
    esac
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
        url=$(curl -fsSL -o /dev/null -w '%{url_effective}' "$REPO_URL/releases/latest" 2> /dev/null) || return 1
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

new_tmp() {
    tmp=$(mktemp -d "${TMPDIR:-/tmp}/drift-install.XXXXXX") || die "cannot create a temporary directory"
    trap 'if [ "$keep" = 0 ]; then rm -rf "$tmp"; fi' EXIT
    trap 'exit 129' HUP
    trap 'exit 130' INT
    trap 'exit 143' TERM
}

handover() {
    bin=$1
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
}

is_commit() {
    case "$1" in
        *[!0-9a-f]* | '') return 1 ;;
    esac
    [ "${#1}" -ge 7 ] && [ "${#1}" -le 40 ]
}

fetch_source() {
    if [ "$has_git" = 0 ]; then
        fetch "$REPO_URL/archive/$ref.tar.gz" "$tmp/source.tar.gz" || die "cannot fetch $ref from $REPO_URL"
        mkdir "$src"
        tar -xzf "$tmp/source.tar.gz" -C "$src" --strip-components 1 || die "cannot fetch $ref from $REPO_URL"
        return
    fi
    # a hex name that is not a branch or tag is a commit
    if [ -n "$(git ls-remote -- "$source_url" "$ref" 2> /dev/null)" ] || ! is_commit "$ref"; then
        git clone -q --depth 1 --branch "$ref" -- "$source_url" "$src" || die "cannot fetch $ref from $source_url"
    else
        fetch_commit || die "cannot fetch $ref from $source_url"
    fi
}

fetch_commit() {
    if [ "${#ref}" -eq 40 ]; then
        git init -q "$src" && git -C "$src" fetch -q --depth 1 -- "$source_url" "$ref" && git -C "$src" checkout -q FETCH_HEAD
    else
        git clone -q --no-checkout -- "$source_url" "$src" && git -C "$src" checkout -q "$ref"
    fi
}

build_from_source() {
    ref=${ref:-master}
    source_url=${DRIFT_SOURCE_URL:-$REPO_URL.git}

    java_bin=
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        java_bin=$JAVA_HOME/bin/java
    elif command -v java > /dev/null 2>&1; then
        java_bin=java
    fi
    [ -n "$java_bin" ] || die "building from source needs a JDK 17 or newer on PATH or in JAVA_HOME"
    jver=$("$java_bin" -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -n 1)
    jmajor=${jver%%.*}
    if [ "$jmajor" = 1 ]; then
        jmajor=${jver#1.}
        jmajor=${jmajor%%.*}
    fi
    case "$jmajor" in
        '' | *[!0-9]*) die "building from source needs a JDK 17 or newer; cannot read the version of $java_bin" ;;
    esac
    [ "$jmajor" -ge 17 ] || die "building from source needs a JDK 17 or newer; $java_bin is Java $jver"

    has_git=0
    if command -v git > /dev/null 2>&1; then
        has_git=1
    elif ! command -v tar > /dev/null 2>&1; then
        die "building from source needs git, or tar to unpack the source archive"
    fi

    new_tmp
    src="$tmp/source"
    fetch_source

    case "$os-$arch" in
        macos-arm64) target=MacosArm64 lower=macosArm64 ;;
        linux-x64) target=LinuxX64 lower=linuxX64 ;;
        linux-arm64) target=LinuxArm64 lower=linuxArm64 ;;
    esac
    say "building drift (several minutes the first time)"
    if ! (cd "$src" && ./gradlew --console=plain ":cli:linkReleaseExecutable$target" > "$tmp/build.log" 2>&1); then
        keep=1
        tail -n 40 "$tmp/build.log" >&2
        say "error: the build failed; the full log is $tmp/build.log and $tmp is kept"
        say "check the JDK version, free disk space and the first error above"
        exit 1
    fi

    built="$src/cli/build/bin/$lower/releaseExecutable/drift.kexe"
    [ -f "$built" ] || die "the build did not produce $built"
    mkdir "$tmp/extract"
    cp "$built" "$tmp/extract/drift"
    chmod 755 "$tmp/extract/drift"
    "$tmp/extract/drift" --version > /dev/null 2>&1 || die "the built executable does not run on this system"
    if [ "$has_git" = 1 ]; then
        say "built drift from $ref ($(git -C "$src" rev-parse --short HEAD))"
    else
        say "built drift from $ref"
    fi
    handover "$tmp/extract/drift"
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
if [ "$from_source" = 0 ] && [ -z "$version" ]; then
    if tag=$(latest_tag); then
        version=${tag#v}
    else
        say "no release of drift exists yet, building from source (needs git or curl, a JDK 17 or newer, and several minutes; pass --ref to choose what to build)"
        from_source=1
    fi
fi

if [ "$from_source" = 1 ]; then
    build_from_source
fi

asset="drift-$version-$os-$arch.tar.gz"
url="${base%/}/v$version/$asset"

new_tmp

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

handover "$bin"
