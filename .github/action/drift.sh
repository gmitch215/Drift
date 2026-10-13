#!/usr/bin/env bash
set -euo pipefail

say() {
    printf 'drift-action: %s\n' "$*" >&2
}

die() {
    say "error: $*"
    exit 1
}

safe() {
    printf '%s' "$1" | sed 's/[^A-Za-z0-9._-]/-/g'
}

emit() {
    printf '%s=%s\n' "$1" "$2" >> "${GITHUB_OUTPUT:-/dev/null}"
}

summary() {
    cat >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
}

drift_clean() {
    (
        unset "${!INPUT_@}" "${!DRIFT_@}" GH_TOKEN
        exec drift "$@"
    )
}

capture_scrubbed() {
    (
        local v
        for v in $(compgen -v); do
            case $v in
                ACTIONS_* | INPUT_* | DRIFT_* | STATE_* | GH_TOKEN)
                    unset "$v"
                    ;;
            esac
        done
        drift capture --label "$1"
    )
}

sha256_of() {
    if command -v sha256sum > /dev/null 2>&1; then
        sha256sum "$1"
    else
        shasum -a 256 "$1"
    fi
}

extract() {
    case $1 in
        *.zip)
            if command -v unzip > /dev/null 2>&1; then
                unzip -q -o "$1" -d "$2"
            elif command -v 7z > /dev/null 2>&1; then
                7z x -y -bd "-o$2" "$1" > /dev/null
            else
                die "unzip or 7z is required to extract $1"
            fi
            ;;
        *) tar -xzf "$1" -C "$2" ;;
    esac
}

install_drift() {
    local tag=${DRIFT_VERSION:?DRIFT_VERSION is not set}
    local version=${tag#v} name ext
    case "${RUNNER_OS:?}-${RUNNER_ARCH:?}" in
        Linux-X64) name=linux-x64 ext=tar.gz ;;
        Linux-ARM64) name=linux-arm64 ext=tar.gz ;;
        macOS-ARM64) name=macos-arm64 ext=tar.gz ;;
        Windows-X64) name=windows-x64 ext=zip ;;
        *) die "no release asset is built for $RUNNER_OS on $RUNNER_ARCH" ;;
    esac
    local asset="drift-$version-$name.$ext"
    local base=${DRIFT_INSTALL_BASE_URL:-https://github.com/gmitch215/Drift/releases/download}
    local url="${base%/}/v$version/$asset"
    local dir="${RUNNER_TEMP:?}/drift-bin"
    mkdir -p "$dir/extract"

    say "downloading $asset"
    curl -fsSL -o "$dir/$asset" "$url" || die "cannot download $url"
    curl -fsSL -o "$dir/$asset.sha256" "$url.sha256" || die "cannot download $url.sha256"
    local expected actual
    read -r expected _ < "$dir/$asset.sha256" || true
    actual=$(sha256_of "$dir/$asset")
    actual=${actual%% *}
    [ "$actual" = "$expected" ] ||
        die "checksum mismatch for $asset (expected $expected, got $actual)"
    say "sha256 verified"

    extract "$dir/$asset" "$dir/extract"
    local bin=$dir/extract
    [ -f "$bin/drift" ] || [ -f "$bin/drift.exe" ] || die "$asset does not contain drift"
    chmod +x "$bin/drift" 2> /dev/null || true
    if command -v cygpath > /dev/null 2>&1; then
        cygpath -w "$bin" >> "${GITHUB_PATH:-/dev/null}"
    else
        printf '%s\n' "$bin" >> "${GITHUB_PATH:-/dev/null}"
    fi
    "$bin/drift" --version
}

