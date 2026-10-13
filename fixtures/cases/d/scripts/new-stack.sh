#!/bin/sh
# usage: new-stack.sh <project dir> <logs dir>; runs inside drift-case-d-php83
set -u
proj=$1
logs=$2
mkdir -p "$logs"
run() {
    docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -e COMPOSER_HOME=/tmp/composer \
        -v "$proj":/work -w /work drift-case-d-php83 sh -c "$1"
}
ok() { grep -q 'composer-exit: 0' "$1"; }
step() {
    name=$1
    shift
    run "php -v | head -1; $*; echo \"composer-exit: \$?\"" > "$logs/new-$name.log" 2>&1
    if ! ok "$logs/new-$name.log" && grep -q 'allow-plugins' "$logs/new-$name.log"; then
        run "php -v | head -1; $* --no-plugins; echo \"composer-exit: \$?\"" > "$logs/new-$name-no-plugins.log" 2>&1
        ok "$logs/new-$name-no-plugins.log" && return 0
        return 1
    fi
    ok "$logs/new-$name.log"
}
if step 1-install composer install --no-interaction --no-progress; then used=1-install
elif step 2-update composer update --no-interaction --no-progress; then used=2-update
elif step 3-ignore-platform composer install --no-interaction --no-progress --ignore-platform-reqs; then used=3-ignore-platform
else used=none
fi
echo "install step used: $used" | tee "$logs/new-install-used.txt"
[ "$used" = none ] && exit 1
run 'vendor/bin/phpunit --no-interaction; echo "phpunit-exit: $?"' > "$logs/new-test.log" 2>&1
tail -25 "$logs/new-test.log"
