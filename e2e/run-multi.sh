#!/usr/bin/env bash
set -euo pipefail
export PKG=dev.hotreload.multisample
source "$(dirname "$0")/../scripts/env.sh"

START_TIME=$(date +%s)
PASSED=0
TOTAL=4

echo "--- Booting emulator ---"
"$(dirname "$0")/../scripts/emulator-up.sh"

echo "--- Installing app ---"
adb shell am force-stop dev.hotreload.multisample >/dev/null 2>&1 || true
(cd "$REPO_ROOT/samples/multi-module" && ./gradlew -q :app:installDebug)

echo "--- Launching app ---"
adb shell am start -n dev.hotreload.multisample/.MainActivity >/dev/null
sleep 3
INITIAL_PID=$(adb shell pidof dev.hotreload.multisample || echo DEAD)
if [ "$INITIAL_PID" = "DEAD" ]; then
    echo "App failed to start!"
    exit 1
fi
echo "Initial PID: $INITIAL_PID"

CORE_LABEL="$REPO_ROOT/samples/multi-module/core/src/main/kotlin/dev/hotreload/multisample/core/CoreLabel.kt"
FEATURE_CARD="$REPO_ROOT/samples/multi-module/feature/src/main/kotlin/dev/hotreload/multisample/feature/FeatureCard.kt"
STRINGS="$REPO_ROOT/samples/multi-module/feature/src/main/res/values/strings.xml"
# NOT under res/: AGP's resource merger rejects any non-.xml file under res/values.
STRINGS_BACKUP=$(mktemp)

cp "$STRINGS" "$STRINGS_BACKUP"
WATCH_LOG=$(mktemp)

kill_watch() {
    if [ -n "${WATCH_PID:-}" ]; then
        kill -9 "$WATCH_PID" 2>/dev/null || true
    fi
    # -9: SIGTERM leaves the JVM alive ~2s past script exit, which flakes an
    # immediate "pgrep empty" acceptance/CI check.
    pkill -9 -f "dev.hotreload.cli.MainKt" 2>/dev/null || true
}

cleanup() {
    echo "--- Cleaning up ---"
    kill_watch
    # Restore every edited file to its committed content.
    (cd "$REPO_ROOT" && git checkout -- \
        samples/multi-module/core/src/main/kotlin/dev/hotreload/multisample/core/CoreLabel.kt \
        samples/multi-module/feature/src/main/kotlin/dev/hotreload/multisample/feature/FeatureCard.kt \
        samples/multi-module/feature/src/main/res/values/strings.xml \
    ) 2>/dev/null || true
    rm -f "$STRINGS_BACKUP"
    rm -f "$WATCH_LOG"
}
trap cleanup EXIT

# Pre-flight: a watcher leaked from an earlier run would double-compile and
# double-swap everything this run does.
kill_watch

