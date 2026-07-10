#!/usr/bin/env bash
# Build the AOSP LiveEdit bytecode interpreter into the single dex the engine ships as a
# resource (engine/src/main/resources/dev/hotreload/interp.dex), injected on-device on the
# first interpreted edit of a session (T27 step 4).
#
# Derived from spike/interpreter/build.sh (which stays independent + green): same third_party
# sources, same toolchain pins, same source-level jarjar of ASM and annotation stubs — but with
# NO spike driver/target, plus the GENERATED `Proxies` lambda-proxy table (T28: produced by
# scripts/gen-proxies.sh from AOSP LambdaGenerator; the stub era ended with T28 — proxies let
# interpreted code construct restart lambdas whose constructors changed).
#
# T28 gotcha: injected dex is immutable per process — after replacing interp.dex, device apps
# must be force-stopped + relaunched before the new dex is used.
#
# Provenance (tools-base pin + this command) is recorded next to the dex in interp.dex.PROVENANCE.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/env.sh"

RT="$REPO_ROOT/third_party/tools-base/deploy/agent/runtime"
SRC="$RT/src/main/java/com/android/tools/deploy"
ASM_JAR="$RT/lib/asm-all-9.0.jar"
ANNO_JAR="$RT/lib/annotations-19.0.0.jar"
ANDROID_JAR="$ANDROID_HOME/platforms/android-36/android.jar"
JAVAC="$JAVA_HOME/bin/javac"
JAR="$JAVA_HOME/bin/jar"

OUT="$REPO_ROOT/engine/src/main/resources/dev/hotreload/interp.dex"
PROVENANCE="$OUT.PROVENANCE"

for f in "$ASM_JAR" "$ANNO_JAR" "$ANDROID_JAR"; do
  [ -f "$f" ] || { echo "missing: $f (run scripts/fetch-aosp-deployer.sh?)"; exit 1; }
done

BUILD="$REPO_ROOT/build/interp-dex"
rm -rf "$BUILD"
mkdir -p "$BUILD/java/com/android/tools/deploy/instrument" "$BUILD/classes" "$BUILD/dex" \
         "$BUILD/gen/com/android/annotations"

# 1. Copy the AOSP sources; rewrite com.android.deploy.asm -> the jar's real package (source-level
#    jarjar). liveedit/ includes the backported Map/List/Set shims.
cp -R "$SRC/interpreter" "$SRC/liveedit" "$BUILD/java/com/android/tools/deploy/"
cp "$SRC/instrument/ReflectionHelpers.java" "$BUILD/java/com/android/tools/deploy/instrument/"
# perl -i (not `sed -i ''`) for cross-platform in-place edit: BSD sed needs `-i ''` but GNU sed
# (Linux CI) treats the empty arg as the script and the s/// as a filename. perl -i is identical
# on both and performs the same substitution, so the produced dex is byte-for-byte unchanged.
find "$BUILD/java" -name '*.java' -exec perl -i -pe 's/com\.android\.deploy\.asm/org.jetbrains.org.objectweb.asm/g' {} +

# 2. The sparse clone has only deploy/; stub the three tools/base/annotations used (source-retention).
for A in NonNull Nullable VisibleForTesting; do
  cat > "$BUILD/gen/com/android/annotations/$A.java" <<EOF
package com.android.annotations;
import java.lang.annotation.*;
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.PACKAGE})
public @interface $A {}
EOF
done

# 3. The generated Proxies lambda-proxy table (T28, scripts/gen-proxies.sh). It references
#    kotlin-stdlib types (Lambda, Function0..N, coroutine bases) — stdlib goes on the compile
#    classpath and d8 --classpath ONLY; device apps already ship it, it must NOT be dexed in.
GEN_PROXIES="$REPO_ROOT/third_party/generated/liveedit/Proxies.java"
[ -f "$GEN_PROXIES" ] || { echo "missing $GEN_PROXIES — run scripts/gen-proxies.sh first"; exit 1; }
KOTLIN_STDLIB=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.0" \
  -name 'kotlin-stdlib-2.4.0.jar' 2>/dev/null | head -1)
[ -n "$KOTLIN_STDLIB" ] || { echo "missing kotlin-stdlib 2.4.0 in ~/.gradle/caches"; exit 1; }
mkdir -p "$BUILD/gen/com/android/tools/deploy/liveedit"
cp "$GEN_PROXIES" "$BUILD/gen/com/android/tools/deploy/liveedit/Proxies.java"

# 4. Compile the interpreter + liveedit (+ backported) + ReflectionHelpers + stubs.
find "$BUILD/java" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
"$JAVAC" --release 8 -nowarn -cp "$ASM_JAR:$ANNO_JAR:$ANDROID_JAR:$KOTLIN_STDLIB" -d "$BUILD/classes" @"$BUILD/sources.txt"

# 5. Dex everything (with desugaring — the AOSP sources are Java) into a single dex with all of ASM.
(cd "$BUILD/classes" && "$JAR" cf "$BUILD/interp-classes.jar" .)
"$D8" --min-api 30 --lib "$ANDROID_JAR" --classpath "$KOTLIN_STDLIB" --output "$BUILD/dex" \
  "$BUILD/interp-classes.jar" "$ASM_JAR" "$ANNO_JAR"

[ -f "$BUILD/dex/classes2.dex" ] && { echo "unexpected multidex output"; exit 1; }

mkdir -p "$(dirname "$OUT")"
cp "$BUILD/dex/classes.dex" "$OUT"
SIZE=$(stat -f%z "$OUT")

PIN=$(grep -m1 -i 'tools-base' "$REPO_ROOT/third_party/PINNED.txt" 2>/dev/null || echo "see third_party/PINNED.txt")
cat > "$PROVENANCE" <<EOF
interp.dex — AOSP LiveEdit bytecode interpreter, dexed for on-device injection (T27).

Source:  third_party/tools-base deploy/agent/runtime {interpreter,liveedit,liveedit/backported}
         + instrument/ReflectionHelpers, with source-level jarjar of ASM (com.android.deploy.asm
         -> org.jetbrains.org.objectweb.asm), stub com.android.annotations {NonNull,Nullable,
         VisibleForTesting}, and the GENERATED liveedit.Proxies lambda-proxy table
         (third_party/generated/liveedit, scripts/gen-proxies.sh — T28); kotlin-stdlib 2.4.0
         on compile classpath + d8 --classpath only, NOT dexed in.
Pin:     $PIN
Toolchain: JBR javac --release 8; d8 (build-tools 36.0.0) --min-api 30, default desugaring, all of
           asm-all-9.0 folded in; single dex.
Build:   scripts/build-interp-dex.sh
Size:    $SIZE bytes
License: Apache-2.0 (AOSP) — see third_party/PINNED.txt and NOTICE.
EOF

echo "built $OUT ($SIZE bytes)"
echo "wrote $PROVENANCE"
