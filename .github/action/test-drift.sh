#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
exe=${1:?usage: test-drift.sh <drift executable>}
exe=$(cd "$(dirname "$exe")" && pwd)/$(basename "$exe")
work=$(mktemp -d)
[ -z "${KEEP:-}" ] || echo "work: $work" >&2
trap '[ -n "${KEEP:-}" ] || rm -rf "$work"' EXIT

version=$(sed -n 's/^version=//p' "$root/gradle.properties")
grep -q "default: v$version\$" "$root/action.yml" || {
    echo "action.yml version default does not match gradle.properties ($version)" >&2
    exit 1
}

case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) export RUNNER_OS=macOS RUNNER_ARCH=ARM64 name=macos-arm64 ;;
    Linux-x86_64) export RUNNER_OS=Linux RUNNER_ARCH=X64 name=linux-x64 ;;
    Linux-aarch64) export RUNNER_OS=Linux RUNNER_ARCH=ARM64 name=linux-arm64 ;;
    *) echo "unsupported host" >&2; exit 1 ;;
esac

export RUNNER_TEMP="$work/temp" GITHUB_PATH="$work/path" GITHUB_OUTPUT="$work/out"
export GITHUB_STEP_SUMMARY="$work/summary" GITHUB_REPOSITORY=o/r GITHUB_RUN_ID=77
export GITHUB_REF_NAME=feature GITHUB_HEAD_REF='' GITHUB_BASE_REF=''
export DRIFT_DEFAULT_BRANCH=master
mkdir -p "$RUNNER_TEMP"
: > "$GITHUB_PATH"

mkdir -p "$work/release/v$version"
(cd "$work" && "$root/.github/release/stage.sh" archive "$exe" "drift-$version-$name.tar.gz")
mv "$work/dist/"* "$work/release/v$version/"
export DRIFT_INSTALL_BASE_URL="file://$work/release" DRIFT_VERSION="v$version"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

"$here/drift.sh" install > "$work/install.log" 2>&1 || fail "install: $(cat "$work/install.log")"
grep -q 'sha256 verified' "$work/install.log" || fail "install did not verify the digest"
bin=$(tail -n 1 "$GITHUB_PATH")
[ -x "$bin/drift" ] || fail "drift not extracted to $bin"
export PATH="$bin:$PATH"

printf 'tampered' > "$work/release/v$version/drift-$version-$name.tar.gz"
if "$here/drift.sh" install > "$work/bad.log" 2>&1; then fail "a changed archive must fail"; fi
grep -q 'checksum mismatch' "$work/bad.log" || fail "no checksum error"

