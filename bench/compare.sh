#!/bin/bash
# bench/compare.sh -- dex2jvm vs enjarify vs dex2jar on the same APKs, judged by
# the same gates.
#
# For each APK and each converter it records:
#   classes  .class entries in the output jar
#   ms       wall-clock conversion time (JVM / Python startup included)
#   G6       tools/verify BranchTargetCheck: branch/handler targets are instruction starts
#   G7       tools/verify StackDepthCheck: stack depth agrees with HotSpot GenerateOopMap
#   G8       tools/verify VerifyCheck: classes HotSpot's split verifier REJECTS
#            (lower is better; classes it could not link for lack of a dependency
#            are reported as "missing" and are not counted either way)
#
# Requirements:
#   JAVA_HOME     JDK 21+
#   ANDROID_JAR   an android.jar (e.g. $ANDROID_HOME/platforms/android-34/android.jar),
#                 used as the library surface for dex2jvm's hierarchy oracle and for G8
#   ENJARIFY_DIR  a checkout of https://github.com/google/enjarify (python3 on PATH)
#   DEX2JAR_DIR   an unpacked dex2jar release (the dir holding d2j-dex2jar.sh)
#
#   bench/compare.sh app1.apk app2.apk ...      -> markdown table on stdout

set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JH="${JAVA_HOME:?set JAVA_HOME}/bin"
: "${ANDROID_JAR:?set ANDROID_JAR}" "${ENJARIFY_DIR:?set ENJARIFY_DIR}" "${DEX2JAR_DIR:?set DEX2JAR_DIR}"
WORK="${DEX2JVM_BENCH_WORK:-$ROOT/build/bench}"
mkdir -p "$WORK/bin"
"$JH/javac" --release 21 -nowarn -d "$WORK/bin" \
    $(find "$ROOT/src/main/java" "$ROOT/tools/verify/src" -name '*.java') || exit 2
export PATH="$JH:$PATH"

now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }

gates() {   # gates <jar> -> "G6 G7 G8 missing"
    local jar=$1 g6 g7 g8 miss v
    g6=$("$JH/java" -Xmx3g -cp "$WORK/bin" io.github.kksimp.dex2jvm.verify.BranchTargetCheck "$jar" 2>&1 | grep -oE 'RESULT: [A-Z]+' | cut -d' ' -f2)
    g7=$("$JH/java" -Xmx3g -cp "$WORK/bin" io.github.kksimp.dex2jvm.verify.StackDepthCheck "$jar" 2>&1 | grep -oE 'RESULT: [A-Z]+' | cut -d' ' -f2)
    v=$("$JH/java" -Xmx3g -cp "$WORK/bin" io.github.kksimp.dex2jvm.verify.VerifyCheck --classpath "$ANDROID_JAR" --show 0 "$jar" 2>&1 | grep '^SUMMARY')
    g8=$(echo "$v" | sed -E 's/.*verify=([0-9]+).*format=([0-9]+).*/\1+\2/' | bc 2>/dev/null)
    miss=$(echo "$v" | sed -E 's/.*missing=([0-9]+).*/\1/')
    echo "${g6:-?} ${g7:-?} ${g8:-?} ${miss:-?}"
}

run_one() {  # run_one <tool> <apk> <out.jar>
    local tool=$1 apk=$2 out=$3
    rm -f "$out"
    case "$tool" in
      dex2jvm)  "$JH/java" -Xmx4g -cp "$WORK/bin" io.github.kksimp.dex2jvm.Main --classpath "$ANDROID_JAR" "$apk" "$out" ;;
      enjarify) ( cd "$ENJARIFY_DIR" && python3 -O -m enjarify.main "$apk" -o "$out" -f ) ;;
      dex2jar)  "$DEX2JAR_DIR/d2j-dex2jar.sh" --force -o "$out" "$apk" ;;
    esac
}

echo "| app | tool | classes | ms | G6 | G7 | G8 rejected | G8 unlinkable |"
echo "|---|---|---:|---:|---|---|---:|---:|"
for apk in "$@"; do
    name=$(basename "$apk" | sed -E 's/(_[0-9].*|-[0-9].*)?\.(apk|apkm|xapk|apks)$//')
    for tool in dex2jvm enjarify dex2jar; do
        out="$WORK/$name.$tool.jar"
        t0=$(now_ms)
        run_one "$tool" "$apk" "$out" > "$WORK/$name.$tool.log" 2>&1
        t1=$(now_ms)
        if [ ! -s "$out" ]; then
            echo "| $name | $tool | (no jar) | $((t1 - t0)) | | | | |"
            continue
        fi
        n=$(unzip -l "$out" 2>/dev/null | grep -c '\.class$')
        read -r g6 g7 g8 miss <<< "$(gates "$out")"
        echo "| $name | $tool | $n | $((t1 - t0)) | $g6 | $g7 | $g8 | $miss |"
    done
done
