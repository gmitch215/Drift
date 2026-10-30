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

case "$os-$arch" in
    macos-arm64) gradle_target=MacosArm64 ;;
    linux-x64) gradle_target=LinuxX64 ;;
    linux-arm64) gradle_target=LinuxArm64 ;;
    *) gradle_target=none ;;
esac
case "$exe" in
    /*) exe_abs=$exe ;;
    *) exe_abs="$(pwd)/$exe" ;;
esac

rm -rf "${work:?}"/drift-install.* "${work:?}/source" "${work:?}/archive-site" "${work:?}/gradle"
rm -rf "${work:?}/tools" "${work:?}/gitbin" "${work:?}/jdk21" "${work:?}/jdk11" "${work:?}/jdk8"
src="$work/source"
sgit() {
    git -C "$src" -c user.name=drift -c user.email=drift@example.invalid -c commit.gpgsign=false "$@"
}

git init -q -b master "$src"
cat > "$src/gradlew" <<EOF
#!/bin/sh
printf '%s\n' "\$*" > "$work/gradle.args"
git rev-parse HEAD > "$work/gradle.sha" 2> /dev/null
pwd > "$work/gradle.dir"
case "\$*" in
    *linkReleaseExecutableMacosArm64*) lower=macosArm64 ;;
    *linkReleaseExecutableLinuxX64*) lower=linuxX64 ;;
    *linkReleaseExecutableLinuxArm64*) lower=linuxArm64 ;;
    *) exit 2 ;;
esac
if [ -f "$work/gradle.fail" ]; then
    i=1
    while [ "\$i" -le 50 ]; do
        echo "log line \$i"
        i=\$((i + 1))
    done
    exit 1
fi
mkdir -p "cli/build/bin/\$lower/releaseExecutable"
cp "$exe_abs" "cli/build/bin/\$lower/releaseExecutable/drift.kexe"
EOF
chmod +x "$src/gradlew"
printf 'drift\n' > "$src/README.md"
sgit add -A
sgit commit -q -m one
sgit tag v9.9.9
sgit checkout -q -b exp
printf 'two\n' > "$src/exp.txt"
sgit add -A
sgit commit -q -m two
sgit checkout -q master
printf 'three\n' > "$src/master.txt"
sgit add -A
sgit commit -q -m three
c1=$(sgit rev-parse 'v9.9.9^{commit}')
c2=$(sgit rev-parse exp)
short=$(sgit rev-parse --short=7 exp)

mkdir -p "$work/archive-site/archive" "$work/jdk21" "$work/jdk11" "$work/jdk8" "$work/tools" "$work/gitbin"
sgit archive --format=tar.gz --prefix=Drift-master/ -o "$work/archive-site/archive/master.tar.gz" master
cat > "$work/jdk21/java" <<'EOF'
#!/bin/sh
echo 'openjdk version "21.0.8" 2025-07-15' >&2
EOF
cat > "$work/jdk11/java" <<'EOF'
#!/bin/sh
echo 'openjdk version "11.0.2" 2019-01-15' >&2
EOF
cat > "$work/jdk8/java" <<'EOF'
#!/bin/sh
echo 'java version "1.8.0_292"' >&2
EOF
chmod +x "$work/jdk21/java" "$work/jdk11/java" "$work/jdk8/java"
for name in env uname sysctl curl tar shasum sha256sum grep sed tr head tail cat mktemp rm mkdir cp chmod id; do
    path=$(command -v "$name" || true)
    if [ -n "$path" ]; then ln -sf "$path" "$work/tools/$name"; fi
done
ln -sf "$(command -v git)" "$work/gitbin/git"

build_run() {
    env -i HOME="$home" PATH="${t_path:-$work/jdk21:$work/tools:$work/gitbin}" SHELL=/bin/zsh \
        TERM=dumb TMPDIR="$work" DRIFT_SOURCE_URL="file://$src" \
        DRIFT_INSTALL_REPO_URL="${t_repo:-file://$work/no-release}" \
        DRIFT_INSTALL_BASE_URL="${t_base:-}" DRIFT_VERSION="${t_pin:-}" \
        DRIFT_FROM_SOURCE="${t_from:-}" DRIFT_REF="${t_ref:-}" \
        /bin/sh "$root/install.sh" "$@"
}

source_build() {
    rm -f "$work/gradle.args" "$work/gradle.sha" "$work/gradle.dir"
    build_run "$@" > "$work/src.out" 2> "$work/src.err"
}

built() {
    [ -f "$work/gradle.args" ]
}

sha_is() {
    [ "$(cat "$work/gradle.sha")" = "$1" ]
}

shown() {
    grep -qx "$1" "$work/src.err"
}

hidden() {
    ! grep -qx "$1" "$work/src.err"
}

restore() {
    installed uninstall && unchanged "$work/before.txt"
}

from_env() {
    t_from=1 source_build
}

ref_env() {
    t_ref=exp source_build
}

from_release() {
    t_base="file://$work/release" t_pin="$version" source_build --from-source
}

not_downloaded() {
    ! grep -q downloading "$work/src.err"
}

pinned_missing() {
    t_pin=9.9.8 source_build
}

no_java() {
    t_path="$work/tools:$work/gitbin" source_build
}

java_11() {
    t_path="$work/jdk11:$work/tools:$work/gitbin" source_build
}

java_8() {
    t_path="$work/jdk8:$work/tools:$work/gitbin" source_build
}

failing_build() {
    touch "$work/gradle.fail"
    source_build
    code=$?
    rm -f "$work/gradle.fail"
    return "$code"
}

kept() {
    test -d "$(dirname "$(cat "$work/gradle.dir")")"
}

unknown_ref() {
    source_build --ref no-such-ref
}

dry_source() {
    source_build --dry-run
}

no_git() {
    t_path="$work/jdk21:$work/tools" t_repo="file://$work/archive-site" source_build
}

check "no release and no ref builds from source" source_build
check "the fallback says there is no release" grep -q 'no release of drift exists yet' "$work/src.err"
check "the fallback builds the target for this machine" \
    grep -q "linkReleaseExecutable$gradle_target" "$work/gradle.args"
check "the default ref is master" sha_is "$(sgit rev-parse master)"
check "the fallback installs the built executable" cmp -s "$exe" "$home/.local/bin/drift"
check "the fallback writes the receipt" test -f "$home/.config/drift/install-receipt.json"
check "uninstall after the fallback restores every byte" restore

check "--from-source builds when a release exists" from_release
check "--from-source does not download the release" not_downloaded
check "--from-source builds the target for this machine" \
    grep -q "linkReleaseExecutable$gradle_target" "$work/gradle.args"
check "uninstall after --from-source restores every byte" restore

check "--ref exp builds the branch" source_build --ref exp
check "--ref exp checks out exp" sha_is "$(sgit rev-parse exp)"
check "uninstall after --ref exp restores every byte" restore

check "--ref v9.9.9 builds the tag" source_build --ref v9.9.9
check "--ref v9.9.9 checks out the tag" sha_is "$c1"
check "uninstall after --ref v9.9.9 restores every byte" restore

check "--ref with a full commit hash builds that commit" source_build --ref "$c1"
check "a full commit hash checks out that commit" sha_is "$c1"
check "uninstall after a full commit hash restores every byte" restore

check "--ref with a short commit hash builds that commit" source_build --ref "$short"
check "a short commit hash checks out that commit" sha_is "$c2"
check "uninstall after a short commit hash restores every byte" restore

check "DRIFT_FROM_SOURCE=1 builds from source" from_env
check "DRIFT_FROM_SOURCE=1 checks out master" sha_is "$(sgit rev-parse master)"
check "uninstall after DRIFT_FROM_SOURCE restores every byte" restore

check "DRIFT_REF=exp builds from source" ref_env
check "DRIFT_REF=exp checks out exp" sha_is "$c2"
check "uninstall after DRIFT_REF restores every byte" restore

check "a pinned version with no asset exits 1" code_is 1 pinned_missing
check "a pinned version names the failed download" grep -q 'cannot download' "$work/src.err"
check "a pinned version never builds" test ! -f "$work/gradle.args"
check "a pinned version changed nothing" unchanged "$work/before.txt"

check "no JDK exits 1" code_is 1 no_java
check "no JDK names the JDK" grep -q 'JDK 17 or newer' "$work/src.err"
check "no JDK never builds" test ! -f "$work/gradle.args"
check "no JDK changed nothing" unchanged "$work/before.txt"

check "Java 11 exits 1" code_is 1 java_11
check "Java 11 is named as too old" grep -q 'is Java 11.0.2' "$work/src.err"
check "Java 11 changed nothing" unchanged "$work/before.txt"

check "Java 1.8 exits 1" code_is 1 java_8
check "Java 1.8 is named as too old" grep -q 'is Java 1.8.0_292' "$work/src.err"
check "Java 1.8 changed nothing" unchanged "$work/before.txt"

check "a failed build exits 1" code_is 1 failing_build
check "a failed build shows the last 40 lines of the log" shown 'log line 50'
check "a failed build hides the lines before the last 40" hidden 'log line 10'
check "a failed build names the kept directory" grep -q "$work/drift-install\." "$work/src.err"
check "a failed build keeps the temporary directory" kept
check "a failed build changed nothing" unchanged "$work/before.txt"

check "an unknown ref exits 1" code_is 1 unknown_ref
check "an unknown ref says it cannot fetch" grep -q 'cannot fetch no-such-ref' "$work/src.err"
check "an unknown ref never builds" test ! -f "$work/gradle.args"
check "an unknown ref changed nothing" unchanged "$work/before.txt"

check "--dry-run builds from source" dry_source
check "--dry-run changed nothing" unchanged "$work/before.txt"

check "without git the archive is built" no_git
check "without git the build names no SHA" grep -q 'built drift from master$' "$work/src.err"
check "uninstall after the archive build restores every byte" restore

check "--ref with no value exits 2" code_is 2 build_run --ref
check "a ref that starts with a dash exits 1" code_is 1 build_run --ref=-x
check "refused refs changed nothing" unchanged "$work/before.txt"

exit "$failed"
