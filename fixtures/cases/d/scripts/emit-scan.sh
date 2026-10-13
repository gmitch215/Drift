#!/bin/sh
# usage: emit-scan.sh <project dir> <image> <log>; reruns the suite with deprecations, notices and warnings made fatal
set -u
docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$1":/work -w /work "$2" sh -c '
cat > /tmp/emit.xml <<X
<?xml version="1.0" encoding="UTF-8"?>
<phpunit bootstrap="/work/vendor/autoload.php" convertDeprecationsToExceptions="true"
    convertNoticesToExceptions="true" convertWarningsToExceptions="true" convertErrorsToExceptions="true">
    <testsuites>
        <testsuite name="all"><directory>/work/test/unit</directory><directory>/work/test/functional</directory></testsuite>
    </testsuites>
</phpunit>
X
php -d error_reporting=-1 vendor/bin/phpunit --no-interaction --configuration /tmp/emit.xml; echo "phpunit-exit: $?"' > "$3" 2>&1
tail -12 "$3"
