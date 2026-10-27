#!/usr/bin/env bash
set -euo pipefail
exec python3 -I "$(cd "$(dirname "$0")" && pwd)/score.py" "$@"
