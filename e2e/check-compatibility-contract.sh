#!/usr/bin/env bash
# Fast, dependency-free guard for the Phase 10 CI/documentation contract.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

fail() {
    echo "compatibility contract failed: $*" >&2
    exit 1
}

require_file() {
    [ -f "$1" ] || fail "missing $1"
}

require_text() {
    local file="$1"
    local text="$2"
    grep -Fq -- "$text" "$file" || fail "$file is missing: $text"
}

WORKFLOW=.github/workflows/e2e.yml
DOC=docs/project-configuration.md
RUNNER=e2e/run-zero-touch.sh
GATE=e2e/run-zero-touch-compatibility-gate.sh
AGP8=e2e/fixtures/zero-touch/agp8
AGP9=e2e/fixtures/zero-touch/agp9

require_file "$WORKFLOW"
require_file "$DOC"
require_file "$RUNNER"
require_file "$GATE"
require_file "$AGP8/build.gradle.kts"
require_file "$AGP8/settings.gradle.kts"
require_file "$AGP9/build.gradle.kts"
require_file "$AGP9/settings.gradle.kts"

# Every third-party action in this workflow must remain locked to a full commit SHA.
awk '
    /^[[:space:]]*-[[:space:]]+uses:[[:space:]]*/ {
        value = $0
        sub(/.*uses:[[:space:]]*/, "", value)
        if (value !~ /@[0-9a-f]{40}([[:space:]]|$)/) {
            print value
            bad = 1
        }
    }
    END { exit bad }
' "$WORKFLOW" || fail "workflow contains an unpinned action"

for text in \
    'compatibility:' \
    "java-version: '17'" \
    "java-version: '21'" \
    'JAVA17_HOME' \
    'e2e/run-zero-touch.sh' \
    'api-level: 36'; do
    require_text "$WORKFLOW" "$text"
done

# The offline gate and its skip-detection guard live in the gate script rather than
# inline in the workflow (android-emulator-runner runs each line of a multi-line
# `script:` block as an independent `sh -c` process, so an exit code captured on one
# line can't survive to a later line — see e2e/run-zero-touch-compatibility-gate.sh).
for text in \
    'ZERO_TOUCH_OFFLINE=1' \
    'AGP 8/JDK 17 leg was skipped'; do
    require_text "$GATE" "$text"
done

# Assert each fixture contract explicitly so a fixture rename or a lost standalone-Kotlin
# dependency is noisy.
require_text "$AGP8/build.gradle.kts" 'version "8.12.3"'
require_text "$AGP8/build.gradle.kts" 'org.jetbrains.kotlin.android'
require_text "$AGP8/build.gradle.kts" 'org.jetbrains.kotlin.jvm'
require_text "$AGP8/applications/phone/build.gradle.kts" 'create("internal")'
require_text "$AGP8/applications/phone/build.gradle.kts" 'create("stage")'
require_text "$AGP8/settings.gradle.kts" 'project(":mobile").projectDir = file("applications/phone")'
require_text "$AGP9/build.gradle.kts" 'version "9.2.1"'
require_text "$AGP9/build.gradle.kts" 'kotlin-gradle-plugin:2.4.0'
require_text "$AGP9/settings.gradle.kts" 'project(":mobile").projectDir = file("applications/mobile")'

for text in \
    'run_inspect "$AGP9_PROJECT"' \
    'run_configure "$AGP9_PROJECT"' \
    'start_zero_touch "$AGP9_PROJECT"' \
    'run_inspect "$AGP8_PROJECT"' \
    'run_configure "$AGP8_PROJECT"' \
    'start_watch "$AGP8_PROJECT"' \
    'assert_tree_clean'; do
    require_text "$RUNNER" "$text"
done

require_text e2e/run.sh 'args="start --project $REPO_ROOT/samples/single-module --app-id dev.hotreload.sample"'
require_text e2e/run.sh 'PASS: configured start fixture path'

for text in \
    '## Compatibility matrix' \
    'AGP 9.2.1, built-in Kotlin 2.4.0, JDK 21' \
    'AGP 8.12.3, standalone Kotlin Gradle Plugin 2.3.20, JDK 17' \
    'Single and multi-module projects' \
    'Gradle path differs from physical directory' \
    'Configured integration' \
    'Zero-touch integration' \
    'Groovy build scripts are not' \
    'Only one device is supported'; do
    require_text "$DOC" "$text"
done

echo 'PASS: compatibility CI/docs contract'
