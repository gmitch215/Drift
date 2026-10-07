#!/bin/sh
set -eu

here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir "$work/release"
printf 'alpha' > "$work/release/a.zip"
printf 'beta' > "$work/release/b.msi"
(cd "$work/release" && sha256sum a.zip b.msi > SHA256SUMS)

export RELEASE_BASE_URL="file://$work/release"
export WAIT_ATTEMPTS=2
export WAIT_SECONDS=0

"$here/wait-assets.sh" owner/repo v1 "$work/ok" a.zip b.msi > "$work/ok.log"
grep -q 'a.zip: OK' "$work/ok.log"
grep -q 'b.msi: OK' "$work/ok.log"

if "$here/wait-assets.sh" owner/repo v1 "$work/missing" a.zip c.dmg 2> "$work/missing.err"; then
	echo "a missing asset must fail" >&2
	exit 1
fi
grep -q 'c.dmg did not become downloadable' "$work/missing.err"

printf 'tampered' > "$work/release/b.msi"
if "$here/wait-assets.sh" owner/repo v1 "$work/bad" a.zip b.msi > "$work/bad.log" 2>&1; then
	echo "a changed asset must fail" >&2
	exit 1
fi
grep -q 'b.msi: FAILED' "$work/bad.log"

printf 'x' > "$work/release/d.zip"
if "$here/wait-assets.sh" owner/repo v1 "$work/unlisted" d.zip 2> "$work/unlisted.err"; then
	echo "an unlisted asset must fail" >&2
	exit 1
fi
grep -q 'd.zip is not listed in SHA256SUMS' "$work/unlisted.err"

echo "wait-assets: all checks passed"
