#!/usr/bin/env bash
# Print FunctionKeyMeta (key/startOffset/endOffset) for a compiled class.
# Usage: [CLASSES_DIR=<dir>] extract-keys.sh [fqcn]   (default: toy app classes)
set -euo pipefail
source "$(dirname "$0")/env.sh"

FQCN="${1:-dev.hotreload.toy.MainActivityKt}"
CLASSES_DIR="${CLASSES_DIR:-$TOY_CLASSES}"
"$JAVAP" -v "$CLASSES_DIR/${FQCN//.//}.class" 2>/dev/null \
  | LC_ALL=C grep -A3 "FunctionKeyMeta(" | LC_ALL=C grep -E "key=|Offset="
