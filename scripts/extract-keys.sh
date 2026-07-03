#!/usr/bin/env bash
# Print FunctionKeyMeta (key/startOffset/endOffset) for a compiled class.
# Usage: extract-keys.sh [fqcn]   (default dev.hotreload.toy.MainActivityKt)
set -euo pipefail
source "$(dirname "$0")/env.sh"

FQCN="${1:-dev.hotreload.toy.MainActivityKt}"
"$JAVAP" -v "$TOY_CLASSES/${FQCN//.//}.class" 2>/dev/null \
  | LC_ALL=C grep -A3 "FunctionKeyMeta(" | LC_ALL=C grep -E "key=|Offset="
