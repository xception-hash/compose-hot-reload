#!/usr/bin/env bash
# Print the centre pixel of the composable whose contentDescription is $1
# (default HOT_ICON) as #RRGGBB. Drawables aren't text — this is the only way
# uiautomator-style checks can see them (same recipe as the T16 experiment).
set -euo pipefail
source "$(dirname "$0")/env.sh"

DESC="${1:-HOT_ICON}"
BOUNDS=$(adb exec-out uiautomator dump /dev/tty 2>/dev/null \
    | LC_ALL=C grep -oE "content-desc=\"${DESC}\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" \
    | LC_ALL=C grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
[ -z "$BOUNDS" ] && { echo "node '$DESC' not found" >&2; exit 1; }
read -r X1 Y1 X2 Y2 <<< "$(echo "$BOUNDS" | tr '[],' '   ')"
X=$(( (X1 + X2) / 2 )); Y=$(( (Y1 + Y2) / 2 ))

# Bare mktemp for BSD/GNU portability: `mktemp -t iconpixel` works on macOS
# (BSD treats it as a prefix) but GNU mktemp on the CI runner rejects a template
# without XXXXXX. No .png suffix needed — PIL sniffs content, screencap emits PNG.
PNG=$(mktemp)
adb exec-out screencap -p > "$PNG"
python3 - "$PNG" "$X" "$Y" <<'EOF'
import sys
from PIL import Image
img = Image.open(sys.argv[1]).convert("RGB")
r, g, b = img.getpixel((int(sys.argv[2]), int(sys.argv[3])))
print(f"#{r:02X}{g:02X}{b:02X}")
EOF
rm -f "$PNG"
