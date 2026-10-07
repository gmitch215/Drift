#!/usr/bin/env sh
set -u
out="${1:-fixtures/transcripts}"

capture() {
    tool="$1"
    label="$2"
    image="$3"
    shift 3
    mkdir -p "$out/$tool"
    docker pull -q "$image" > /dev/null 2>&1
    docker run --rm "$image" sh -c "$*" > "$out/$tool/$label.txt" 2>&1 || echo "exit $?" >> "$out/$tool/$label.txt"
}

for v in 18 20 24; do capture node "node-$v" "node:$v-alpine" 'node --version'; done
for v in 3.9 3.12 3.13; do capture python "python-$v" "python:$v-alpine" 'python3 --version'; done
for v in 7.4 8.1 8.4; do capture php "php-$v" "php:$v-cli-alpine" 'php -v'; done
for v in 1.21 1.24; do capture go "go-$v" "golang:$v-alpine" 'go version'; done
for v in 8 21 25; do capture java "temurin-$v" "eclipse-temurin:$v-jre" 'java -version'; done
for v in 3.18 3.20 3.22; do capture cc "alpine-$v" "alpine:$v" 'apk add --no-cache gcc >/dev/null 2>&1; gcc --version'; done
capture git "alpine-3.20" "alpine:3.20" 'apk add --no-cache git >/dev/null 2>&1; git --version'
capture rust "rust-1.80" "rust:1.80-slim" 'rustc --version'
capture coreutils "debian-bookworm" "debian:bookworm-slim" 'ls --version'
capture coreutils "alpine-3.20" "alpine:3.20" 'ls --version'
for v in 3.18 3.20 3.22; do capture libc "musl-alpine-$v" "alpine:$v" 'ldd --version'; done
capture libc "glibc-debian-bookworm" "debian:bookworm-slim" 'ldd --version'
capture libc "glibc-ubuntu-24.04" "ubuntu:24.04" 'ldd --version'
PEP668='import os,sysconfig;print(os.path.exists(os.path.join(sysconfig.get_path("stdlib"),"EXTERNALLY-MANAGED")))'
capture python-managed "python-3.12-alpine" "python:3.12-alpine" "python3 -c '$PEP668'"
for v in 3.18 3.20 3.22; do
    capture python-managed "alpine-$v" "alpine:$v" "apk add --no-cache python3 >/dev/null 2>&1; python3 -c '$PEP668'"
done
