#!/bin/sh
# usage: check.sh [--dry] README   checks every drift command in the sh fences of README
set -u
set -f

dry=0
if [ "${1:-}" = "--dry" ]; then
	dry=1
	shift
fi
readme=${1:?README path}
if [ "$dry" -eq 0 ] && { [ -z "${DRIFT:-}" ] || [ ! -x "$DRIFT" ]; }; then
	echo "DRIFT must name an executable drift binary (or pass --dry)" >&2
	exit 2
fi

fail=0
count=0

report() {
	echo "FAIL $1" >&2
	fail=1
}

commands=$(awk '
	/^```sh$/ { inb = 1; next }
	/^```/ { inb = 0; next }
	inb && /^drift( |$)/ { sub(/[ \t]+#.*/, ""); print NR ":" $0 }
' "$readme")

while IFS=: read -r lineno cmd; do
	[ -n "$lineno" ] || continue
	count=$((count + 1))
	set -- $cmd
	shift
	sub=${1:-}
	if [ -z "$sub" ]; then
		report "line $lineno: no subcommand after drift"
		continue
	fi
	shift
	quotes=$(printf '%s' "$cmd" | tr -cd '"' | wc -c)
	if [ $((quotes % 2)) -ne 0 ]; then
		report "line $lineno: unbalanced double quote"
		continue
	fi
	if [ "$dry" -eq 1 ]; then
		echo "ok line $lineno: $cmd"
		continue
	fi

	before=$fail
	flags=""
	for w; do
		[ "$w" = "--" ] && break
		case $w in
		--*) flags="$flags ${w%%=*}" ;;
		esac
	done

	case $sub in
	--*) out=$("$DRIFT" "$sub" 2>&1); rc=$? ;;
	*) out=$("$DRIFT" "$sub" --help 2>&1); rc=$? ;;
	esac
	if [ "$rc" -ne 0 ]; then
		report "line $lineno: drift $sub does not run (exit $rc): $cmd"
		continue
	fi
	for flag in $flags; do
		printf '%s\n' "$out" | grep -F -e "$flag" >/dev/null || report "line $lineno: $flag is not listed by drift $sub --help"
	done
	[ "$fail" -eq "$before" ] && echo "ok line $lineno: $cmd"
done <<EOF
$commands
EOF

if [ "$count" -eq 0 ]; then
	report "no drift commands found in $readme"
fi
if [ "$fail" -eq 0 ]; then
	echo "PASS $count commands in $readme"
else
	echo "FAIL $count commands checked in $readme" >&2
fi
exit "$fail"
