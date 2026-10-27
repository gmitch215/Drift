# shared settings for the cold-start scripts; sourced, not run

COLD_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$COLD_DIR/../.." && pwd)
COLD_HOST=${COLD_HOST:-paisley-park}
COLD_RUN_ROOT=${COLD_RUN_ROOT:-$REPO/experiments/results/cold-start/runs}
COLD_DRIFT_BIN=${COLD_DRIFT_BIN:-drift-rig/bin/drift-linux-x64-c775d8f}

cold_ssh() {
	ssh "$COLD_HOST" "$@"
}

cold_container() {
	echo "drift-cold-$1-$2"
}

cold_run_dir() {
	cat "$COLD_RUN_ROOT/current-$1" 2>/dev/null
}

cold_mode() {
	grep -q '^mode=single$' "$1/run.env" 2>/dev/null && echo single || echo pair
}

cold_log() {
	printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" "$2" "$3" "$4" "$5" >> "$6/commands.log"
}
