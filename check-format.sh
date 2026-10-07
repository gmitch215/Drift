#!/usr/bin/env sh
exec ./gradlew spotlessCheck checkLineLength "$@"
