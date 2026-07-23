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
PLUGIN_README=intellij-plugin/README.md
IDE_SETTINGS=docs/ide-plugin-settings.md
PHASE1=docs/phase1.md
MARKETPLACE_URL='https://plugins.jetbrains.com/plugin/32850-compose-hot-reload'

[ -f "$USAGE" ] || fail "missing $USAGE"
[ -f "$README" ] || fail "missing $README"
[ -f "$GUIDE" ] || fail "missing $GUIDE"
[ -f "$PLUGIN_README" ] || fail "missing $PLUGIN_README"
[ -f "$IDE_SETTINGS" ] || fail "missing $IDE_SETTINGS"
[ -f "$PHASE1" ] || fail "missing $PHASE1"

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

for text in \
    'Definition of done' \
    'Adaptation principles' \
    'Mixed Java/Kotlin modules do not need conversion.' \
    'Non-interactive watcher pattern' \
    'Report verified, unverified, and out-of-tested-lane items separately.'; do
    grep -Fq -- "$text" "$GUIDE" ||
        fail "AI setup guide is missing '$text'"
done

for file in "$README" "$PLUGIN_README" "$IDE_SETTINGS"; do
    grep -Fq -- "$MARKETPLACE_URL" "$file" ||
        fail "$file is missing the verified Marketplace plugin link"
done

for text in \
    'bundled CLI' \
    'Refresh discovery' \
    'explicit configured profile' \
    'matching' \
    'Ready' \
    'one API-30+ device'; do
    grep -Fq -- "$text" "$README" "$PLUGIN_README" "$IDE_SETTINGS" ||
        fail "IDE onboarding is missing '$text'"
done

grep -Fq -- 'Profiles do not store `--sdk` or `--build-tools`' "$README" ||
    fail "README must explain the profile boundary for SDK/build-tools"
grep -Fq -- 'Start launches `watch`, not `start`.' "$PLUGIN_README" ||
    fail "plugin README must explain what Start launches"
grep -Fq -- 'Start launches `watch`, not `start`.' "$IDE_SETTINGS" ||
    fail "IDE settings guide must explain what Start launches"
grep -Fq -- 'This is a historical implementation record from July 2026, not a setup guide.' "$PHASE1" ||
    fail "historical Phase 1 commands must not look like current onboarding"

check_heading_spacing() {
    local file="$1"
    awk '
        /^```/ { in_fence = !in_fence }
        !in_fence && previous_heading && $0 != "" {
            printf "%s:%d: Markdown heading must be followed by a blank line\n", FILENAME, NR
            failed = 1
        }
        { previous_heading = !in_fence && $0 ~ /^#{1,6} / }
        END { exit failed }
    ' "$file" || fail "$file has inconsistent heading spacing"
}

for file in "$README" "$GUIDE" "$PLUGIN_README" "$IDE_SETTINGS" docs/project-configuration.md "$PHASE1" AGENTS.md; do
    check_heading_spacing "$file"
done

echo "PASS: README CLI/help and AI-guide contract"
