#!/usr/bin/env bash
# Phase 0 spike: one-shot hot swap of a single class + targeted group invalidation.
# Run from spike/toy-app. Usage: ../scripts/hotswap.sh <fqcn> <groupKey> [--structural]
set -euo pipefail

FQCN="$1"           # e.g. dev.hotreload.toy.MainActivityKt
KEY="$2"            # FunctionKeyMeta key of the edited composable
STRUCTURAL="${3:-}" # pass --structural for added methods/fields (API 30+)

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
SDK="$HOME/Library/Android/sdk"
ADB="$SDK/platform-tools/adb"
D8="$SDK/build-tools/36.0.0/d8"
PKG=dev.hotreload.toy
OUT=$(mktemp -d)

# 1. Incremental Kotlin compile only (no packaging).
./gradlew :app:compileDebugKotlin -q

# 2. Dex just the changed class. --no-desugaring works because the app compiles
#    with -Xlambdas=class / -Xsam-conversions=class (no invokedynamic emitted).
CLS="app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/${FQCN//.//}.class"
"$D8" --no-desugaring --min-api 30 --output "$OUT" "$CLS"

# 3. Push + trigger the in-app receiver.
"$ADB" push "$OUT/classes.dex" "/sdcard/Android/data/$PKG/files/patch.dex" >/dev/null
EXTRA=""
[ "$STRUCTURAL" = "--structural" ] && EXTRA="--ez structural true"
"$ADB" shell am broadcast -n "$PKG/.PatchReceiver" -a "$PKG.PATCH" \
  --es file patch.dex --es cls "$FQCN" --ei key "$KEY" $EXTRA >/dev/null

sleep 1
"$ADB" logcat -d -s PatchReceiver HotReloadAgent ComposeBridge | tail -5
