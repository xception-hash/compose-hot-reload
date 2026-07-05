#!/usr/bin/env bash
# Phase 6 spike: push the interpreter dex into the running toy app and interpret the
# embedded v2 SpikeTarget bytes on-device. Asserts "SPIKE PASS" in logcat.
#
# Prereqs: emulator booted (scripts/emulator-up.sh), toy app installed & launched with the
# PatchReceiver "run" hook (scripts/dev-install.sh), build.sh already run.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/../../scripts/env.sh"
PKG=dev.hotreload.toy
DEX="$HERE/build/dex/classes.dex"
[ -f "$DEX" ] || { echo "run build.sh first"; exit 1; }

# Injected classes cannot be replaced in a live process — always start from a fresh one.
adb shell am force-stop "$PKG"
adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 2

adb push "$DEX" "/sdcard/Android/data/$PKG/files/interp.dex" >/dev/null
adb logcat -c
adb shell am broadcast -n "$PKG/.PatchReceiver" -a "$PKG.PATCH" \
  --es inject interp.dex --es run dev.hotreload.spike.SpikeDriver >/dev/null

sleep 3
OUT=$(adb logcat -d -s PatchReceiver | tail -20)
echo "$OUT"
if echo "$OUT" | grep -q "SPIKE PASS"; then
  echo "== interpreter spike PASS =="
else
  echo "== interpreter spike FAIL =="
  exit 1
fi
