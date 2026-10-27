#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
gradle=${GRADLE:-$repo/gradlew}
all_targets=jvm,linuxX64,js,wasmJs
targets=$all_targets
label=${RUN_LABEL:-$(date -u +%Y%m%dT%H%M%SZ)}
out=${OUT_DIR:-$here/../results/atlas-libs/runs/$label}
check=0
messages=
extra=()

usage() {
    echo "usage: run.sh [--check] [--targets jvm,linuxX64,js,wasmJs] [--out DIR] [--messages] [-- gradle args]"
    echo "env: GRADLE (gradle command), RUN_LABEL, OUT_DIR"
    echo "on paisley-park: experiments/run.sh atlas-libs --paisley"
}

while [ $# -gt 0 ]; do
    case $1 in
    --check) check=1 ;;
    --targets) targets=$2; shift ;;
    --out) out=$2; shift ;;
    --messages) messages=--messages ;;
    --) shift; extra=("$@"); break ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; exit 64 ;;
    esac
    shift
done

prop() { sed -n "s/^$1=//p" "$here/gradle.properties"; }

check_project() {
    local f ids total dup
    for f in settings.gradle.kts build.gradle.kts gradle.properties compare.mjs; do
        [ -f "$here/$f" ] || { echo "missing $f" >&2; exit 1; }
    done
    for p in kotlinVersion jsJodaTimezoneVersion datetimeVersion serializationVersion ioVersion coroutinesVersion; do
        [ -n "$(prop $p)" ] || { echo "gradle.properties has no $p" >&2; exit 1; }
    done
    ids=$(grep -rhoE 'Probe\("[a-z0-9.-]+"' "$here/src" | sed 's/Probe("//; s/"//')
    total=$(echo "$ids" | wc -l | tr -d ' ')
    dup=$(echo "$ids" | sort | uniq -d)
    [ -z "$dup" ] || { echo "duplicate probe ids: $dup" >&2; exit 1; }
    [ "$total" -ge 15 ] && [ "$total" -le 25 ] || { echo "$total probes, expected 15 to 25" >&2; exit 1; }
    echo "kotlin $(prop kotlinVersion), datetime $(prop datetimeVersion), serialization $(prop serializationVersion), io $(prop ioVersion), coroutines $(prop coroutinesVersion)"
    echo "@js-joda/timezone $(prop jsJodaTimezoneVersion)"
    echo "$total probes:"
    echo "$ids" | sed 's/^/  /'
    echo "targets: $targets"
    echo "would write: $out/<target>.txt and $out/compare.md"
}

if [ $check = 1 ]; then
    check_project
    exit 0
fi

check_project > /dev/null
mkdir -p "$out"
g() { "$gradle" -p "$here" -q --console=plain "$@" ${extra[@]+"${extra[@]}"}; }

header() {
    echo "# kotlin.version = $(prop kotlinVersion)"
    echo "# kotlin.target = $1"
    echo "# lib.kotlinx-datetime = $(prop datetimeVersion)"
    echo "# lib.kotlinx-serialization-json = $(prop serializationVersion)"
    echo "# lib.kotlinx-io-core = $(prop ioVersion)"
    echo "# lib.kotlinx-coroutines-core = $(prop coroutinesVersion)"
    echo "# lib.js-joda-timezone = $(prop jsJodaTimezoneVersion) (js, wasmJs)"
}

transcript() {
    awk '/^@@BEGIN$/ {on=1; next} /^@@END$/ {on=0} on' "$1"
}

record() {
    local target=$1 raw="$out/$1.raw"
    case $target in
    jvm) g jvmRun > "$raw" ;;
    js) g kotlinUpgradeYarnLock jsNodeProductionRun > "$raw" ;;
    wasmJs) g kotlinWasmUpgradeYarnLock wasmJsNodeProductionRun > "$raw" ;;
    linuxX64)
        g linkReleaseExecutableLinuxX64
        if [ "$(uname -s)-$(uname -m)" != Linux-x86_64 ]; then
            echo "linuxX64: built, not run (needs an x86-64 Linux host)" >&2
            return 3
        fi
        "$here"/build/bin/linuxX64/releaseExecutable/atlas-libs.kexe > "$raw"
        ;;
    *) echo "unknown target $target" >&2; return 64 ;;
    esac
    { header "$target"; transcript "$raw"; } > "$out/$target.tmp"
    grep -q "^@@ " "$out/$target.tmp" || { echo "$target printed no transcript" >&2; return 1; }
    mv "$out/$target.tmp" "$out/$target.txt"
    echo "$target: $(grep -c '^@@ ' "$out/$target.txt") probes, $(grep -vc '^[#@]' "$out/$target.txt") lines"
}

status=0
for t in ${targets//,/ }; do
    record "$t" || { rc=$?; [ $rc = 3 ] || status=2; echo "target $t failed with $rc" >&2; }
done

diff_rc=0
node "$here/compare.mjs" "$out" $messages > "$out/compare.md" || diff_rc=$?
cat "$out/compare.md"
echo "transcripts and table in $out"
[ $status = 0 ] || exit $status
exit $diff_rc
