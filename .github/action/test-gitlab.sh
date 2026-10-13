#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
exe=${1:?usage: test-gitlab.sh <drift executable>}
exe=$(cd "$(dirname "$exe")" && pwd)/$(basename "$exe")
work=$(mktemp -d)
trap '[ -n "${KEEP:-}" ] || rm -rf "$work"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

version=$(sed -n 's/^version=//p' "$root/gradle.properties")
grep -q "default: v$version\$" "$root/templates/drift.yml" ||
    fail "templates/drift.yml version default does not match gradle.properties ($version)"

case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) name=linux-x64 ;;
    Linux-aarch64) name=linux-arm64 ;;
    *) fail "the component script runs on Linux only" ;;
esac

mkdir -p "$work/release/v$version" "$work/bin"
(cd "$work" && "$root/.github/release/stage.sh" archive "$exe" "drift-$version-$name.tar.gz")
mv "$work/dist/"* "$work/release/v$version/"

export IN_command='if [ "$TZ" != UTC ]; then echo "Error: unexpected timezone $TZ"; exit 3; fi; echo ok'
export IN_version="v$version"
ruby -ryaml -rshellwords -e '
    fill = ->(s) { s.gsub(/\$\[\[ inputs\.(\w+) \]\]/) { ENV.fetch("IN_#{$1}", "drift") } }
    job = YAML.safe_load(File.read(ARGV[0]).split("\n---\n")[1]).values.first
    job["variables"].each { |k, v| puts "export #{k}=#{fill.(v.to_s).shellescape}" }
    puts job["script"].join("\n")
' "$root/templates/drift.yml" > "$work/job.sh" || fail "template does not parse"

real_curl=$(command -v curl)
cat > "$work/bin/curl" <<CURL
#!/bin/sh
echo "curl \$*" >> "$work/curl.log"
case "\$*" in
    *gitlab.test*jobs/artifacts/master/download*)
        while [ \$# -gt 0 ]; do
            [ "\$1" = -o ] && out=\$2
            shift
        done
        cp "$work/baseline.zip" "\$out"
        ;;
    *gitlab.test*) exit 22 ;;
    *) exec "$real_curl" "\$@" ;;
esac
CURL
chmod +x "$work/bin/curl"

export DRIFT_BASE_URL="file://$work/release"
export CI_API_V4_URL=https://gitlab.test/api/v4 CI_PROJECT_ID=9 CI_JOB_TOKEN=tok
export CI_PIPELINE_ID=123 CI_JOB_NAME=drift CI_DEFAULT_BRANCH=master CI_COMMIT_BRANCH=feature
export PATH="$work/bin:$PATH"

job() {
    local dir=$1
    shift
    rm -rf "$work/cwd"
    mkdir -p "$work/cwd"
    local status=0
    (cd "$work/cwd" && env "$@" bash -c ". $work/job.sh") > "$work/$dir.log" 2>&1 || status=$?
    rm -rf "${work:?}/$dir"
    mv "$work/cwd" "$work/$dir"
    return "$status"
}

job green TZ=UTC || fail "green job: $(cat "$work/green.log")"
[ -f "$work/green/drift-capsule/capsule.json" ] || fail "no capsule from the green job"
[ ! -s "$work/curl.log" ] || ! grep -q gitlab.test "$work/curl.log" || fail "green job called the API"
if grep -q 'DRIFT_COMMAND\|CI_JOB_TOKEN' "$work/green/drift-capsule/capsule.json"; then
    fail "action inputs or the job token leaked into the capsule"
fi
grep -q 'env.CI_PIPELINE_ID' "$work/green/drift-capsule/capsule.json" || fail "run identity was not recorded"
grep -q 'CI_JOB_NAME' "$work/green/drift-capsule/capsule.json" || fail "CI_JOB_NAME was scrubbed"
(cd "$work/green" && python3 -m zipfile -c "$work/baseline.zip" drift-capsule)

code=0
job red TZ=Asia/Tokyo || code=$?
[ "$code" = 3 ] || fail "red job exit code $code, want 3"
grep -q 'baseline is the latest green drift on master' "$work/red.log" || fail "baseline log line"
grep -q 'env.TZ differs most' "$work/red.log" || fail "diagnosis in the job log"
[ -s "$work/red/drift-capsule/diagnosis.txt" ] || fail "no diagnosis.txt artifact"
grep -q 'job=drift' "$work/curl.log" || fail "job name not sent"
grep -q 'JOB-TOKEN: tok' "$work/curl.log" || fail "job token not sent as a header"
if grep -q 'job_token' "$work/curl.log"; then fail "job token sent in the URL"; fi

: > "$work/curl.log"
code=0
job mr TZ=Asia/Tokyo CI_MERGE_REQUEST_TARGET_BRANCH_NAME=release CI_COMMIT_BRANCH=feature || code=$?
[ "$code" = 3 ] || fail "mr job exit code $code, want 3"
grep -q 'artifacts/release/download' "$work/curl.log" || fail "target branch not tried first"
if grep -q 'artifacts/feature/download' "$work/curl.log"; then fail "source branch fetched"; fi
grep -q 'baseline is the latest green drift on master' "$work/mr.log" || fail "default branch fallback"

: > "$work/curl.log"
code=0
job none TZ=Asia/Tokyo CI_DEFAULT_BRANCH=other || code=$?
[ "$code" = 3 ] || fail "no-baseline exit code $code, want 3"
grep -q 'no green baseline found' "$work/none.log" || fail "no-baseline message"

echo "gitlab component script: ok"
