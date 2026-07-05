#!/usr/bin/env bash
# Phase 6 spike: build the AOSP LiveEdit bytecode interpreter into a single dex.
#
# Compiles third_party/tools-base deploy/agent/runtime {interpreter,liveedit,backported} +
# instrument/ReflectionHelpers as-is (only rewriting the jarjar'd ASM package back to the
# jar's real package, source-level instead of bytecode jarjar), plus the spike driver, then
# d8's everything with asm-all-9.0 into build/dex/classes.dex.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/../../scripts/env.sh"

RT="$REPO_ROOT/third_party/tools-base/deploy/agent/runtime"
SRC="$RT/src/main/java/com/android/tools/deploy"
ASM_JAR="$RT/lib/asm-all-9.0.jar"
ANNO_JAR="$RT/lib/annotations-19.0.0.jar"
ANDROID_JAR="$ANDROID_HOME/platforms/android-36/android.jar"
JAVAC="$JAVA_HOME/bin/javac"
JAR="$JAVA_HOME/bin/jar"

for f in "$ASM_JAR" "$ANNO_JAR" "$ANDROID_JAR"; do
  [ -f "$f" ] || { echo "missing: $f (run scripts/fetch-aosp-deployer.sh?)"; exit 1; }
done

BUILD="$HERE/build"
rm -rf "$BUILD"
mkdir -p "$BUILD/java/com/android/tools/deploy/instrument" "$BUILD/classes" "$BUILD/v2" "$BUILD/gen/dev/hotreload/spike" "$BUILD/dex"

# 1. Copy the AOSP sources; rewrite com.android.deploy.asm -> the jar's real package.
cp -R "$SRC/interpreter" "$SRC/liveedit" "$BUILD/java/com/android/tools/deploy/"
cp "$SRC/instrument/ReflectionHelpers.java" "$BUILD/java/com/android/tools/deploy/instrument/"
find "$BUILD/java" -name '*.java' -exec sed -i '' 's/com\.android\.deploy\.asm/org.jetbrains.org.objectweb.asm/g' {} +

# 1b. The sparse clone has only deploy/; stub the three tools/base/annotations used.
mkdir -p "$BUILD/gen/com/android/annotations"
for A in NonNull Nullable VisibleForTesting; do
  cat > "$BUILD/gen/com/android/annotations/$A.java" <<EOF
package com.android.annotations;
import java.lang.annotation.*;
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.PACKAGE})
public @interface $A {}
EOF
done

# 2. Compile the v2 target alone and embed its bytes as base64 (it must never be dexed).
"$JAVAC" --release 8 -nowarn -d "$BUILD/v2" "$HERE/target-v2/SpikeTarget.java"
B64=$(base64 -i "$BUILD/v2/dev/hotreload/spike/SpikeTarget.class" | tr -d '\n')
cat > "$BUILD/gen/dev/hotreload/spike/TargetBytes.java" <<EOF
package dev.hotreload.spike;
public final class TargetBytes { public static final String B64 = "$B64"; }
EOF

# 3. Compile AOSP sources + driver + generated TargetBytes in one shot.
find "$BUILD/java" "$BUILD/gen" "$HERE/driver" -name '*.java' > "$BUILD/sources.txt"
"$JAVAC" --release 8 -nowarn -cp "$ASM_JAR:$ANNO_JAR:$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

# 4. Dex everything (with desugaring, unlike our patch pipeline — the AOSP sources are Java).
(cd "$BUILD/classes" && "$JAR" cf "$BUILD/interp-classes.jar" .)
"$D8" --min-api 30 --lib "$ANDROID_JAR" --output "$BUILD/dex" \
  "$BUILD/interp-classes.jar" "$ASM_JAR" "$ANNO_JAR"

[ -f "$BUILD/dex/classes2.dex" ] && { echo "unexpected multidex output"; exit 1; }
echo "built $BUILD/dex/classes.dex ($(stat -f%z "$BUILD/dex/classes.dex") bytes)"
