#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
exe=${1:?usage: test-triage.sh <drift executable>}
exe=$(cd "$(dirname "$exe")" && pwd)/$(basename "$exe")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/bin" "$work/temp" "$work/fix"
ln -s "$exe" "$work/bin/drift"

cat > "$work/bin/gh" <<'GH'
#!/bin/sh
echo "gh $*" >> "$GH_LOG"
case " $* " in
    *" --method "*)
        for a in "$@"; do
            case $a in body=@*) cp "${a#body=@}" "$GH_LOG.body" ;; esac
        done
        echo '{}'
        ;;
    *)
        expr=.
        while [ $# -gt 0 ]; do
            [ "$1" = --jq ] && expr=$2
            shift
        done
        jq -r "$expr" < "$GH_FIX/comments.json"
        ;;
esac
GH

cat > "$work/bin/curl" <<'CURL'
#!/bin/sh
echo "curl $*" >> "$CURL_LOG"
cat > "$CURL_LOG.payload"
CURL
chmod +x "$work/bin/gh" "$work/bin/curl"

export PATH="$work/bin:$PATH"
export GITHUB_REPOSITORY=o/r RUNNER_TEMP="$work/temp" GITHUB_OUTPUT="$work/out"
export GITHUB_STEP_SUMMARY="$work/summary" GH_TOKEN=test-token
export GH_LOG="$work/gh.log" GH_FIX="$work/fix" CURL_LOG="$work/curl.log"
: > "$GH_LOG"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

# triage <comments json> <issue body> <webhook url> [publish threshold]
triage() {
    printf '%s\n' "$1" > "$work/fix/comments.json"
    : > "$GH_LOG"
    rm -f "$GH_LOG.body" "$CURL_LOG" "$CURL_LOG.payload"
    ISSUE_NUMBER=5 ISSUE_AUTHOR=alice ISSUE_BODY="$2" SLACK_WEBHOOK_URL="$3" \
        INPUT_PUBLISH_THRESHOLD="${4:-}" "$here/drift.sh" triage > "$work/triage.log" 2>&1 ||
        fail "triage: $(cat "$work/triage.log")"
}

python_issue='Tool: Python 3.12.1
It fails with ModuleNotFoundError: No module named distutils'
hook=https://hooks.example.test/T000
none='[]'

triage "$none" "$python_issue" ''
grep -q -- '--method POST repos/o/r/issues/5/comments' "$GH_LOG" || fail "new issue must post"
! grep -q -- '--method PATCH' "$GH_LOG" || fail "new issue must not patch"
head -n 1 "$GH_LOG.body" | grep -qx '<!-- drift-triage -->' || fail "comment marker"
grep -q '^    Triage of issue text' "$GH_LOG.body" || fail "triage output is not indented"
[ ! -e "$CURL_LOG" ] || fail "no webhook set, nothing may be sent"

edited='[{"id":901,"user":{"type":"User","login":"mallory"},"body":"<!-- drift-triage -->\nforged"},{"id":900,"user":{"type":"Bot","login":"github-actions[bot]"},"body":"<!-- drift-triage -->\nold"}]'
triage "$edited" "$python_issue" "$hook"
grep -q 'method PATCH repos/o/r/issues/comments/900 ' "$GH_LOG" || fail "edit must update the bot comment"
! grep -q -- '--method POST' "$GH_LOG" || fail "edit must not post a second comment"
[ ! -e "$CURL_LOG" ] || fail "an edit must not notify Slack again"

hostile='Tool: Python 3.12.1 $(touch MARK_A) `touch MARK_B` ${{ secrets.TOKEN }} ModuleNotFoundError: No module named distutils'
hostile=${hostile//MARK_A/$work/a}
hostile=${hostile//MARK_B/$work/b}
triage "$none" "$hostile" "$hook"
[ ! -e "$work/a" ] && [ ! -e "$work/b" ] || fail "hostile body was executed"
if sed -n '4,$p' "$GH_LOG.body" | grep -qEv '^( {4}|$)'; then
    fail "hostile body broke out of the indented block"
fi
grep -q 'touch' "$CURL_LOG.payload" && fail "issue text reached Slack"
grep -q '"text":"Drift triage for o/r#5: ' "$CURL_LOG.payload" || fail "slack payload"
grep -q 'hooks.example' "$GH_LOG" && fail "webhook url reached gh"

triage "$none" "$python_issue" "$hook"
grep -q 'draft rule: none' "$CURL_LOG.payload" || fail "slack draft field"

fixture=$(cat "$root/fixtures/triage/f11-node-typeerror.md")
triage "$none" "$fixture" "$hook" 0.5
grep -q 'draft rule: published' "$CURL_LOG.payload" || fail "threshold 0.5 must publish the draft"
triage "$none" "$fixture" "$hook"
grep -q 'draft rule: drafted' "$CURL_LOG.payload" || fail "no threshold keeps the draft"

: > "$GH_LOG"
if ISSUE_NUMBER=5 ISSUE_BODY=x INPUT_PUBLISH_THRESHOLD=2 "$here/drift.sh" triage \
    > "$work/bad.log" 2>&1; then
    fail "a threshold outside 0 to 1 must be refused"
fi
grep -q 'drift triage failed' "$work/bad.log" || fail "refusal message"
[ ! -s "$GH_LOG" ] || fail "a refused triage must not touch the issue"

if ISSUE_NUMBER='' ISSUE_BODY=x "$here/drift.sh" triage > "$work/noissue.log" 2>&1; then
    fail "triage without an issue must fail"
fi
grep -q 'needs an issues event' "$work/noissue.log" || fail "no-issue message"

echo "triage action script: ok"