fetch_baseline() {
    local given=${INPUT_BASELINE:-} label=$1
    baseline=
    if [ -n "$given" ] && [ -e "$given" ]; then
        baseline=$given
        baseline_name=$given
        return 0
    fi
    command -v gh > /dev/null 2>&1 || {
        say "gh is not installed, so no baseline can be fetched"
        return 1
    }
    local refs=() ref name run_id found target="${RUNNER_TEMP:-/tmp}/drift-baseline"
    if [ "${GITHUB_EVENT_NAME:-}" = pull_request ] || [ "${GITHUB_EVENT_NAME:-}" = pull_request_target ]; then
        refs+=("${GITHUB_BASE_REF:-}")
    else
        refs+=("${GITHUB_REF_NAME:-}")
    fi
    refs+=("${DRIFT_DEFAULT_BRANCH:-}")
    for ref in "${refs[@]}"; do
        [ -n "$ref" ] || continue
        name=${given:-drift-baseline-$(safe "$ref")-$label}
        export DRIFT_REF=$ref
        local runs
        runs=$(gh api "repos/$GITHUB_REPOSITORY/actions/runs?branch=$ref&event=push&status=success&per_page=30" \
            --jq '[.workflow_runs[] | select(.head_branch == $ENV.DRIFT_REF and .head_repository.id == .repository.id)] | .[:10][] | .id') || {
            say "cannot list runs; the job needs actions: read"
            return 1
        }
        for run_id in $runs; do
            found=$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$run_id/artifacts?name=$name" \
                --jq '[.artifacts[] | select(.expired == false and .workflow_run.head_branch == $ENV.DRIFT_REF and .workflow_run.head_repository_id == .workflow_run.repository_id)] | first | .id // empty') || continue
            [ -n "$found" ] || continue
            mkdir -p "$target"
            if gh run download "$run_id" -R "$GITHUB_REPOSITORY" -n "$name" -D "$target"; then
                baseline=$target
                baseline_name="$name (run $run_id on $ref)"
                return 0
            fi
            say "cannot download $name from run $run_id"
        done
    done
    return 1
}

run_mode() {
    local mode=${INPUT_MODE:-run} dir=${INPUT_DIRECTORY:-drift-capsule}
    local label ec=0 branch cmd=${INPUT_COMMAND:-}
    label=$(safe "${INPUT_LABEL:-drift}")
    branch=$(safe "${GITHUB_HEAD_REF:-${GITHUB_REF_NAME:-local}}")
    case $mode in
        run) [ -n "${INPUT_COMMAND:-}" ] || die "mode run needs a command" ;;
        capture | diagnose) ;;
        *) die "unknown mode: $mode (use capture, diagnose or run)" ;;
    esac

    if [ "$mode" = run ]; then
        local scrubbed
        scrubbed=$(mktemp)
        capture_scrubbed "$label" > "$scrubbed" || die "capture failed"
        drift_clean run --out "$dir" --label "$label" -- bash -c "$cmd" || ec=$?
        [ -f "$dir/capsule.json" ] && mv "$scrubbed" "$dir/capsule.json"
    else
        mkdir -p "$dir"
        capture_scrubbed "$label" > "$dir/capsule.json" || die "capture failed"
    fi
    [ -f "$dir/capsule.json" ] || die "no capsule was written to $dir"

    emit capsule "$dir"
    emit capsule-artifact "drift-capsule-$label"
    emit baseline-artifact "drift-baseline-$branch-$label"
    emit exit-code "$ec"
    local save=false
    if [ "$mode" != diagnose ] && [ "$ec" = 0 ] && [ "${INPUT_SAVE_BASELINE:-true}" = true ] &&
        [ "${GITHUB_EVENT_NAME:-}" = push ] &&
        { [ -z "${DRIFT_HEAD_REPO:-}" ] || [ "$DRIFT_HEAD_REPO" = "${GITHUB_REPOSITORY:-}" ]; }; then
        save=true
    fi
    emit save-baseline "$save"

    local diagnose=false
    if [ "$mode" = diagnose ] || { [ "$mode" = run ] && [ "$ec" != 0 ]; }; then
        diagnose=true
    fi
    if [ "$diagnose" = false ]; then
        {
            printf '## Drift: %s\n\n' "$label"
            if [ "$mode" = run ]; then
                printf 'The command passed. The capsule is saved as the baseline for `%s`.\n' "$branch"
            else
                printf 'Captured the environment of this job.\n'
            fi
        } | summary
        return 0
    fi

    {
        printf '## Drift: %s\n\n' "$label"
        if [ "$mode" = run ]; then printf 'The command exited with %s.\n\n' "$ec"; fi
    } | summary
    local baseline baseline_name
    if ! fetch_baseline "$label"; then
        printf 'No green baseline was found for `%s`. Run this job green once on a branch\n' "$label" |
            summary
        printf 'to save one, or pass `baseline`.\n' | summary
        [ "$mode" = diagnose ] && say "no baseline found"
        return "$ec"
    fi
    local report=$dir/diagnosis.txt
    if drift diagnose --against "$baseline" "$dir" --detail summary > "$report"; then
        {
            printf 'Compared with %s:\n\n```text\n' "$baseline_name"
            cat "$report"
            printf '```\n'
        } | summary
        cat "$report"
    else
        printf 'The comparison with %s failed; see the job log.\n' "$baseline_name" | summary
        [ "$mode" = diagnose ] && ec=1
    fi
    return "$ec"
}

