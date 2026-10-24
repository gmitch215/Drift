#!/bin/sh
root=$1
shift
jobs=1
ids=
while [ $# -gt 0 ]; do
    case $1 in
        --jobs) jobs=$2; shift 2 ;;
        *) ids="$ids $1"; shift ;;
    esac
done
root=$(cd "$root" && pwd)
SOLVED=${SOLVED:-$(dirname "$root")/solved}
export SOLVED
log=$root/solve.log

solve_one() {
    d=$1
    id=$(basename "$d")
    start=$(date +%s)
    missing=
    for tag in $(cat "$d/images"); do
        docker image inspect "$tag" > /dev/null 2>&1 || missing="$missing $tag"
    done
    if [ -n "$missing" ]; then
        echo "missing image:$missing" > "$d/solve.err"
        echo "$id no-image 0" >> "$log"
        return
    fi
    sh "$d/solve.sh" > /dev/null 2>&1
    echo "$id $(cat "$d/solve.verdict") $(( $(date +%s) - start ))" >> "$log"
}

running=0
for d in "$root"/*/; do
    id=$(basename "$d")
    [ -f "$d/solve.sh" ] || continue
    if [ -n "$ids" ]; then
        case " $ids " in
            *" $id "*) ;;
            *) continue ;;
        esac
    fi
    [ -s "$d/solve.verdict" ] && continue
    [ -f "$SOLVED/$id/case.json" ] && continue
    avail=$(df -Pk "$d" | awk 'NR==2 {print $4}')
    if [ "$avail" -lt 26214400 ]; then
        echo "STOP low disk: $avail KB free"
        wait
        exit 3
    fi
    if [ "$jobs" -le 1 ]; then
        solve_one "$d"
    else
        solve_one "$d" &
        running=$((running + 1))
        if [ "$running" -ge "$jobs" ]; then
            wait
            running=0
        fi
    fi
done
wait
echo "SOLVE DONE"
