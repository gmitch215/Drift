#!/usr/bin/env bash
# usage: cold-up.sh <n> [alt]   start the here and there containers for scenario <n>
set -eu
. "$(dirname "$0")/harness.sh"

n=${1:?scenario number}
variant=${2:-}
scen="$COLD_DIR/scenarios/$n"
[ -f "$scen/scenario.env" ] || { echo "no scenario $n" >&2; exit 2; }
. "$scen/scenario.env"

mode=${MODE:-pair}
case "$mode" in
	pair) sides="here there" ;;
	single) sides=here ;;
	*) echo "scenario $n has unknown MODE $mode" >&2; exit 2 ;;
esac

[ "$HERE_IMAGE" != TBD ] || { echo "scenario $n has TBD images; pick them from docker images on $COLD_HOST" >&2; exit 2; }
if [ "$mode" = pair ]; then
	[ "$THERE_IMAGE" != TBD ] || { echo "scenario $n has TBD images; pick them from docker images on $COLD_HOST" >&2; exit 2; }
fi

if [ -n "$(cold_ssh "docker ps -q --filter name=drift-cold-")" ]; then
	echo "a cold scenario is already up; run cold-down.sh first" >&2
	exit 2
fi

stamp=$(date -u +%Y%m%dT%H%M%SZ)
name=$n${variant:+-$variant}-$stamp
run="$COLD_RUN_ROOT/$name"
mkdir -p "$run"
drift_abs="$(cold_ssh 'printf %s "$HOME"')/$COLD_DRIFT_BIN"

for side in $sides; do
	stage="$run/stage-$side/reader"
	mkdir -p "$stage/project"
	cp -R "$scen/project/." "$stage/project/"
	[ -z "$variant" ] || cp -R "$scen/$variant/." "$stage/project/"
	[ ! -d "$scen/$side" ] || cp -R "$scen/$side/." "$stage/project/"
	if [ "${COLD_README:-work}" = head ]; then
		git -C "$REPO" show HEAD:README.md > "$stage/README.md"
	else
		cp "$REPO/README.md" "$stage/README.md"
	fi
done

image_var=HERE_IMAGE; flags_var=HERE_FLAGS
for side in $sides; do
	c=$(cold_container "$n" "$side")
	if [ "$side" = there ]; then image_var=THERE_IMAGE; flags_var=THERE_FLAGS; fi
	image=$(eval "printf %s \"\$$image_var\"")
	flags=$(eval "printf %s \"\$$flags_var\"")
	cold_ssh "docker run -d --rm --name $c --network none --memory 1g --cpus 1 --storage-opt size=2G --pids-limit 256 --security-opt no-new-privileges --user 1000:1000 -e HOME=/home/reader $flags -v $drift_abs:/usr/local/bin/drift:ro $image sleep infinity" >/dev/null
	(cd "$run/stage-$side" && COPYFILE_DISABLE=1 tar --no-xattrs --uid 1000 --gid 1000 -cf - reader) | cold_ssh "docker cp - $c:/home"
done

here=$(cold_container "$n" here)
{
	printf 'scenario=%s\nvariant=%s\nmode=%s\nhost=%s\nhere_image=%s\nthere_image=%s\n' "$n" "$variant" "$mode" "$COLD_HOST" "$HERE_IMAGE" "${THERE_IMAGE:-}"
	printf 'harness_commit=%s\n' "$(git -C "$REPO" rev-parse --short HEAD)"
	printf 'drift_bin=%s\n' "$drift_abs"
	printf 'drift_version=%s\n' "$(cold_ssh "docker exec $here drift --version" 2>&1 | tr -d '\r')"
	printf 'started=%s\n' "$(date -u +%s)"
} > "$run/run.env"
printf '%s\n' "$run" > "$COLD_RUN_ROOT/current-$n"
echo "up: $name ($run)"
