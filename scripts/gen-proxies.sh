#!/usr/bin/env bash
# Generate the real lambda-proxy lookup table (Proxies.java) with AOSP's LambdaGenerator (T28
# step 1). Build-time codegen, fully offline: javapoet + kotlin-stdlib + the embeddable Kotlin
# compiler come from ~/.gradle/caches; the generator itself compiles against the interp build's
# relocated-ASM classes (research: docs/phase6-interpreter-research.md §7).
#
# Output: third_party/generated/liveedit/Proxies.java (+ PROVENANCE) — consumed by
# scripts/build-interp-dex.sh in place of the former stub.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/env.sh"

RT="$REPO_ROOT/third_party/tools-base/deploy/agent/runtime"
GEN_SRC="$RT/src/main/java/com/android/tools/deploy/codegen/LambdaGenerator.kt"
ASM_JAR="$RT/lib/asm-all-9.0.jar"
ANNO_JAR="$RT/lib/annotations-19.0.0.jar"   # org.jetbrains.annotations, compiler-process dep
INTERP_CLASSES="$REPO_ROOT/build/interp-dex/classes"

OUT_DIR="$REPO_ROOT/third_party/generated/liveedit"
OUT="$OUT_DIR/Proxies.java"
PROVENANCE="$OUT_DIR/PROVENANCE"

find_jar() { # group artifact version
  local hit
  hit=$(find "$HOME/.gradle/caches/modules-2/files-2.1/$1/$2/$3" -name "$2-$3.jar" 2>/dev/null | head -1)
  [ -n "$hit" ] || { echo "missing $1:$2:$3 in ~/.gradle/caches (offline build input)" >&2; exit 1; }
  echo "$hit"
}
JAVAPOET=$(find_jar com.squareup javapoet 1.13.0)
STDLIB=$(find_jar org.jetbrains.kotlin kotlin-stdlib 2.4.0)
KOTLINC=$(find_jar org.jetbrains.kotlin kotlin-compiler-embeddable 2.4.0)
# Runtime deps of the compiler PROCESS only (not inputs to the generated code); newest cached.
REFLECT=$(find_jar org.jetbrains.kotlin kotlin-reflect 2.3.21)
COROUTINES=$(find_jar org.jetbrains.kotlinx kotlinx-coroutines-core-jvm 1.9.0)

[ -f "$GEN_SRC" ] || { echo "missing $GEN_SRC (run scripts/fetch-aosp-deployer.sh?)"; exit 1; }

# LambdaGenerator imports ProxyClass/ProxyClassHandler/SourceLocationAware + relocated ASM —
# reuse the interp build's compiled classes (same relocation).
[ -d "$INTERP_CLASSES" ] || "$HERE/build-interp-dex.sh"

BUILD="$REPO_ROOT/build/gen-proxies"
rm -rf "$BUILD"
mkdir -p "$BUILD/out" "$OUT_DIR"

# Same source-level jarjar as build-interp-dex.sh (source imports com.android.deploy.asm).
cp "$GEN_SRC" "$BUILD/LambdaGenerator.kt"
sed -i '' 's/com\.android\.deploy\.asm/org.jetbrains.org.objectweb.asm/g' "$BUILD/LambdaGenerator.kt"

"$JAVA_HOME/bin/java" -cp "$KOTLINC:$STDLIB:$REFLECT:$COROUTINES:$ANNO_JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -classpath "$STDLIB:$JAVAPOET:$ASM_JAR:$INTERP_CLASSES" \
  -d "$BUILD/out" "$BUILD/LambdaGenerator.kt" 2>&1 | grep -v '^warning:' || true
[ -f "$BUILD/out/com/android/tools/deploy/codegen/LambdaGeneratorKt.class" ] || {
  echo "generator compile failed"; exit 1; }

# main(args[0]) writes the output path; it ALSO drops a stray Proxies.java in CWD — run in BUILD.
(cd "$BUILD" && "$JAVA_HOME/bin/java" \
  -cp "$BUILD/out:$JAVAPOET:$STDLIB:$ASM_JAR:$INTERP_CLASSES" \
  com.android.tools.deploy.codegen.LambdaGeneratorKt "$OUT")

CLASSES=$(grep -c "static class" "$OUT")
[ "$CLASSES" -ge 140 ] || { echo "suspicious output: only $CLASSES nested classes"; exit 1; }

PIN=$(grep -m1 -i 'tools-base' "$REPO_ROOT/third_party/PINNED.txt" 2>/dev/null || echo "see third_party/PINNED.txt")
cat > "$PROVENANCE" <<EOF
Proxies.java — generated lambda-proxy lookup table for the LiveEdit interpreter (T28).

Generator: third_party/tools-base .../codegen/LambdaGenerator.kt (asm relocated to
           org.jetbrains.org.objectweb.asm to match our interp build), run offline.
Inputs:    javapoet 1.13.0, kotlin-stdlib 2.4.0 (reflected over at generation time),
           kotlin-compiler-embeddable 2.4.0, interp build classes.
Pin:       $PIN
Build:     scripts/gen-proxies.sh
Classes:   $CLASSES nested proxy classes
Date:      $(date +%Y-%m-%d)
License:   Apache-2.0 (AOSP) — see third_party/PINNED.txt and NOTICE.
EOF

echo "wrote $OUT ($CLASSES nested classes)"
echo "wrote $PROVENANCE"
