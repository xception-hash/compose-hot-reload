#!/usr/bin/env bash
# Build the AOSP LiveEdit bytecode interpreter into the single dex the engine ships as a
# resource (engine/src/main/resources/dev/hotreload/interp.dex), injected on-device on the
# first interpreted edit of a session (T27 step 4).
#
# Derived from spike/interpreter/build.sh (which stays independent + green): same third_party
# sources, same toolchain pins, same source-level jarjar of ASM and annotation stubs — but with
# NO spike driver/target, plus the stub `Proxies` the interpreter's LiveEditContext ctor requires
# (the real one is a Bazel codegen output absent from our sparse clone; we compile with
# -Xlambdas=class so new lambdas are real injected classes and the proxy machinery stays dormant).
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
find "$BUILD/java" -name '*.java' -exec sed -i '' 's/com\.android\.deploy\.asm/org.jetbrains.org.objectweb.asm/g' {} +

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

# 3. Stub the codegen'd Proxies lookup table (LiveEditContext's ctor throws if the class is absent).
mkdir -p "$BUILD/gen/com/android/tools/deploy/liveedit"
cat > "$BUILD/gen/com/android/tools/deploy/liveedit/Proxies.java" <<'EOF'
package com.android.tools.deploy.liveedit;

import java.util.Set;

// Stub for the codegen'd lambda-proxy lookup table (AOSP generates this with LambdaGenerator +
// javapoet). LiveEditContext requires the class to exist in the app classloader; we compile app
// code with -Xlambdas=class, so new lambdas are real injected classes and this stays a null lookup.
public final class Proxies {
    public static Class<?> getProxyInterface(Set<Class<?>> supertypes) {
        return null;
    }
}
EOF

# 4. Compile the interpreter + liveedit (+ backported) + ReflectionHelpers + stubs.
find "$BUILD/java" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
"$JAVAC" --release 8 -nowarn -cp "$ASM_JAR:$ANNO_JAR:$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

# 5. Dex everything (with desugaring — the AOSP sources are Java) into a single dex with all of ASM.
(cd "$BUILD/classes" && "$JAR" cf "$BUILD/interp-classes.jar" .)
"$D8" --min-api 30 --lib "$ANDROID_JAR" --output "$BUILD/dex" \
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
         VisibleForTesting}, and stub liveedit.Proxies.
Pin:     $PIN
Toolchain: JBR javac --release 8; d8 (build-tools 36.0.0) --min-api 30, default desugaring, all of
           asm-all-9.0 folded in; single dex.
Build:   scripts/build-interp-dex.sh
Size:    $SIZE bytes
License: Apache-2.0 (AOSP) — see third_party/PINNED.txt and NOTICE.
EOF

echo "built $OUT ($SIZE bytes)"
echo "wrote $PROVENANCE"
