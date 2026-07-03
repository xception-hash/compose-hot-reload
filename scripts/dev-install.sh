#!/usr/bin/env bash
# Full baseline: build toy app, install, launch, print PID.
set -euo pipefail
source "$(dirname "$0")/env.sh"

cd "$TOY"
./gradlew :app:assembleDebug -q
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 3
echo "pid: $(adb shell pidof "$PKG")"
