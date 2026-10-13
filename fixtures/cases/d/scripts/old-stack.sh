#!/bin/sh
# usage: old-stack.sh <project dir> <logs dir>; runs inside drift-case-d-php74
set -u
proj=$1
logs=$2
mkdir -p "$logs"
run() {
    docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -e COMPOSER_HOME=/tmp/composer \
        -v "$proj":/work -w /work drift-case-d-php74 sh -c "$1"
}
run 'php -v | head -1; composer install --no-interaction --no-progress; echo "composer-exit: $?"' \
    > "$logs/old-install.log" 2>&1
if grep -q 'allow-plugins' "$logs/old-install.log" && ! grep -q 'composer-exit: 0' "$logs/old-install.log"; then
    run 'composer install --no-interaction --no-progress --no-plugins; echo "composer-exit: $?"' \
        > "$logs/old-install-no-plugins.log" 2>&1
    echo "install --no-plugins: $(tail -1 "$logs/old-install-no-plugins.log")"
else
    echo "install: $(tail -1 "$logs/old-install.log")"
fi
run 'vendor/bin/phpunit --no-interaction; echo "phpunit-exit: $?"' > "$logs/old-test.log" 2>&1
tail -8 "$logs/old-test.log"
