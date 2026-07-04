#!/usr/bin/env bash
# Sparse-clone AOSP tools/base deploy/ into third_party/tools-base (gitignored, pinned).
# Reference source for the resource-edits work (docs/resource-edits-notes.md §2). No repo code changes.
# Idempotent: re-runs are a no-op that just print the pinned commit.
set -euo pipefail
source "$(dirname "$0")/env.sh"

URL="https://android.googlesource.com/platform/tools/base"
DEST="$REPO_ROOT/third_party/tools-base"
PINNED="$REPO_ROOT/third_party/PINNED.txt"

if [ -d "$DEST/.git" ]; then
  echo "tools-base already present, pinned at:"
  echo "  $(git -C "$DEST" rev-parse HEAD)"
  exit 0
fi

mkdir -p "$REPO_ROOT/third_party"
git clone --depth 1 --filter=blob:none --sparse "$URL" "$DEST"
git -C "$DEST" sparse-checkout set deploy

HASH="$(git -C "$DEST" rev-parse HEAD)"
DATE="$(date +%Y-%m-%d)"
echo "tools-base $HASH $DATE" >> "$PINNED"
echo "fetched tools-base @ $HASH (recorded in third_party/PINNED.txt)"
