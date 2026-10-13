#!/usr/bin/env sh
set -eu

usage() {
    cat <<'USAGE'
usage: tools/perf/perf.sh KIND BINARY OUT HOST LABEL [SIZE_PATH...]

  KIND       native, jvm or wasm
  BINARY     executable (native), launcher script (jvm) or dist directory (wasm)
  OUT        result JSON path, normally tools/perf/results/HOST-LABEL.json
  HOST       mac, paisley or paisley-2core
  LABEL      build label, such as native-cross or jvm
  SIZE_PATH  files whose bytes count as the artifact size (default BINARY)

environment: RUNS (15), WARMUP (3), GREEN and RED (the fixture capsule pair)
Runs version, capture and diagnose RUNS times each after WARMUP discarded runs.
Needs python3. Wasm records size only.
USAGE
}

if [ "$#" -lt 5 ]; then
    usage >&2
    exit 2
fi

kind=$1 binary=$2 out=$3 host=$4 label=$5
shift 5
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)

if [ "$#" -eq 0 ]; then
    set -- "$binary"
fi
sizes=""
for p in "$@"; do
    sizes="$sizes --size-of $p"
done

# shellcheck disable=SC2086
exec python3 "$here/measure.py" --kind "$kind" --binary "$binary" --out "$out" \
    --host "$host" --label "$label" --runs "${RUNS:-15}" --warmup "${WARMUP:-3}" \
    --green "${GREEN:-$root/fixtures/drangler/capsules/36702683742.json}" \
    --red "${RED:-$root/fixtures/drangler/capsules/36995781138.json}" \
    --stamp "$(date -u +%Y-%m-%dT%H:%M:%SZ)" $sizes
