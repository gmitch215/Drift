#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
half=heldout
check=0

usage() {
    echo "usage: run.sh [--check] [--half dev|heldout]"
    echo "without --check: fetch, filter, batch and tier-a for one half (gh must be logged in; env: GH_BUDGET, GQL_BUDGET, RUNS)"
}

while [ $# -gt 0 ]; do
    case $1 in
    --check) check=1 ;;
    --half) half=$2; shift ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; exit 64 ;;
    esac
    shift
done

if [ $check = 1 ]; then
    python3 -I "$here/score.py" --check
    exec python3 -I "$here/corpus_id.py" check
fi

"$here/fetch.sh" --half "$half"
"$here/filter.sh" --half "$half"
"$here/batch.sh" --half "$half"
"$here/tier-a.sh" --half "$half"
