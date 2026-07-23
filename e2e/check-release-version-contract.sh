#!/usr/bin/env bash
# Offline release-coordinate guard. Historical task/release records are intentionally excluded.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
VERSION=0.2.0

require_text() {
    local file="$1"
    local text="$2"
    grep -Fq -- "$text" "$file" || {
        echo "release version contract failed: $file is missing $text" >&2
        exit 1
    }
}

require_text gradle-plugin/build.gradle.kts "version = \"$VERSION\""
require_text gradle-plugin/build.gradle.kts "group = \"com.github.xception-hash.compose-hot-reload\""
require_text runtime-client/lib/build.gradle.kts "version = \"$VERSION\""
require_text gradle-plugin/src/main/kotlin/dev/hotreload/gradle/HotReloadPlugin.kt "runtime-client:$VERSION"
require_text intellij-plugin/gradle.properties "pluginVersion=$VERSION"
require_text README.md "id(\"dev.hotreload\") version \"$VERSION\""
require_text README.md "**$VERSION — next unified release (in validation).**"
require_text scripts/verify-release-artifacts.sh "usage: \$0 <version> <mavenLocal|repository-url>"

if rg -n '0\\.1\\.8.*current unified release|current unified release.*0\\.1\\.8' \
    README.md docs/project-configuration.md intellij-plugin/README.md docs/ide-plugin-settings.md; then
    echo "release version contract failed: stale current-release wording" >&2
    exit 1
fi

echo "PASS: 0.2.0 release version contract"
