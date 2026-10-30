#!/usr/bin/env bash
set -euo pipefail

x64=${1:?usage: simulate-release.sh <linux x64 drift> <linux arm64 drift> [work dir]}
arm64=${2:?linux arm64 drift}
work=${3:-${TMPDIR:-/tmp}/drift-release-sim}
root=$(cd "$(dirname "$0")/.." && pwd)
version=$(grep '^version=' "$root/gradle.properties" | cut -d= -f2)
failed=0

check() {
    desc=$1
    shift
    if "$@" > "$work/last.out" 2>&1; then
        echo "ok   $desc"
    else
        echo "FAIL $desc"
        sed 's/^/     /' "$work/last.out" | tail -8
        failed=1
    fi
}

mkdir -p "$work"
find "$work" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
mkdir -p "$work/release/v$version" "$work/serve" "$work/home"

for pair in "x64:$x64" "arm64:$arm64"; do
    name=${pair%%:*}
    mkdir "$work/$name"
    (cd "$work/$name" && "$root/.github/release/stage.sh" archive "${pair#*:}" "drift-$version-linux-$name.tar.gz")
    cp "$work/$name"/dist/* "$work/release/v$version/"
done
(cd "$work/release/v$version" && "$root/.github/release/stage.sh" sums .)
check "SHA256SUMS verifies every archive" sh -c "cd '$work/release/v$version' && sha256sum -c SHA256SUMS"
check "each archive holds drift, LICENSE, README.txt and the notices" sh -c "
    for a in '$work'/release/v$version/*.tar.gz; do
        tar -tzf \"\$a\" | sort | tr '\\n' ' ' | grep -q 'LICENSE README.txt THIRD-PARTY-NOTICES.txt drift' || exit 1
    done"

cat > "$work/serve.py" <<'EOF'
import http.server
import os
import sys

root, version, port = sys.argv[1], sys.argv[2], int(sys.argv[3])


class Handler(http.server.SimpleHTTPRequestHandler):
    def translate_path(self, path):
        prefix = "/Drift/releases/download/"
        if path.startswith(prefix):
            return os.path.join(root, path[len(prefix):])
        return os.path.join(root, "missing")

    def do_GET(self):
        if self.path == "/Drift/releases/latest":
            self.send_response(302)
            self.send_header("Location", "/Drift/releases/tag/v" + version)
            self.end_headers()
        elif self.path.startswith("/Drift/releases/tag/"):
            self.send_response(200)
            self.end_headers()
        else:
            super().do_GET()

    def log_message(self, *args):
        pass


http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
EOF
port=$((20000 + RANDOM % 20000))
python3 -I "$work/serve.py" "$work/release" "$version" "$port" &
server=$!
trap 'kill $server 2> /dev/null || true' EXIT
sleep 1

installed() {
    env -i HOME="$work/home" PATH="/usr/bin:/bin" SHELL=/bin/sh TERM=dumb TMPDIR="$work" \
        DRIFT_INSTALL_REPO_URL="http://127.0.0.1:$port/Drift" /bin/sh "$root/install.sh" "$@"
}

check "installer resolves the latest release through the redirect and installs it" installed --no-modify-path
check "the installed drift reports the version" sh -c "'$work/home/.local/bin/drift' --version | grep -q 'drift version $version'"
pinned() {
    DRIFT_VERSION=$version installed "$@"
}

check "a pinned version installs the same archive" pinned --no-modify-path --dry-run

why() {
    (cd "$work/home" && "$work/home/.local/bin/drift" why --error \
        "Fatal error: Uncaught Error: Call to undefined function str_contains()")
}

answered() {
    why | grep -q 'In one step: php -v | drift why --reply'
}

check "drift why answers from the installed binary" answered

if command -v docker > /dev/null 2>&1 && [ "${SKIP_DOCKER:-0}" != 1 ]; then
    studio=${STUDIO:-$work/studio}
    mkdir -p "$studio"
    [ -e "$studio/index.html" ] || echo '<!doctype html>' > "$studio/index.html"
    "$root/docker/stage.sh" "$work/ctx" "$studio" "$work/x64/dist" "$work/arm64/dist"
    check "the image builds" docker build -q --platform linux/amd64 -t drift-release-sim -f "$root/docker/Dockerfile" "$work/ctx"
    check "the image answers a one-line why as UID 10001 in its default folder" sh -c \
        "docker run --rm drift-release-sim why --error 'Fatal error: Uncaught Error: Call to undefined function str_contains()' | grep -q 'In one step'"
    check "the image keeps the session so --reply works in one container" sh -c \
        "docker run --rm --entrypoint sh drift-release-sim -c \"drift why --error 'Fatal error: Uncaught Error: Call to undefined function str_contains()' > /dev/null; echo 'PHP 7.4.33 (cli)' | drift why --reply\" | grep -q '7.4.33'"
    docker rmi drift-release-sim > /dev/null 2>&1 || true
else
    echo "skip image checks (no docker or SKIP_DOCKER=1)"
fi

exit "$failed"
