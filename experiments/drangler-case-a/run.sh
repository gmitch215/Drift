#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
reps=15
check=0
suite=
body=${BODY:-}

usage() {
    echo "usage: run.sh [--check] [--reps N] [--suite DRANGLER_TREE --body CONVERTED_SQL]"
    echo "env: LOGS (CI log directory), LIMIT_MS (default 5000), UNDICI (path of an undici package entry for the client)"
}

while [ $# -gt 0 ]; do
    case $1 in
    --check) check=1 ;;
    --reps) reps=$2; shift ;;
    --suite) suite=$2; shift ;;
    --body) body=$2; shift ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; exit 64 ;;
    esac
    shift
done

command -v node > /dev/null || { echo "node not found" >&2; exit 2; }

for f in lib idle seedrepro replay min; do
    [ -f "$here/lab/$f.mjs" ] || { echo "missing lab/$f.mjs" >&2; exit 1; }
    node --check "$here/lab/$f.mjs"
done

echo "== CI logs: sync test time against the 5 s idle limit"
"$here/ci-timings.sh"

if [ $check = 1 ]; then
    echo "would run: node lab/min.mjs block:5.5,block:6,sleep:6 $reps"
    echo "would run with --suite: lab/idle.mjs and lab/seedrepro.mjs block:6,newconn:6,sleep:6 against the drangler fixture worker"
    exit 0
fi

echo "== minimal reproduction: Node server, Node client, no workerd and no drangler"
out=$(node "$here/lab/min.mjs" block:5.5,block:6,sleep:6 "$reps")
echo "$out"
echo "$out" | node -e '
    const r = JSON.parse(require("fs").readFileSync(0, "utf8")).results;
    const wilson = (k, n) => {
        const z = 1.96, p = k / n, d = 1 + z * z / n;
        const c = (p + z * z / (2 * n)) / d, h = z * Math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d;
        return [c - h, c + h];
    };
    const b = r["block:6"], s = r["sleep:6"];
    const [bl] = wilson(b.fail, b.n), [, sh] = wilson(s.fail, s.n);
    console.log(`block 6 s: ${b.fail}/${b.n} failed (lower bound ${bl.toFixed(2)}); sleep 6 s: ${s.fail}/${s.n} failed (upper bound ${sh.toFixed(2)})`);
    if (!(bl > sh)) { console.log("FAIL: the blocked client does not fail more often than the idle client"); process.exit(1); }
    console.log("PASS: a client that blocks its event loop past the server idle time fails; one that sleeps does not");
'

if [ -n "$suite" ]; then
    [ -n "$body" ] || { echo "--suite needs --body, a converted dump" >&2; exit 64; }
    export DRANGLER_TREE=$suite
    echo "== fixture worker idle close"
    node "$here/lab/idle.mjs" 3
    echo "== fixture worker seed"
    node "$here/lab/seedrepro.mjs" block:6,newconn:6,sleep:6 "$reps" "$body"
fi

echo "paisley-park: experiments/run.sh drangler-case-a --paisley"