# --- Helper: tap a clickable node whose bounds enclose a given text ---
# The multi-module sample's buttons have labels like "App count: 0" and
# "Feature count: 0" — taps.sh looks for "Count" which matches the
# single-module layout. Here we locate the button by its text prefix via
# uiautomator dump and tap its center.
tap_button() {
    local text_prefix="$1"
    local count="${2:-1}"
    local dump
    dump=$(adb exec-out uiautomator dump /dev/tty 2>/dev/null)
    local bounds
    bounds=$(echo "$dump" \
        | LC_ALL=C grep -oE "text=\"${text_prefix}[^\"]*\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" \
        | LC_ALL=C grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
    [ -z "$bounds" ] && { echo "button '$text_prefix' not found"; exit 1; }
    local X1 Y1 X2 Y2
    read -r X1 Y1 X2 Y2 <<< "$(echo "$bounds" | tr '[],' '   ')"
    local X=$(( (X1 + X2) / 2 )); local Y=$(( (Y1 + Y2) / 2 ))
    for _ in $(seq "$count"); do adb shell input tap "$X" "$Y"; sleep 0.4; done
}

echo "--- Starting watch loop ---"
(cd "$REPO_ROOT" && ./gradlew -q :cli:run --args="watch --project $REPO_ROOT/samples/multi-module --app-id dev.hotreload.multisample --module app,feature,core") > "$WATCH_LOG" 2>&1 &
WATCH_PID=$!

echo "Waiting for 'watching ' in log..."
TIMEOUT=120
START=$(date +%s)
while ! grep -q "watching " "$WATCH_LOG"; do
    if (( $(date +%s) - START > TIMEOUT )); then
        echo "TIMEOUT waiting for watch loop to start."
        cat "$WATCH_LOG"
        exit 1
    fi
    sleep 1
done
echo "Watch loop ready."

wait_for_hotswap() {
    local initial_count="$1"
    local timeout=60
    local start=$(date +%s)
    while true; do
        local current_count=$(grep -c "hot-swapped:" "$WATCH_LOG" || true)
        if (( current_count > initial_count )); then
            break
        fi
        if (( $(date +%s) - start > timeout )); then
            echo "TIMEOUT waiting for hot-swapped:"
            tail -n 20 "$WATCH_LOG"
            exit 1
        fi
        sleep 1
    done
}

wait_for_resource_swap() {
    local initial_count="$1"
    local timeout=90   # includes an assembleDebug
    local start=$(date +%s)
    while true; do
        local current_count=$(grep -c "resource-swapped:" "$WATCH_LOG" || true)
        if (( current_count > initial_count )); then
            break
        fi
        if (( $(date +%s) - start > timeout )); then
            echo "TIMEOUT waiting for resource-swapped:"
            tail -n 20 "$WATCH_LOG"
            exit 1
        fi
        sleep 1
    done
}

wait_for_log() {
    local pattern="$1"
    local initial_count="$2"
    local timeout="${3:-60}"
    local start=$(date +%s)
    while true; do
        local current_count=$(grep -c "$pattern" "$WATCH_LOG" || true)
        if (( current_count > initial_count )); then
            break
        fi
        if (( $(date +%s) - start > timeout )); then
            echo "TIMEOUT waiting for log: $pattern"
            tail -n 20 "$WATCH_LOG"
            exit 1
        fi
        sleep 1
    done
}

assert_ui() {
    local expected="$1"
    local timeout=10
    local start=$(date +%s)
    while ! adb exec-out uiautomator dump /dev/tty 2>/dev/null | LC_ALL=C grep -q "$expected"; do
        if (( $(date +%s) - start > timeout )); then
            echo "TIMEOUT waiting for UI state: $expected"
            adb exec-out uiautomator dump /dev/tty 2>/dev/null | LC_ALL=C grep -oE 'text="[^"]+"' | sort -u
            echo "Watch log tail:"
            tail -n 20 "$WATCH_LOG"
            exit 1
        fi
        sleep 1
    done
}

assert_pid() {
    local current_pid=$(adb shell pidof dev.hotreload.multisample || echo "DEAD")
    if [ "$current_pid" != "$INITIAL_PID" ]; then
        echo "PID changed! Expected $INITIAL_PID, got $current_pid"
        exit 1
    fi
}

echo "--- Running test cases ---"

# Case 1: multi-core-body
# Tap App counter 2x, then edit core's label string. Expect whole-tree
# invalidation (no compose keys in a pure-Kotlin module) AND UI shows the
# edit AND state (App count: 2) is preserved through InvalidateAll.
echo "Running case: multi-core-body"
tap_button "App count:" 2
hotswap_count=$(grep -c "hot-swapped:" "$WATCH_LOG" || true)
perl -pi -e 's/"core says:/"core edit:/' "$CORE_LABEL"
wait_for_hotswap "$hotswap_count"
# Core is pure Kotlin (no compose keys) → expect whole tree invalidation.
grep -q "whole tree invalidated (no compose keys)" "$WATCH_LOG" \
    || { echo "FAIL: expected 'whole tree invalidated (no compose keys)' in watch log"; tail -n 20 "$WATCH_LOG"; exit 1; }
assert_ui 'core edit:'
assert_ui 'App count: 2'
assert_pid
PASSED=$((PASSED + 1))
echo "PASS: multi-core-body"

# Case 2: multi-feature-composable
# Tap the library-owned control, then edit FeatureCard's label. This lazy item
# lambda captures both the state-backed count and its separately-created callback.
# Expect targeted group invalidation (NOT whole-tree), the visible edit, and the
# captured state to remain type-correct and intact.
echo "Running case: multi-feature-composable"
tap_button "Feature count:"
assert_ui 'Feature count: 1'
hotswap_count=$(grep -c "hot-swapped:" "$WATCH_LOG" || true)
FEATURE_CLASS=$(find "$REPO_ROOT/samples/multi-module/feature/build/intermediates/built_in_kotlinc" -type f -name 'FeatureCardKt.class' | head -1)
[ -n "$FEATURE_CLASS" ] || { echo "FAIL: feature Kotlin output missing before edit"; exit 1; }
FEATURE_CLASS_HASH=$(shasum -a 256 "$FEATURE_CLASS" | awk '{print $1}')
perl -pi -e 's/"FeatureCard: /"FeatureX: /' "$FEATURE_CARD"
wait_for_hotswap "$hotswap_count"
# A watched library edit must update its own Kotlin output. This guards against relying on
# :app:compileDebugKotlin to refresh an independently changed library.
FEATURE_CLASS_HASH_AFTER=$(shasum -a 256 "$FEATURE_CLASS" | awk '{print $1}')
[ "$FEATURE_CLASS_HASH" != "$FEATURE_CLASS_HASH_AFTER" ] \
    || { echo "FAIL: feature Kotlin output did not change after its watched edit"; tail -n 20 "$WATCH_LOG"; exit 1; }
# Feature is an Android/Compose module → expect targeted invalidation.
# Check for 'groups invalidated' in log lines AFTER the latest hot-swap.
grep -q "groups invalidated" "$WATCH_LOG" \
    || { echo "FAIL: expected 'groups invalidated' in watch log"; tail -n 20 "$WATCH_LOG"; exit 1; }
assert_ui 'FeatureX:'
assert_ui 'Feature count: 1'
assert_pid
PASSED=$((PASSED + 1))
echo "PASS: multi-feature-composable"

# Case 3: multi-abi-interpret (T28 — was multi-abi-rebuild pre-T28)
# Edit CoreLabel.kt signature (add a default param). Pre-T28 this was a hard
# "cannot hot-swap / signatures changed" refusal; since T28 the whole-batch rule
# primes + interprets the changed classes across modules (this exact edit was
# T28 Checkpoint B) — expect "primed:" + "interpreted:", same PID, UI intact.
# Then revert: the class is primed now, so the revert ALSO routes through the
# interpreter (another "interpreted:", not "no bytecode changes").
echo "Running case: multi-abi-interpret"
interp_count=$(grep -c "interpreted:" "$WATCH_LOG" || true)
perl -pi -e 's/fun coreLabel\(n: Int\)/fun coreLabel(n: Int, suffix: String = "")/' "$CORE_LABEL"
wait_for_log "interpreted:" "$interp_count"
grep -q "primed:.*CoreLabelKt" "$WATCH_LOG" \
    || { echo "FAIL: expected a 'primed:' line naming CoreLabelKt"; tail -n 20 "$WATCH_LOG"; exit 1; }
# UI still renders through the interpreted classes (case 1's edited text).
assert_ui 'core edit:'
assert_pid
interp_count=$(grep -c "interpreted:" "$WATCH_LOG" || true)
# Revert: restore to the edited state from case 1 (core edit:), NOT the original.
perl -pi -e 's/fun coreLabel\(n: Int, suffix: String = ""\)/fun coreLabel(n: Int)/' "$CORE_LABEL"
wait_for_log "interpreted:" "$interp_count"
assert_ui 'core edit:'
assert_pid
PASSED=$((PASSED + 1))
echo "PASS: multi-abi-interpret"

# Case 4: multi-resource-edit
# Edit the feature module's strings.xml value → expect "resource-swapped:" in
# the log AND the new text visible on screen.
echo "Running case: multi-resource-edit"
res_count=$(grep -c "resource-swapped:" "$WATCH_LOG" || true)
perl -pi -e 's/>feature res [^<]+</>feature res e2e</' "$STRINGS"
wait_for_resource_swap "$res_count"
assert_ui 'feature res e2e'
assert_pid
PASSED=$((PASSED + 1))
echo "PASS: multi-resource-edit"

END_TIME=$(date +%s)
echo "All $PASSED/$TOTAL cases PASS. Total time: $((END_TIME - START_TIME))s"
