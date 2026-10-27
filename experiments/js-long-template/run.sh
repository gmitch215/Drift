#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
gradle=${GRADLE:-$repo/gradlew}
versions=1.8.0,1.8.22,1.9.0,1.9.25,2.0.0,2.0.21,2.1.0,2.1.21,2.2.0,2.2.21,2.3.0,2.3.21,2.4.0,2.4.21,2.5.0-Beta1,2.5.0-Beta2
out=${OUT_DIR:-$here/../results/js-long-template/runs/$(date -u +%Y%m%dT%H%M%SZ)}
check=0
report=0

usage() {
    echo "usage: run.sh [--check] [--versions v1,v2,...] [--out DIR] [--report]"
    echo "env: GRADLE (launcher used once per Gradle line to write a wrapper; default repo gradlew), OUT_DIR"
}

while [ $# -gt 0 ]; do
    case $1 in
    --check) check=1 ;;
    --versions) versions=$2; shift ;;
    --out) out=$2; shift ;;
    --report) report=1 ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; exit 64 ;;
    esac
    shift
done

expected=9007199254740993
expect_a="A $expected"
expect_d="D ok:$expected"
expect_g="G $expected"
expect_p="P ok:$expected"

validate() {
    echo "$1" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+(-(Beta|RC)[0-9]*)?$' || { echo "bad version: $1" >&2; exit 64; }
}

gradle_line() {
    case $1 in
    1.*) echo 8.5 ;;
    *) echo 8.14.3 ;;
    esac
}

js_block() {
    case $1 in
    1.*) echo 'js(IR)' ;;
    *) echo js ;;
    esac
}

render_settings() {
    sed -e "s/version \"2.4.20\"/version \"$1\"/" "$here/project/settings.gradle.kts"
}

render_build() {
    sed -e "s/^    js {/    $(js_block "$1") {/" "$here/project/build.gradle.kts"
}

check_project() {
    local f v
    for f in settings.gradle.kts build.gradle.kts gradle.properties src/jsMain/kotlin/Main.kt; do
        [ -f "$here/project/$f" ] || { echo "missing project/$f" >&2; exit 1; }
    done
    grep -q '^inline fun wrap' "$here/project/src/jsMain/kotlin/Main.kt" || { echo "Main.kt has no wrap" >&2; exit 1; }
    grep -q '^fun plain' "$here/project/src/jsMain/kotlin/Main.kt" || { echo "Main.kt has no plain" >&2; exit 1; }
    dup=$(echo "$versions" | tr , '\n' | sort | uniq -d)
    [ -z "$dup" ] || { echo "duplicate versions: $dup" >&2; exit 1; }
    for v in ${versions//,/ }; do
        validate "$v"
        render_settings "$v" | grep -q "version \"$v\"" || { echo "settings template does not take $v" >&2; exit 1; }
        render_build "$v" | grep -q "^    $(js_block "$v") {" || { echo "build template does not take $(js_block "$v")" >&2; exit 1; }
        echo "$v gradle $(gradle_line "$v") $(js_block "$v")"
    done
    echo "$(echo "$versions" | tr , ' ' | wc -w | tr -d ' ') versions; would write $out/<version>/ and $out/<version>/result.tsv"
}

ensure_wrapper() {
    local boot=$out/.wrapper/$1
    [ -x "$boot/gradlew" ] && return 0
    mkdir -p "$boot"
    printf 'rootProject.name = "wrapper"\n' > "$boot/settings.gradle.kts"
    "$gradle" -p "$boot" --no-daemon wrapper --gradle-version "$1" --distribution-type bin > "$boot.log" 2>&1
}

run_version() {
    local v=$1 gl dir log rc a d g p status cause
    gl=$(gradle_line "$v")
    dir=$out/$v
    [ ! -e "$dir" ] || { echo "$dir exists; pick a new --out" >&2; exit 3; }
    ensure_wrapper "$gl"
    mkdir -p "$dir"
    cp -R "$here/project/." "$dir/"
    cp -R "$out/.wrapper/$gl/gradlew" "$out/.wrapper/$gl/gradlew.bat" "$out/.wrapper/$gl/gradle" "$dir/"
    render_settings "$v" > "$dir/settings.gradle.kts"
    render_build "$v" > "$dir/build.gradle.kts"

    log=$dir/build.log
    set +e
    (cd "$dir" && ./gradlew --no-daemon --max-workers=2 --console=plain kotlinUpgradeYarnLock jsNodeProductionRun) > "$log" 2>&1
    rc=$?
    set -e

    a=$(grep -m1 -E '^A ' "$log" || true)
    d=$(grep -m1 -E '^D ' "$log" || true)
    g=$(grep -m1 -E '^G ' "$log" || true)
    p=$(grep -m1 -E '^P ' "$log" || true)
    cause=-
    if [ "$rc" -ne 0 ]; then
        status="build failed"
        cause=$(grep -m1 -E '^e: ' "$log" || true)
        [ -n "$cause" ] || cause=$(awk '/What went wrong:/ {getline; while ($0 ~ /^[[:space:]]*$/) getline; print; exit}' "$log")
        [ -n "$cause" ] || cause="exit $rc"
        a=-; d=-; g=-; p=-
    elif [ -z "$a" ] || [ -z "$d" ] || [ -z "$g" ] || [ -z "$p" ]; then
        status="no output"
        cause="missing an A, D, G or P line"
    elif [ "$a" = "$expect_a" ] && [ "$d" = "$expect_d" ] && [ "$g" = "$expect_g" ] && [ "$p" = "$expect_p" ]; then
        status=ok
    else
        status=wrong
    fi
    cause=${cause//|/\\|}
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$v" "$gl" "$a" "$d" "$g" "$p" "$status" "$cause" "$rc" > "$dir/result.tsv"
    echo "$v: $status (rc $rc)"
}

report() {
    echo "| version | Gradle | A | D | G | P (control) | verdict | cause | exit |"
    echo "| --- | --- | --- | --- | --- | --- | --- | --- | --- |"
    cat "$out"/*/result.tsv 2>/dev/null | sort -V | awk -F '\t' '{ printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s |\n", $1, $2, $3, $4, $5, $6, $7, $8, $9 }'
}

if [ $check = 1 ]; then
    check_project
    exit 0
fi

if [ $report = 1 ]; then
    report
    exit 0
fi

mkdir -p "$out"
for v in ${versions//,/ }; do
    validate "$v"
    run_version "$v"
done
report | tee "$out/table.md"
echo "table in $out/table.md"