mkdir -p "$work/bin" "$work/fix"
cat > "$work/bin/gh" <<'GH'
#!/bin/sh
echo "gh $*" >> "$GH_LOG"
case $1 in
    api)
        url=$2
        shift 2
        expr=.
        while [ $# -gt 0 ]; do
            [ "$1" = --jq ] && expr=$2
            shift
        done
        case $url in
            *name=missing*) f=art-none ;;
            *actions/runs?branch=master*) f=runs-master ;;
            *actions/runs/60/artifacts*) f=art-60 ;;
            *actions/runs/41/artifacts*) f=art-41 ;;
            *actions/runs?branch=*) f=runs-none ;;
            *) f=art-none ;;
        esac
        jq -r "$expr" < "$GH_FIX/$f.json"
        ;;
    run)
        while [ $# -gt 0 ]; do
            [ "$1" = -D ] && dest=$2
            shift
        done
        mkdir -p "$dest" && cp -R "$GH_BASELINE/." "$dest/"
        ;;
esac
GH
chmod +x "$work/bin/gh"
run() { echo "{\"id\":$1,\"head_branch\":\"master\",\"head_repository\":{\"id\":$2},\"repository\":{\"id\":1}}"; }
art() { echo "{\"artifacts\":[{\"id\":$1,\"expired\":false,\"workflow_run\":{\"id\":$2,\"head_branch\":\"master\",\"head_repository_id\":$3,\"repository_id\":1}}]}"; }
echo "{\"workflow_runs\":[$(run 70 2),$(run 60 1),$(run 41 1)]}" > "$work/fix/runs-master.json"
echo '{"workflow_runs":[]}' > "$work/fix/runs-none.json"
art 600 60 2 > "$work/fix/art-60.json"
art 410 41 1 > "$work/fix/art-41.json"
echo '{"artifacts":[]}' > "$work/fix/art-none.json"
export GH_LOG="$work/gh.log" GH_BASELINE="$work/green" GH_FIX="$work/fix"
: > "$GH_LOG"

red='if [ "$TZ" != UTC ]; then echo "Error: unexpected timezone $TZ"; exit 3; fi; echo ok'
export INPUT_LABEL=t
cd "$work"

export GITHUB_EVENT_NAME=push
INPUT_MODE=run INPUT_COMMAND="$red" INPUT_DIRECTORY=green TZ=UTC \
    "$here/drift.sh" run > "$work/green.log" 2>&1 || fail "green run failed: $(cat "$work/green.log")"
grep -q '^save-baseline=true$' "$GITHUB_OUTPUT" || fail "green push run does not save a baseline"
grep -q 'drift-baseline-feature-t' "$GITHUB_OUTPUT" || fail "baseline artifact name"
grep -q 'saved as the baseline' "$GITHUB_STEP_SUMMARY" || fail "green summary"
if grep -q 'INPUT_\|DRIFT_VERSION' green/capsule.json; then
    fail "action inputs leaked into the capsule"
fi
grep -q 'env.GITHUB_RUN_ID' green/capsule.json || fail "run identity was not recorded"

for case in "pull_request" "fork:push"; do
    : > "$GITHUB_OUTPUT"
    ev=${case%%:*}
    repo=
    [ "$case" = fork:push ] && repo=evil/r
    GITHUB_EVENT_NAME=$ev DRIFT_HEAD_REPO=$repo INPUT_MODE=run INPUT_COMMAND=true INPUT_DIRECTORY=x$ev \
        "$here/drift.sh" run > "$work/x.log" 2>&1 || fail "run for $case"
    grep -q '^save-baseline=false$' "$GITHUB_OUTPUT" || fail "$case must not save a baseline"
done

: > "$GITHUB_OUTPUT"
: > "$GITHUB_STEP_SUMMARY"
code=0
INPUT_MODE=run INPUT_COMMAND="$red" INPUT_DIRECTORY=red INPUT_BASELINE="$work/green" TZ=Asia/Tokyo \
    "$here/drift.sh" run > "$work/red.log" 2>&1 || code=$?
[ "$code" = 3 ] || fail "red run exit code $code, want 3"
grep -q '^exit-code=3$' "$GITHUB_OUTPUT" || fail "exit-code output"
grep -q '^save-baseline=false$' "$GITHUB_OUTPUT" || fail "red run must not save a baseline"
grep -q 'env.TZ differs most' "$GITHUB_STEP_SUMMARY" || fail "summary lacks the diagnosis"
[ -s red/diagnosis.txt ] || fail "no diagnosis.txt"

: > "$GITHUB_STEP_SUMMARY"
code=0
INPUT_MODE=run INPUT_COMMAND="$red" INPUT_DIRECTORY=red2 INPUT_BASELINE='' TZ=Asia/Tokyo \
    PATH="$work/bin:$PATH" "$here/drift.sh" run > "$work/red2.log" 2>&1 || code=$?
[ "$code" = 3 ] || fail "artifact baseline exit code $code, want 3"
grep -q 'drift-baseline-master-t (run 41 on master)' "$GITHUB_STEP_SUMMARY" ||
    fail "trusted baseline not used: $(cat "$GITHUB_STEP_SUMMARY")"
grep -q 'run download 41 -R o/r -n drift-baseline-master-t' "$GH_LOG" || fail "gh run download"
if grep -q 'run download 60\|runs/70/artifacts\|run download 70' "$GH_LOG"; then
    fail "a fork or poisoned artifact was fetched"
fi
head -n 1 "$GH_LOG" | grep -q 'runs?branch=feature&event=push&status=success' ||
    fail "own branch looked up first on push"

: > "$GH_LOG"
: > "$GITHUB_STEP_SUMMARY"
GITHUB_EVENT_NAME=pull_request GITHUB_HEAD_REF=feature GITHUB_BASE_REF=master \
    INPUT_MODE=run INPUT_COMMAND="$red" INPUT_DIRECTORY=red4 TZ=Asia/Tokyo \
    PATH="$work/bin:$PATH" "$here/drift.sh" run > "$work/red4.log" 2>&1 || true
grep -q 'drift-baseline-master-t (run 41 on master)' "$GITHUB_STEP_SUMMARY" || fail "pr baseline"
if grep -q 'branch=feature' "$GH_LOG"; then fail "the pull request head ref was used as a baseline source"; fi

: > "$GITHUB_STEP_SUMMARY"
code=0
INPUT_MODE=run INPUT_COMMAND="$red" INPUT_DIRECTORY=red3 INPUT_BASELINE=missing-name TZ=Asia/Tokyo \
    PATH="$work/bin:$PATH" "$here/drift.sh" run > "$work/red3.log" 2>&1 || code=$?
[ "$code" = 3 ] || fail "no-baseline exit code $code, want 3"
grep -q 'No green baseline' "$GITHUB_STEP_SUMMARY" || fail "no-baseline summary"

: > "$GITHUB_OUTPUT"
: > "$GITHUB_STEP_SUMMARY"
INPUT_MODE=diagnose INPUT_DIRECTORY=cur INPUT_BASELINE="$work/green" TZ=Asia/Tokyo \
    "$here/drift.sh" run > "$work/diag.log" 2>&1 || fail "diagnose mode: $(cat "$work/diag.log")"
grep -q '^save-baseline=false$' "$GITHUB_OUTPUT" || fail "diagnose must not save a baseline"
grep -q '```text' "$GITHUB_STEP_SUMMARY" || fail "diagnose summary"

: > "$GITHUB_OUTPUT"
INPUT_MODE=capture INPUT_DIRECTORY=cap "$here/drift.sh" run > "$work/cap.log" 2>&1 ||
    fail "capture mode"
grep -q '^save-baseline=true$' "$GITHUB_OUTPUT" || fail "capture saves a baseline"
[ -f cap/capsule.json ] || fail "capture wrote no capsule"

if INPUT_MODE=run INPUT_COMMAND='' "$here/drift.sh" run > "$work/nocmd.log" 2>&1; then
    fail "mode run without a command must fail"
fi
grep -q 'needs a command' "$work/nocmd.log" || fail "no-command message"
if INPUT_MODE=nope "$here/drift.sh" run > "$work/mode.log" 2>&1; then fail "bad mode"; fi

"$here/test-triage.sh" "$exe"
echo "drift action script: ok"
