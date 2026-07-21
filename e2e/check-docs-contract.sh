#!/usr/bin/env bash
# Offline guard: the README CLI reference must track the authoritative USAGE constant.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

fail() {
    echo "documentation contract failed: $*" >&2
    exit 1
}

USAGE=cli/src/main/kotlin/dev/hotreload/cli/Main.kt
README=README.md
GUIDE=docs/ai-project-setup.md

[ -f "$USAGE" ] || fail "missing $USAGE"
[ -f "$README" ] || fail "missing $README"
[ -f "$GUIDE" ] || fail "missing $GUIDE"

require_row() {
    local entry="$1"
    grep -Fq -- "| \`$entry" "$README" ||
        fail "README CLI reference is missing '$entry'"
}

for command in help watch prepare start doctor inspect configure 'config show'; do
    require_row "$command"
done
grep -Fq -- '`--help`' "$README" || fail "README CLI reference is missing '--help'"

# Extract every long option from the Usage constant rather than maintaining a second option list.
while IFS= read -r option; do
    [ -n "$option" ] || continue
    require_row "$option"
done < <(
    sed -n '/^Options:/,/^"""/p' "$USAGE" |
        sed -nE 's/^[[:space:]]*(--[a-z-]+).*/\1/p' |
        sort -u
)

for text in \
    'Stable Quickstart: configured Gradle plugin' \
    'Experimental zero-touch and live literals' \
    'Diagnostic escape hatch' \
    'docs/ai-project-setup.md' \
    'Read AGENTS.md and docs/ai-project-setup.md' \
    'Do not use zero-touch, live literals, or --ignore-fingerprint'; do
    grep -Fq -- "$text" "$README" "$GUIDE" ||
        fail "public onboarding is missing '$text'"
done

echo "PASS: README CLI/help and AI-guide contract"