notify_slack() {
    local report=$1 n=$2 facts rules claims draft=drafted payload
    facts=$(sed -n 's/^Facts (\([0-9]*\))$/\1/p' "$report")
    rules=$(sed -n 's/^Rules (\([0-9]*\))$/\1/p' "$report")
    claims=$(sed -n 's/^Claims (\([0-9]*\))$/\1/p' "$report")
    if grep -q '^Draft rule: none$' "$report"; then
        draft=none
    elif grep -q '^Draft rule .*: publish ' "$report"; then
        draft=published
    fi
    payload=$(printf '{"text":"Drift triage for %s#%s: %s facts, %s rules, %s claims; draft rule: %s. https://github.com/%s/issues/%s"}' \
        "$GITHUB_REPOSITORY" "$n" "$facts" "$rules" "$claims" "$draft" "$GITHUB_REPOSITORY" "$n")
    printf '%s' "$payload" | curl -fsS --max-time 10 -H 'Content-Type: application/json' \
        --data-binary @- "$SLACK_WEBHOOK_URL" > /dev/null || say "slack notification failed"
}

triage_mode() {
    [ -n "${ISSUE_NUMBER:-}" ] || die "triage needs an issues event"
    local repo=${GITHUB_REPOSITORY:?} n=$ISSUE_NUMBER
    local args=(triage --date "$(date -u +%Y-%m-%d)")
    local report body ids id created=false
    report=$(mktemp)
    body=$(mktemp)
    [ -z "${INPUT_PUBLISH_THRESHOLD:-}" ] || args+=(--publish-threshold "$INPUT_PUBLISH_THRESHOLD")
    [ -z "${ISSUE_AUTHOR:-}" ] || args+=(--author "$ISSUE_AUTHOR")
    printf '%s' "${ISSUE_BODY:-}" | drift_clean "${args[@]}" - > "$report" ||
        die "drift triage failed"

    {
        printf '<!-- drift-triage -->\n## Drift triage\n\n'
        sed 's/^/    /' "$report"
    } > "$body"

    export DRIFT_MARKER='<!-- drift-triage -->'
    ids=$(gh api "repos/$repo/issues/$n/comments" --paginate \
        --jq '.[] | select(.user.type == "Bot" and (.body | contains($ENV.DRIFT_MARKER))) | .id') ||
        die "cannot list the comments of #$n"
    id=${ids%%$'\n'*}
    if [ -n "$id" ]; then
        gh api --method PATCH "repos/$repo/issues/comments/$id" -F "body=@$body" > /dev/null ||
            die "cannot update the triage comment on #$n"
    else
        gh api --method POST "repos/$repo/issues/$n/comments" -F "body=@$body" > /dev/null ||
            die "cannot post the triage comment on #$n"
        created=true
    fi

    if [ "$created" = true ] && [ -n "${SLACK_WEBHOOK_URL:-}" ]; then
        notify_slack "$report" "$n"
    fi
    cat "$report"
    rm -f "$report" "$body"
}

case ${1:-} in
    install) install_drift ;;
    run) run_mode ;;
    triage) triage_mode ;;
    *) die "usage: drift.sh install|run|triage" ;;
esac
