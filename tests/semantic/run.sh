#!/bin/bash
# tests/semantic/run.sh -- SEMANTIC conformance tests for dex2jvm.
#
# The structural gates (tools/verify: G6 branch targets, G7 stack depth, G8
# HotSpot's real verifier) all answer "is this a well-formed class file". None
# of them answers "does it compute the same thing". A converter can emit a
# perfectly verifiable class that returns the wrong number, takes the wrong
# branch, or catches the wrong exception, and every structural gate passes it.
#
# So this suite RUNS BOTH SIDES and diffs the output, with javac as the oracle:
#
#   Case.java --javac--> .class --------------------------- run -> REFERENCE
#             --javac--> .class --d8/r8--> dex --dex2jvm--> run -> ACTUAL
#
# Any difference is a real miscompile. Three modes, because they expose
# different bugs:
#
#   desugar     a normal d8 build. A failure here hits real APKs.
#   nodesugar   d8 --no-desugaring: keeps invokedynamic, interface static and
#               private methods, and nestmate private access intact.
#   r8          minified + optimized, which is what shipping apps are. R8
#               re-points constructor calls at superclass constructors, strips
#               InnerClasses/EnclosingMethod, flattens packages and renames --
#               inputs d8 never produces.
#
# The converted classes run with bytecode verification ON (the JVM default), so
# a case also fails if HotSpot's verifier rejects what we emitted.
#
# Each mode's jar is also checked by PrivAudit (tools/verify): a cross-class
# private reference only throws when the instruction EXECUTES (JVMS 5.4.3 makes
# resolution lazy), so a case can pass the diff while still carrying a latent
# IllegalAccessError on a branch it did not take.
#
# A case may opt OUT of a mode with a first-line marker `// dexsem: no-<mode>`.
# The only legitimate reason is that its OUTPUT depends on names or metadata the
# mode destroys (R8 renames classes and strips Signature/InnerClasses).
#
# Cases must print DETERMINISTIC output: no identity hash codes, no HashMap
# iteration order, no timing. Under r8 also avoid printing class names.
#
# Requirements: JDK 21+ (JAVA_HOME or java on PATH) and the Android SDK
# command-line tools for d8/r8 (ANDROID_HOME or ANDROID_SDK_ROOT).
#
#   tests/semantic/run.sh [CaseName ...]
#   DEX2JVM_TEST_MODES='desugar r8' tests/semantic/run.sh

set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/tests/semantic/cases"
WORK="${DEX2JVM_TEST_WORK:-$ROOT/build/semantic}"

