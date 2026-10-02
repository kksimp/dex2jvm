#!/bin/bash
# build.sh -- compile dex2jvm and its verify tools into build/.
#
#   ./build.sh            -> build/dex2jvm.jar          (java -jar build/dex2jvm.jar ...)
#                            build/dex2jvm-verify.jar   (G6/G7/G8 gates + PrivAudit)
#
# Needs only a JDK 21+ (JAVA_HOME, or javac on PATH). No other dependencies.
set -eu
ROOT="$(cd "$(dirname "$0")" && pwd)"
if [ -n "${JAVA_HOME:-}" ]; then JH="$JAVA_HOME/bin"; else JH="$(dirname "$(command -v javac)")"; fi
B="$ROOT/build"
rm -rf "$B/classes" "$B/verify-classes"
mkdir -p "$B/classes" "$B/verify-classes"
"$JH/javac" --release 21 -nowarn -d "$B/classes" $(find "$ROOT/src/main/java" -name '*.java')
cp -R "$ROOT/src/main/resources/." "$B/classes/"
"$JH/jar" --create --file "$B/dex2jvm.jar" --main-class io.github.kksimp.dex2jvm.Main -C "$B/classes" .
"$JH/javac" --release 21 -nowarn -d "$B/verify-classes" $(find "$ROOT/tools/verify/src" -name '*.java')
"$JH/jar" --create --file "$B/dex2jvm-verify.jar" -C "$B/verify-classes" .
echo "built $B/dex2jvm.jar and $B/dex2jvm-verify.jar"
