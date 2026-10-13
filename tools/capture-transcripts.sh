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
capture npm "node-24" "node:24-bookworm-slim" 'npm --version'
for v in 3.12 3.13; do
    capture python-gil "python-$v" "python:$v-alpine" 'python3 -c "import sysconfig;print(sysconfig.get_config_var(\"Py_GIL_DISABLED\") or 0)"'
done
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
APT='apt-get update -qq >/dev/null 2>&1; DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends'
capture openssl "alpine-3.16" "alpine:3.16" 'apk add --no-cache openssl >/dev/null 2>&1; openssl version'
capture openssl "alpine-3.20" "alpine:3.20" 'apk add --no-cache openssl >/dev/null 2>&1; openssl version'
capture openssl "alpine-3.22" "alpine:3.22" 'apk add --no-cache openssl >/dev/null 2>&1; openssl version'
capture openssl "debian-bookworm" "debian:bookworm-slim" "$APT openssl >/dev/null 2>&1; openssl version"
capture openssl "ubuntu-20.04" "ubuntu:20.04" "$APT openssl >/dev/null 2>&1; openssl version"
capture openssl "ubuntu-22.04" "ubuntu:22.04" "$APT openssl >/dev/null 2>&1; openssl version"
VAULT="sed -i -e 's/mirrorlist=/#mirrorlist=/g' -e 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/*.repo"
capture openssl "centos-7" "centos:7" "$VAULT; yum install -y -q openssl >/dev/null 2>&1; openssl version"
capture openssl-providers "alpine-3.20" "alpine:3.20" 'apk add --no-cache openssl >/dev/null 2>&1; openssl list -providers'
capture openssl-providers "debian-bookworm" "debian:bookworm-slim" "$APT openssl >/dev/null 2>&1; openssl list -providers"
capture openssl-providers "ubuntu-20.04" "ubuntu:20.04" "$APT openssl >/dev/null 2>&1; openssl list -providers"
capture ssh "alpine-3.20" "alpine:3.20" 'apk add --no-cache openssh-client >/dev/null 2>&1; ssh -V'
capture ssh "debian-bookworm" "debian:bookworm-slim" "$APT openssh-client >/dev/null 2>&1; ssh -V"
capture ssh "ubuntu-20.04" "ubuntu:20.04" "$APT openssh-client >/dev/null 2>&1; ssh -V"
capture ssh "ubuntu-22.04" "ubuntu:22.04" "$APT openssh-client >/dev/null 2>&1; ssh -V"
capture composer "composer-2" "composer:2" 'composer --version'
capture composer "composer-1" "composer:1" 'composer --version'
capture sudo "debian-bookworm" "debian:bookworm-slim" "$APT sudo >/dev/null 2>&1; sudo --version"
capture sudo "ubuntu-24.04" "ubuntu:24.04" "$APT sudo >/dev/null 2>&1; sudo --version"
capture sudo "ubuntu-26.04" "ubuntu:26.04" "$APT sudo-rs >/dev/null 2>&1; sudo --version"
capture ruby "ruby-3.3" "ruby:3.3-alpine" 'ruby --version'
capture ruby "ruby-2.7" "ruby:2.7-alpine" 'ruby --version'
capture named "alpine-3.20" "alpine:3.20" 'apk add --no-cache bind >/dev/null 2>&1; named -v'
capture named "debian-bookworm" "debian:bookworm-slim" "$APT bind9 >/dev/null 2>&1; named -v"
capture named "ubuntu-22.04" "ubuntu:22.04" "$APT bind9 >/dev/null 2>&1; named -v"
capture iptables "alpine-3.18" "alpine:3.18" 'apk add --no-cache iptables >/dev/null 2>&1; iptables --version'
capture iptables "alpine-3.20" "alpine:3.20" 'apk add --no-cache iptables >/dev/null 2>&1; iptables --version'
capture iptables "debian-bookworm" "debian:bookworm-slim" "$APT iptables >/dev/null 2>&1; iptables --version"
capture iptables "ubuntu-22.04" "ubuntu:22.04" "$APT iptables >/dev/null 2>&1; iptables --version"
capture curl "alpine-3.18" "alpine:3.18" 'apk add --no-cache curl >/dev/null 2>&1; curl --version'
capture curl "alpine-3.20" "alpine:3.20" 'apk add --no-cache curl >/dev/null 2>&1; curl --version'
capture curl "debian-bookworm" "debian:bookworm-slim" "$APT curl >/dev/null 2>&1; curl --version"
capture curl "ubuntu-22.04" "ubuntu:22.04" "$APT curl >/dev/null 2>&1; curl --version"