if [ -n "${JAVA_HOME:-}" ]; then JH="$JAVA_HOME/bin"; else JH="$(dirname "$(command -v java)")"; fi
[ -x "$JH/javac" ] || { echo "need a JDK 21+ (set JAVA_HOME)"; exit 2; }
# R8 needs a JDK HOME (one with jmods / lib/modules) as its library.
JDK_HOME="$(cd "$JH/.." && pwd)"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
find_tool() {   # find_tool d8|r8
    local t
    for t in "$SDK/cmdline-tools/latest/bin/$1" $(ls -d "$SDK"/build-tools/*/"$1" 2>/dev/null | sort -V | tail -1); do
        [ -x "$t" ] && { echo "$t"; return; }
    done
}
D8="$(find_tool d8)"; R8="$(find_tool r8)"
[ -n "$D8" ] || { echo "no d8 found: set ANDROID_HOME to an Android SDK with cmdline-tools or build-tools"; exit 2; }

rm -rf "$WORK"; mkdir -p "$WORK/ref" "$WORK/bin"

echo "== building dex2jvm + verify tools =="
"$JH/javac" --release 21 -nowarn -d "$WORK/bin" \
    $(find "$ROOT/src/main/java" "$ROOT/tools/verify/src" -name '*.java') || exit 2
cp -R "$ROOT/src/main/resources/." "$WORK/bin/"

CASES=("$@")
if [ ${#CASES[@]} -eq 0 ]; then
    for f in "$SRC"/*.java; do CASES+=("$(basename "$f" .java)"); done
fi

echo "== javac (oracle) =="
# -g and -parameters match what a real Android build passes.
"$JH/javac" -parameters -g -nowarn -d "$WORK/ref" "$SRC"/*.java || exit 2
for c in "${CASES[@]}"; do
    "$JH/java" -cp "$WORK/ref" "$c" > "$WORK/$c.ref.txt" 2> "$WORK/$c.ref.err" \
        || { echo "ORACLE ITSELF FAILED for $c:"; head -3 "$WORK/$c.ref.err"; exit 2; }
done

MODES="${DEX2JVM_TEST_MODES:-desugar nodesugar r8}"
PG="$WORK/keep.pro"
{
    for c in "${CASES[@]}"; do
        echo "-keep class $c { public static void main(java.lang.String[]); }"
    done
    # No -allowaccessmodification: it would publicize the very private members
    # these cases exist to exercise.
} > "$PG"

mode_skips() { grep -q "^// dexsem: no-$2\b" "$SRC/$1.java" 2>/dev/null; }

PRIVFAIL=0
for mode in $MODES; do
    mkdir -p "$WORK/dex_$mode" "$WORK/out_$mode"
    case "$mode" in
      desugar)   "$D8" --min-api 26 --output "$WORK/dex_$mode" "$WORK/ref"/*.class 2>&1 | sed "s/^/   [$mode] /" ;;
      nodesugar) "$D8" --no-desugaring --min-api 26 --output "$WORK/dex_$mode" "$WORK/ref"/*.class 2>&1 | sed "s/^/   [$mode] /" ;;
      r8)
        if [ -z "$R8" ]; then echo "   [r8] no r8 found -- skipping mode"; continue; fi
        "$R8" --release --min-api 26 --lib "$JDK_HOME" --pg-conf "$PG" \
              --output "$WORK/dex_$mode" "$WORK/ref"/*.class 2>&1 | sed "s/^/   [$mode] /"
        ;;
      *) echo "unknown mode $mode"; exit 2 ;;
    esac
    [ -f "$WORK/dex_$mode/classes.dex" ] || { echo "no dex produced for $mode"; exit 2; }
    "$JH/java" -Xmx2g -cp "$WORK/bin" io.github.kksimp.dex2jvm.Main \
        "$WORK/dex_$mode/classes.dex" "$WORK/$mode.jar" 2>&1 | sed "s/^/   [$mode] /"
    [ -s "$WORK/$mode.jar" ] || { echo "converter produced no jar for $mode"; exit 2; }
    ( cd "$WORK/out_$mode" && "$JH/jar" xf "$WORK/$mode.jar" )

    pa=$("$JH/java" -cp "$WORK/bin" io.github.kksimp.dex2jvm.verify.PrivAudit "$WORK/$mode.jar" 2>&1)
    echo "$pa" | grep -q '^RESULT: PASS' || {
        echo "$pa" | sed "s/^/   [$mode] PRIVAUDIT /"
        PRIVFAIL=$((PRIVFAIL + 1))
    }
done

note=""
judge() {
    local c=$1 mode=$2 rc
    note=""
    "$JH/java" -cp "$WORK/out_$mode" "$c" > "$WORK/$c.$mode.txt" 2> "$WORK/$c.$mode.err"
    rc=$?
    if [ "$rc" -ne 0 ]; then
        note="$(head -1 "$WORK/$c.$mode.err" | sed 's/^Exception in thread "main" //' | cut -c1-92)"
        echo "FAIL"
    elif diff -q "$WORK/$c.ref.txt" "$WORK/$c.$mode.txt" >/dev/null; then
        echo "MATCH"
    else
        note="$(diff "$WORK/$c.ref.txt" "$WORK/$c.$mode.txt" | grep -c '^<') line(s) differ"
        echo "MISMATCH"
    fi
}

FAIL=0
RUN_MODES=""
for m in $MODES; do [ -f "$WORK/dex_$m/classes.dex" ] && RUN_MODES="$RUN_MODES $m"; done
hdr=$(printf "%-10s" "CASE"); for m in $RUN_MODES; do hdr="$hdr$(printf '%-11s' "$(echo "$m" | tr 'a-z' 'A-Z')")"; done
printf "\n%s%s\n" "$hdr" "NOTE"
for c in "${CASES[@]}"; do
    row=$(printf "%-10s" "$c"); msg=""
    for m in $RUN_MODES; do
        if mode_skips "$c" "$m"; then row="$row$(printf '%-11s' "n/a")"; continue; fi
        v=$(judge "$c" "$m")
        row="$row$(printf '%-11s' "$v")"
        [ -z "$msg" ] && msg="$note"
        [ "$v" != MATCH ] && FAIL=$((FAIL+1))
    done
    printf "%s%s\n" "$row" "$msg"
done

echo ""
echo "cases: ${#CASES[@]}   failing checks: $FAIL   (outputs in $WORK/<Case>.<mode>.txt)"
if [ "$PRIVFAIL" -ne 0 ]; then
    echo "access-control audit FAILED in $PRIVFAIL mode(s): a latent IllegalAccessError was emitted"
    FAIL=$((FAIL + PRIVFAIL))
fi
[ "$FAIL" -eq 0 ] && echo "RESULT: PASS" || echo "RESULT: FAIL"
exit "$FAIL"
