#!/bin/sh
set -eu
here=$(cd "$(dirname "$0")" && pwd)
gradlew=${1:?usage: stage.sh /path/to/gradlew [out-dir]}
out=${2:-$here/stage}
work=$(mktemp -d)
cp -R "$here/build.gradle.kts" "$here/gradle.properties" "$here/settings.gradle.kts" "$here/src" "$work/"
"$gradlew" -p "$work" linkReleaseExecutableLinuxArm64 wasmJsProductionExecutableCompileSync --console=plain
mkdir -p "$out/native" "$out/wasm"
cp "$here/run.mjs" "$out/"
cp "$work/build/bin/linuxArm64/releaseExecutable/resume-offset.kexe" "$out/native/"
cp "$work"/build/compileSync/wasmJs/main/productionExecutable/optimized/resume-offset.* "$out/wasm/"
