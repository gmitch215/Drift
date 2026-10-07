#!/usr/bin/env sh
set -u
stage="$1"
version="$2"
target="$3"
rig="${LADDER_RIG:-/rig}"
tc="$rig/cache/ladder/tc"
kc="$tc/kotlinc-$version"
work="$rig/ladder/work/$version-$target"
probes="$rig/ladder/probes"
stdlib="$kc/lib/kotlin-stdlib.jar"
export KONAN_DATA_DIR="$rig/cache/ladder/konan"
mkdir -p "$work"

tool() {
	java -cp "$rig/ladder/tool/ladder-tool.jar:$tc/kotlinc-2.4.20/lib/kotlin-stdlib.jar" \
		dev.gmitch215.drift.tools.ladder.LadderMainKt "$@"
}

native() {
	ls -d "$tc"/kotlin-native-*"-$version" | head -1
}

legacy_flag=""
[ "$version" = 1.8.22 ] && legacy_flag="-Xuse-deprecated-legacy-compiler"

compile() {
	case "$target" in
	jvm) "$kc/bin/kotlinc" -nowarn "$1"/*.kt -d "$work/out.$i" ;;
	linux | macos) "$(native)/bin/konanc" -nowarn -entry ladder.main "$1"/*.kt -o "$work/out.$i/probes" ;;
	js-legacy)
		"$kc/bin/kotlinc-js" -nowarn "$1"/*.kt -output "$work/out.$i/probes.js" -main call \
			-module-kind plain $legacy_flag
		;;
	js-ir)
		if [ "${version%%.*}" = 2 ]; then
			"$kc/bin/kotlinc-js" -nowarn "$1"/*.kt -libraries "$kc/lib/kotlin-stdlib-js.klib" \
				-Xir-produce-klib-file -ir-output-dir "$work/klib.$i" -ir-output-name probes \
				-Xir-module-name=probes &&
				"$kc/bin/kotlinc-js" -nowarn -libraries "$kc/lib/kotlin-stdlib-js.klib" \
					-Xir-produce-js -Xinclude="$work/klib.$i/probes.klib" -ir-output-dir "$work/out.$i" \
					-ir-output-name probes -main call -module-kind plain
		elif [ -f "$kc/lib/kotlin-stdlib-js.klib" ]; then
			"$kc/bin/kotlinc-js" -nowarn "$1"/*.kt -libraries "$kc/lib/kotlin-stdlib-js.klib" \
				-Xir-produce-js -ir-output-dir "$work/out.$i" -ir-output-name probes -main call \
				-module-kind plain
		elif [ "$version" = 1.8.22 ]; then
			"$kc/bin/kotlinc-js" -nowarn "$1"/*.kt -libraries "$kc/lib/kotlin-stdlib-js.jar" \
				-Xir-produce-js -ir-output-dir "$work/out.$i" -ir-output-name probes -main call \
				-module-kind plain
		else
			"$kc/bin/kotlinc-js" -nowarn "$1"/*.kt -libraries "$kc/lib/kotlin-stdlib-js.jar" \
				-Xir-produce-js -output "$work/out.$i/probes.js" -main call -module-kind plain
		fi
		;;
	esac
}

compile_stage() {
	: > "$work/exclude.tsv"
	i=0
	while [ "$i" -lt 40 ]; do
		i=$((i + 1))
		tool gen "$probes" "$work/src.$i" "$work/exclude.tsv" || return 1
		mkdir -p "$work/out.$i"
		start=$(date +%s)
		if compile "$work/src.$i" > "$work/compile.$i.log" 2>&1; then
			echo "$i" > "$work/final"
			echo "compile $i ok in $(($(date +%s) - start)) s"
			return 0
		fi
		echo "compile $i failed in $(($(date +%s) - start)) s"
		tool errors "$probes" "$work/compile.$i.log" "$work/exclude.tsv" || return 1
	done
	return 1
}

separate_stage() {
	: > "$work/exclude.sep.tsv"
	n=0
	for id in $(tool ids "$probes" /dev/null); do
		n=$((n + 1))
		tool gen "$probes" "$work/sep.$n" /dev/null "$id" > /dev/null || return 1
		mkdir -p "$work/out.sep.$n"
		i="sep.$n"
		compile "$work/sep.$n" > "$work/sep.$n.log" 2>&1 \
			|| tool errors "$probes" "$work/sep.$n.log" "$work/exclude.sep.tsv" > /dev/null
	done
	sort "$work/exclude.tsv" > "$work/exclude.sorted.tsv"
	sort "$work/exclude.sep.tsv" > "$work/exclude.sep.sorted.tsv"
	if cmp -s "$work/exclude.sorted.tsv" "$work/exclude.sep.sorted.tsv"; then
		echo "separate compile matches the elimination loop ($n probes)"
	else
		echo "separate compile DIFFERS from the elimination loop"
		diff "$work/exclude.sorted.tsv" "$work/exclude.sep.sorted.tsv"
	fi
}

run_stage() {
	n=$(cat "$work/final")
	case "$target" in
	jvm)
		java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$work/out.$n:$stdlib" \
			ladder.MainKt > "$work/run.out" 2> "$work/run.err"
		;;
	linux | macos)
		: > "$work/exits.tsv"
		"$work/out.$n/probes.kexe" > "$work/run.out" 2> "$work/run.err" || {
			: > "$work/run.out"
			for id in $(tool ids "$probes" "$work/exclude.tsv"); do
				"$work/out.$n/probes.kexe" "$id" >> "$work/run.out" 2>> "$work/run.err" ||
					printf '%s\t%s\n' "$id" "$?" >> "$work/exits.tsv"
			done
		}
		;;
	js-legacy)
		unzip -p "$kc/lib/kotlin-stdlib-js.jar" kotlin.js > "$work/out.$n/kotlin.js"
		node -e "global.kotlin = require('$work/out.$n/kotlin.js'); require('$work/out.$n/probes.js')" \
			> "$work/run.out" 2> "$work/run.err"
		;;
	js-ir) node "$work/out.$n/probes.js" > "$work/run.out" 2> "$work/run.err" ;;
	esac
	echo "run exit $?"
}

case "$stage" in
compile) compile_stage ;;
separate) separate_stage ;;
run) run_stage ;;
assemble)
	tool assemble "$probes" "${LADDER_LABEL:-$version}" "$target" "$work/run.out" \
		"$work/exclude.tsv" "$work/transcript.txt" "$work/exits.tsv"
	;;
esac
