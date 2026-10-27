#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
gradle=${GRADLE:-$repo/gradlew}
all_targets=jvm,linuxX64,js,wasmJs
targets=$all_targets
label=${RUN_LABEL:-$(date -u +%Y%m%dT%H%M%SZ)}
out=${OUT_DIR:-$here/../results/box-tests/runs/$label}
check=0
offline=

usage() {
    echo "usage: run.sh [--check] [--targets jvm,linuxX64,js,wasmJs] [--out DIR] [--offline]"
    echo "env: GRADLE (gradle command), RUN_LABEL, OUT_DIR"
    echo "on paisley-park: experiments/run.sh box-tests --paisley"
}

invocation="$0 $*"
while [ $# -gt 0 ]; do
    case $1 in
    --check) check=1 ;;
    --targets) targets=$2; shift ;;
    --out) out=$2; shift ;;
    --offline) offline=--offline ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; exit 64 ;;
    esac
    shift
done

pkg_of() {
    local n=$1
    n=${n%.kt}
    echo "${n//-/_}"
}

test_files() {
    ls "$here"/tests/*.kt | sort
}

check_project() {
    local f n
    for f in settings.gradle.kts build.gradle.kts gradle.properties; do
        [ -f "$here/project/$f" ] || { echo "missing project/$f" >&2; exit 1; }
    done
    for f in $(test_files); do
        grep -q '^fun box(): String {' "$f" || { echo "$(basename "$f") has no box(): String" >&2; exit 1; }
        n=$(basename "$f")
        echo "test $n (package box.$(pkg_of "$n"))"
    done
    echo "targets: $targets"
    echo "would write: $out/<target>.txt and $out/table.md"
}

if [ $check = 1 ]; then
    check_project
    exit 0
fi

[ ! -e "$out" ] || { echo "$out exists; pick a new --out" >&2; exit 3; }
mkdir -p "$out"
work=$out/work
mkdir -p "$work"
cp -R "$here/project/." "$work/"
src=$work/src/commonMain/kotlin
mkdir -p "$src"

imports=
main_body=
index=0
for f in $(test_files); do
    n=$(basename "$f")
    pkg=$(pkg_of "$n")
    mkdir -p "$src/box/$pkg"
    { printf 'package box.%s\n\n' "$pkg"; cat "$f"; } > "$src/box/$pkg/Test.kt"
    imports+="import box.$pkg.box as t$index"$'\n'
    main_body+="        \"${n%.kt}\" to { t$index() },"$'\n'
    index=$((index + 1))
done

{
    printf '%s\n' "$imports"
    echo "fun main() {"
    echo "    val tests = listOf<Pair<String, () -> String>>("
    printf '%s' "$main_body"
    echo "    )"
    echo "    for ((name, run) in tests) {"
    echo "        val result = try { run() } catch (e: Throwable) { \"Exception: \$e\" }"
    echo "        println(\"box \$name: \$result\")"
    echo "    }"
    echo "}"
} > "$src/Main.kt"

g() { "$gradle" -p "$work" -q --console=plain --max-workers=2 $offline "$@"; }

record() {
    local t=$1 raw=$out/$1.raw
    case $t in
    jvm) g jvmRun > "$raw" ;;
    js) g kotlinUpgradeYarnLock jsNodeProductionRun > "$raw" ;;
    wasmJs) g kotlinWasmUpgradeYarnLock wasmJsNodeProductionRun > "$raw" ;;
    linuxX64)
        g linkReleaseExecutableLinuxX64
        if [ "$(uname -s)-$(uname -m)" != Linux-x86_64 ]; then
            echo "linuxX64: built, not run (needs an x86-64 Linux host)" >&2
            return 3
        fi
        "$work/build/bin/linuxX64/releaseExecutable/box-tests.kexe" > "$raw"
        ;;
    *) echo "unknown target $t" >&2; return 64 ;;
    esac
    grep '^box ' "$raw" > "$out/$t.txt" || { echo "$t printed no box lines" >&2; return 1; }
}

cell() {
    local f=$out/$1.txt line
    [ -f "$f" ] || { echo "not run"; return; }
    line=$(grep -m1 "^box $2: " "$f" || true)
    [ -n "$line" ] || { echo "no output"; return; }
    line=${line#box "$2": }
    echo "${line//|/\\|}"
}

report() {
    local t n header sep
    header="| test |"
    sep="| --- |"
    for t in ${targets//,/ }; do
        header+=" $t |"
        sep+=" --- |"
    done
    echo "$header"
    echo "$sep"
    for f in $(test_files); do
        n=$(basename "$f" .kt)
        printf '| %s |' "$n"
        for t in ${targets//,/ }; do
            printf ' %s |' "$(cell "$t" "$n")"
        done
        echo
    done
}

status=0
for t in ${targets//,/ }; do
    record "$t" || { rc=$?; [ $rc = 3 ] || status=2; echo "target $t failed with $rc" >&2; }
done

{
    echo "kotlin $(sed -n 's/.*version "\(.*\)".*/\1/p' "$here/project/settings.gradle.kts")"
    echo "commit $(git -C "$repo" rev-parse --short HEAD 2>/dev/null || echo unknown)"
    echo "command $invocation"
    echo
    report
} | tee "$out/table.md"
echo "table in $out/table.md"
exit $status
