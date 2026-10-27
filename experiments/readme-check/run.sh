#!/bin/sh
# usage: run.sh [--check]   --check parses the README without the drift binary; otherwise DRIFT must name one
set -eu

dir=$(cd "$(dirname "$0")" && pwd)
readme="$dir/../../README.md"

if [ "${1:-}" = "--check" ]; then
	sh -n "$dir/check.sh"
	sh -n "$dir/run.sh"
	sh "$dir/check.sh" --dry "$readme"
	exit 0
fi

sh "$dir/check.sh" "$readme"
