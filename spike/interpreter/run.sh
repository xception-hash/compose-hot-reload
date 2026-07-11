#!/usr/bin/env bash
# Phase 6 spike: push the interpreter dex into the running toy app and interpret the
# embedded v2 SpikeTarget bytes on-device. Asserts "SPIKE PASS" + "SUPER PASS" in logcat,
# then asserts the DOCUMENTED CheckJNI abort for `synchronized` in interpreted bodies (T30).
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

# T30 item 1a: invokespecial (super.toString()) — interpreter_jni.cpp's invokespecialL native.
if echo "$OUT" | grep -q "SUPER PASS"; then
  echo "== invokespecial super.toString() assert PASS =="
else
  echo "== invokespecial super.toString() assert FAIL =="
  exit 1
fi

# The aggregate verdict is logged BEFORE the monitor case on purpose (that case kills the
# process — see below), so it must already be present here.
if echo "$OUT" | grep -q "SPIKE PASS"; then
  echo "== interpreter spike PASS =="
else
  echo "== interpreter spike FAIL =="
  exit 1
fi

# T30 item 1b: `synchronized` blocks in INTERPRETED bodies are DOCUMENTED-FATAL, and this
# asserts exactly that (same philosophy as the parseX=escaped:NumberFormatException assert):
# CheckJNI (force-enabled on debuggable apps: "Late-enabling -Xcheck:jni") aborts the process
# the moment JNI.enterMonitor returns while holding the monitor it just acquired. The engine
# guards this at plan() time — classes with MONITORENTER never reach the interpreter (demoted
# to Rebuild). If this assert ever flips (no abort), the platform behavior changed: revisit
# both the classifier guard and research doc §5.
sleep 2 # settle: the crash dump lands in logcat a beat after the SIGABRT itself
if adb logcat -d | grep -q "Still holding a locked object on JNI end"; then
  echo "== synchronized-in-interpreted-body FATAL (documented, T30) PASS =="
else
  echo "== synchronized-in-interpreted-body FATAL (documented, T30) FAIL: expected CheckJNI abort not found =="
  exit 1
fi
