#!/usr/bin/env bash
# Boot the pinned AVD if no device is connected; block until fully booted.
set -euo pipefail
source "$(dirname "$0")/env.sh"

if adb get-state >/dev/null 2>&1; then
    echo "device already connected: $(adb get-serialno)"
else
    nohup "$EMULATOR" -avd "$AVD" -no-boot-anim -no-audio >/tmp/emulator.log 2>&1 &
    echo "booting $AVD ..."
fi
adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
echo "BOOTED"
