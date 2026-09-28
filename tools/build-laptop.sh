#!/usr/bin/env bash
set -eu
repo="$(cd "$(dirname "$0")/.." && pwd)"
workspace="$(cd "$repo/../../.." && pwd)"
toolchain="${MOO_TOOLCHAIN:-$workspace/Ops/Moo-Toolchain}"
export JAVA_HOME="$toolchain/jdk-21"
export ANDROID_HOME="$toolchain/android-sdk"
export GRADLE_USER_HOME="$toolchain/gradle-home"
export PATH="$JAVA_HOME/bin:$PATH"
test -x "$JAVA_HOME/bin/java"
test -f "$toolchain/moo-signing.init.gradle"
cd "$repo"
if [ "$#" -eq 0 ]; then set -- :app:assembleDebug :app:testDebugUnitTest; fi
exec bash ./gradlew "$@" --init-script "$toolchain/moo-signing.init.gradle" --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8' --console=plain
