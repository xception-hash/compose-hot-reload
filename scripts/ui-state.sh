#!/usr/bin/env bash
# Print app PID + all visible texts (the standard verification after any swap).
set -euo pipefail
source "$(dirname "$0")/env.sh"

echo "pid: $(adb shell pidof "$PKG" || echo DEAD)"
adb exec-out uiautomator dump /dev/tty 2>/dev/null | LC_ALL=C grep -oE 'text="[^"]+"' | sort -u
