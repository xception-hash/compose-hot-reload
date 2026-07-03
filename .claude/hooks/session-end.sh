#!/usr/bin/env bash
# SessionEnd hook: safety net — commit WIP so nothing lives only in a dead session's context.
cd "$(dirname "$0")/../.." || exit 0
[ -n "$(git status --porcelain 2>/dev/null)" ] || exit 0
git add -A
git commit -q -m "wip: session-end auto-commit (safety net)" || true
