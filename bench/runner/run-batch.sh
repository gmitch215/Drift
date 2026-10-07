#!/bin/sh
root=$1
shift
for d in "$root"/*/; do
	id=$(basename "$d")
	[ -f "$d/green.sh" ] || continue
	if [ $# -gt 0 ]; then
		case " $* " in
			*" $id "*) ;;
			*) continue ;;
		esac
	fi
	for arm in green red; do
		[ -s "$d/$arm.out" ] && continue
		avail=$(df -Pk "$d" | awk 'NR==2 {print $4}')
		if [ "$avail" -lt 26214400 ]; then
			echo "STOP low disk: $avail KB free"
			exit 3
		fi
		start=$(date +%s)
		sh "$d/$arm.sh" > "$d/$arm.out.tmp" 2> "$d/$arm.err"
		code=$?
		mv "$d/$arm.out.tmp" "$d/$arm.out"
		echo "$id $arm exit=$code secs=$(( $(date +%s) - start ))"
	done
done
echo "BATCH DONE"
