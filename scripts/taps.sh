#!/usr/bin/env bash
# Tap the counter button N times (default 2). Finds the button via uiautomator,
# so it survives layout shifts between experiments.
set -euo pipefail
source "$(dirname "$0")/env.sh"
N="${1:-2}"

BOUNDS=$(adb exec-out uiautomator dump /dev/tty 2>/dev/null \
  | LC_ALL=C grep -oE 'text="Count[^"]*"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
  | LC_ALL=C grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
[ -z "$BOUNDS" ] && { echo "counter button not found"; exit 1; }
read -r X1 Y1 X2 Y2 <<< "$(echo "$BOUNDS" | tr '[],' '   ')"
X=$(( (X1 + X2) / 2 )); Y=$(( (Y1 + Y2) / 2 ))

for _ in $(seq "$N"); do adb shell input tap "$X" "$Y"; sleep 0.4; done
adb exec-out uiautomator dump /dev/tty 2>/dev/null | LC_ALL=C grep -oE 'text="Count[^"]*"'
