#!/usr/bin/env bash
set -euo pipefail
export PKG=dev.hotreload.sample
source "$(dirname "$0")/../scripts/env.sh"

START_TIME=$(date +%s)

echo "--- Booting emulator ---"
"$(dirname "$0")/../scripts/emulator-up.sh"

echo "--- Installing app ---"
adb shell am force-stop dev.hotreload.sample >/dev/null 2>&1 || true
(cd "$REPO_ROOT/samples/single-module" && ./gradlew -q :app:installDebug)

echo "--- Launching app ---"
adb shell monkey -p dev.hotreload.sample -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 3
INITIAL_PID=$(adb shell pidof dev.hotreload.sample || echo DEAD)
if [ "$INITIAL_PID" = "DEAD" ]; then
    echo "App failed to start!"
    exit 1
fi
echo "Initial PID: $INITIAL_PID"

MAIN_ACTIVITY="$REPO_ROOT/samples/single-module/app/src/main/kotlin/dev/hotreload/sample/MainActivity.kt"
WIDGETS="$REPO_ROOT/samples/single-module/app/src/main/kotlin/dev/hotreload/sample/Widgets.kt"
STRINGS="$REPO_ROOT/samples/single-module/app/src/main/res/values/strings.xml"
DRAWABLE="$REPO_ROOT/samples/single-module/app/src/main/res/drawable/hot_icon.xml"
HOT_PHOTO="$REPO_ROOT/samples/single-module/app/src/main/res/drawable/hot_photo.png"
HOT_PHOTO_FIXTURE="$REPO_ROOT/e2e/fixtures/hot_photo_red.png"
MAIN_ACTIVITY_BACKUP="${MAIN_ACTIVITY}.bak"
# NOT under res/: the resource merger rejects any non-.xml file in res/values.
STRINGS_BACKUP=$(mktemp)

cp "$MAIN_ACTIVITY" "$MAIN_ACTIVITY_BACKUP"
cp "$STRINGS" "$STRINGS_BACKUP"
WATCH_LOG=$(mktemp)

kill_watch() {
    # Killing the wrapper is not enough: the CLI JVM is spawned by the Gradle
    # daemon (not our shell) and survives — leaked watchers then compile and
    # swap concurrently and corrupt later runs. Kill by pattern.
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
    mv "$MAIN_ACTIVITY_BACKUP" "$MAIN_ACTIVITY" 2>/dev/null || true
    mv "$STRINGS_BACKUP" "$STRINGS" 2>/dev/null || true
    # hot_icon.xml and hot_photo.png are committed with blue baselines; cases 11/12 edit them.
    git -C "$REPO_ROOT" checkout -- "$DRAWABLE" "$HOT_PHOTO" 2>/dev/null || true
    rm -f "$WIDGETS"
    rm -f "$WATCH_LOG"
}
trap cleanup EXIT

# Pre-flight: a watcher leaked from an earlier run would double-compile and
# double-swap everything this run does.
kill_watch

echo "--- Starting watch loop ---"
(cd "$REPO_ROOT" && ./gradlew -q :cli:run --args="watch --project $REPO_ROOT/samples/single-module --app-id dev.hotreload.sample") > "$WATCH_LOG" 2>&1 &
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

assert_ui() {
    local expected="$1"
    local timeout=10
    local start=$(date +%s)
    while ! "$REPO_ROOT/scripts/ui-state.sh" | grep -q "$expected"; do
        if (( $(date +%s) - start > timeout )); then
            echo "TIMEOUT waiting for UI state: $expected"
            "$REPO_ROOT/scripts/ui-state.sh"
            echo "Watch log tail:"
            tail -n 20 "$WATCH_LOG"
            exit 1
        fi
        sleep 1
    done
}

assert_pid() {
    local current_pid=$(adb shell pidof dev.hotreload.sample || echo "DEAD")
    if [ "$current_pid" != "$INITIAL_PID" ]; then
        echo "PID changed! Expected $INITIAL_PID, got $current_pid"
        exit 1
    fi
}

echo "--- Running test cases ---"

# Case 1: body-edit
echo "Running case: body-edit"
hotswap_count=$(grep -c "hot-swapped:" "$WATCH_LOG" || true)
perl -pi -e 's/Hello from the sample app/EDITED BODY/' "$MAIN_ACTIVITY"
wait_for_hotswap "$hotswap_count"
assert_ui 'text="EDITED BODY"'
assert_pid
echo "PASS: body-edit"

# Case 2: state-preserved
echo "Running case: state-preserved (leaf edit)"
"$REPO_ROOT/scripts/taps.sh" 2 >/dev/null
hotswap_count=$(grep -c "hot-swapped:" "$WATCH_LOG" || true)
perl -pi -e 's/EDITED BODY/EDITED BODY 2/' "$MAIN_ACTIVITY"
wait_for_hotswap "$hotswap_count"
assert_ui 'text="EDITED BODY 2"'
assert_ui 'text="Count: 2 / Saved: 2"'
assert_pid
echo "PASS: state-preserved (leaf edit)"

# Case 3: structural-add
echo "Running case: structural-add"
hotswap_count=$(grep -c "hot-swapped:" "$WATCH_LOG" || true)
cat << 'EOF' >> "$MAIN_ACTIVITY"

@Composable
fun Badge() {
    Text("STRUCTURAL ADD OK")
}
EOF
perl -pi -e 's/^        Counter\(\)/        Badge()\n        Counter()/' "$MAIN_ACTIVITY"
wait_for_hotswap "$hotswap_count"
assert_ui 'text="STRUCTURAL ADD OK"'
assert_pid
echo "PASS: structural-add"

# Case 4: new-class
echo "Running case: new-class"
hotswap_count=$(grep -c "hot-swapped:" "$WATCH_LOG" || true)
cat << 'EOF' > "$WIDGETS"
package dev.hotreload.sample

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun NewFileWidget() {
    Text("NEW CLASS INJECT OK")
}
EOF
wait_for_hotswap "$hotswap_count"

hotswap_count=$(grep -c "hot-swapped:" "$WATCH_LOG" || true)
perl -pi -e 's/^        Badge\(\)/        NewFileWidget()\n        Badge()/' "$MAIN_ACTIVITY"
wait_for_hotswap "$hotswap_count"
assert_ui 'text="NEW CLASS INJECT OK"'
assert_pid
echo "PASS: new-class"

# Case 5: error-recovery
echo "Running case: error-recovery"
perl -pi -e 's/Text\("EDITED BODY 2"\)/error("e2e crash")/' "$MAIN_ACTIVITY"

# Wait for recomposition failed:
timeout=60
start_err=$(date +%s)
while ! grep -q "recomposition failed:" "$WATCH_LOG"; do
    if (( $(date +%s) - start_err > timeout )); then
        echo "TIMEOUT waiting for recomposition failed:"
        tail -n 20 "$WATCH_LOG"
        exit 1
    fi
    sleep 1
done

assert_ui 'text="EDITED BODY 2"'
assert_pid

hotswap_count=$(grep -c "hot-swapped:" "$WATCH_LOG" || true)
perl -pi -e 's/error\("e2e crash"\)/Text("EDITED BODY 2")/' "$MAIN_ACTIVITY"
wait_for_hotswap "$hotswap_count"

assert_ui 'text="EDITED BODY 2"'
assert_pid
echo "PASS: error-recovery"

# Case 6: multi-file-batch — two source files edited within the 150ms debounce
# window must coalesce into ONE compile/apply batch (SourceWatcher batching).
# Greeting() shows "EDITED BODY 2" (restored in case 5); Widgets.NewFileWidget
# shows "NEW CLASS INJECT OK" (case 4). Both edits are body-only string swaps.
echo "Running case: multi-file-batch"
hotswap_count=$(grep -c "hot-swapped:" "$WATCH_LOG" || true)
perl -pi -e 's/EDITED BODY 2/BATCH FILE A/' "$MAIN_ACTIVITY"
perl -pi -e 's/NEW CLASS INJECT OK/BATCH FILE B/' "$WIDGETS"
wait_for_hotswap "$hotswap_count"
assert_ui 'text="BATCH FILE A"'
assert_ui 'text="BATCH FILE B"'
assert_pid
# Both writes landed under 150ms: they MUST coalesce into a single hot-swap.
new_count=$(grep -c "hot-swapped:" "$WATCH_LOG" || true)
if (( new_count != hotswap_count + 1 )); then
    echo "EXPECTED one batched hot-swap (+1), got +$((new_count - hotswap_count)). Batching FAILED."
    tail -n 20 "$WATCH_LOG"
    exit 1
fi
echo "PASS: multi-file-batch"

# Case 7: rapid-saves — two saves to the SAME file ~1s apart (second lands while
# the first batch is still compiling). Session must stay healthy and settle on the
# SECOND edit's text (no dropped save), then still accept a subsequent normal edit.
echo "Running case: rapid-saves"
hotswap_count=$(grep -c "hot-swapped:" "$WATCH_LOG" || true)
perl -pi -e 's/BATCH FILE A/RAPID ONE/' "$MAIN_ACTIVITY"
sleep 1
perl -pi -e 's/RAPID ONE/RAPID TWO/' "$MAIN_ACTIVITY"
wait_for_hotswap "$hotswap_count"
assert_ui 'text="RAPID TWO"'
assert_pid
# Session not wedged: a plain follow-up edit still applies.
hotswap_count=$(grep -c "hot-swapped:" "$WATCH_LOG" || true)
perl -pi -e 's/RAPID TWO/RAPID THREE/' "$MAIN_ACTIVITY"
wait_for_hotswap "$hotswap_count"
assert_ui 'text="RAPID THREE"'
assert_pid
echo "PASS: rapid-saves"

# Case 8: resource-edit — editing a string VALUE in res/values/strings.xml overlays it
# onto the running app via ResourcesLoader + whole-tree invalidateAll (T17). The new text
# must appear WITHOUT reinstall and WITHOUT losing counter state (invalidateAll preserves
# remember AND rememberSaveable).
echo "Running case: resource-edit"
# Seed + capture the exact counter line so we can assert it is unchanged after the swap.
"$REPO_ROOT/scripts/taps.sh" 1 >/dev/null
COUNT_LINE=$(adb exec-out uiautomator dump /dev/tty 2>/dev/null \
    | LC_ALL=C grep -oE 'text="Count: [0-9]+ / Saved: [0-9]+"' | head -1)
[ -z "$COUNT_LINE" ] && { echo "could not read counter line"; exit 1; }
res_count=$(grep -c "resource-swapped:" "$WATCH_LOG" || true)
perl -pi -e 's/RESOURCE ORIGINAL/RESOURCE EDITED/' "$STRINGS"
wait_for_resource_swap "$res_count"
assert_ui 'text="RESOURCE EDITED"'
assert_ui "$COUNT_LINE"   # state preserved: same remember + rememberSaveable
assert_pid
echo "PASS: resource-edit"

# Case 9: watch-restart-resource-edit — a SECOND watch session against the SAME app
# process (no reinstall, so code_cache still holds session 1's overlays) must apply a new
# value edit. Regression for the overlay-name collision: per-session seq restarted at 1,
# `cp -r` nested into the leftover dir, and the stale session-1 arsc got re-attached.
echo "Running case: watch-restart-resource-edit"
kill_watch
: > "$WATCH_LOG"
(cd "$REPO_ROOT" && ./gradlew -q :cli:run --args="watch --project $REPO_ROOT/samples/single-module --app-id dev.hotreload.sample") > "$WATCH_LOG" 2>&1 &
WATCH_PID=$!
START=$(date +%s)
while ! grep -q "watching " "$WATCH_LOG"; do
    if (( $(date +%s) - START > TIMEOUT )); then
        echo "TIMEOUT waiting for restarted watch loop."
        cat "$WATCH_LOG"
        exit 1
    fi
    sleep 1
done
res_count=$(grep -c "resource-swapped:" "$WATCH_LOG" || true)
perl -pi -e 's/RESOURCE EDITED/RESOURCE RESTARTED/' "$STRINGS"
wait_for_resource_swap "$res_count"
assert_ui 'text="RESOURCE RESTARTED"'
assert_ui "$COUNT_LINE"   # same app process, state still intact
assert_pid
echo "PASS: watch-restart-resource-edit"

# Case 10: pending-resource-swap — one save batch with a BROKEN .kt and a valid values
# edit. The shared compile fails, so the batch applies nothing; the watcher never
# re-delivers the unchanged .xml, so the fix-and-save must retry the resource swap.
echo "Running case: pending-resource-swap"
res_count=$(grep -c "resource-swapped:" "$WATCH_LOG" || true)
fail_count=$(grep -c "compile failed" "$WATCH_LOG" || true)
perl -pi -e 's/Text\("RAPID THREE"\)/Text(E2E_UNDEFINED_SYMBOL)/' "$MAIN_ACTIVITY"
perl -pi -e 's/RESOURCE RESTARTED/RESOURCE PENDING/' "$STRINGS"
start_fail=$(date +%s)
while (( $(grep -c "compile failed" "$WATCH_LOG" || true) <= fail_count )); do
    if (( $(date +%s) - start_fail > 60 )); then
        echo "TIMEOUT waiting for compile failed"
        tail -n 20 "$WATCH_LOG"
        exit 1
    fi
    sleep 1
done
if (( $(grep -c "resource-swapped:" "$WATCH_LOG" || true) != res_count )); then
    echo "resource swap ran despite the batch's compile failure"
    tail -n 20 "$WATCH_LOG"
    exit 1
fi
perl -pi -e 's/Text\(E2E_UNDEFINED_SYMBOL\)/Text("BATCH FIXED")/' "$MAIN_ACTIVITY"
wait_for_resource_swap "$res_count"   # the fix-and-save must flush the pending swap
assert_ui 'text="BATCH FIXED"'
assert_ui 'text="RESOURCE PENDING"'
assert_pid
echo "PASS: pending-resource-swap"

# Case 11: drawable-edit — editing a vector drawable's fillColor in res/drawable/hot_icon.xml
# overlays it via ResourcesLoader AND clears Compose's per-Context asset caches
# (ImageVectorCache/ResourceIdCache) so the whole-tree recompose surfaces the new vector
# WITHOUT reinstall and WITHOUT losing counter state (drawables were tier-1'd 2026-07-05,
# see docs/resource-edits-v1.md §v2). Drawables aren't text, so we read the icon's centre
# pixel off a screencap (scripts/icon-pixel.sh) instead of uiautomator text.
echo "Running case: drawable-edit"
assert_icon() {
    local expected="$1"
    local timeout=10   # recomposition lands a frame after the swap; mirror assert_ui's retry
    local start=$(date +%s)
    local got
    while true; do
        got=$("$REPO_ROOT/scripts/icon-pixel.sh" 2>/dev/null || echo "")
        [ "$got" = "$expected" ] && break
        if (( $(date +%s) - start > timeout )); then
            echo "TIMEOUT waiting for icon pixel $expected (got '$got')"
            tail -n 20 "$WATCH_LOG"
            exit 1
        fi
        sleep 1
    done
}
# Baseline: fillColor #FF2196F3 (ARGB) renders #2196F3 on screen.
assert_icon '#2196F3'
# Seed state so the drawable swap has remember + rememberSaveable to preserve.
"$REPO_ROOT/scripts/taps.sh" 2 >/dev/null
COUNT_LINE=$(adb exec-out uiautomator dump /dev/tty 2>/dev/null \
    | LC_ALL=C grep -oE 'text="Count: [0-9]+ / Saved: [0-9]+"' | head -1)
[ -z "$COUNT_LINE" ] && { echo "could not read counter line"; exit 1; }
res_count=$(grep -c "resource-swapped:" "$WATCH_LOG" || true)
perl -pi -e 's/#FF2196F3/#FFFF0000/' "$DRAWABLE"
wait_for_resource_swap "$res_count"
assert_icon '#FF0000'         # new vector on screen, no reinstall
assert_ui "$COUNT_LINE"       # state preserved across the drawable swap
assert_pid
echo "PASS: drawable-edit"

# Case 12: bitmap-edit — SKIPPED (T23 IN-REVIEW: bitmap cache clearing pending)
# echo "Running case: bitmap-edit"
# assert_photo() {
#     local expected="$1"
#     local timeout=10
#     local start=$(date +%s)
#     local got
#     while true; do
#         got=$("$REPO_ROOT/scripts/icon-pixel.sh" "HOT_PHOTO" 2>/dev/null || echo "")
#         [ "$got" = "$expected" ] && break
#         if (( $(date +%s) - start > timeout )); then
#             echo "TIMEOUT waiting for photo pixel $expected (got '$got')"
#             tail -n 20 "$WATCH_LOG"
#             exit 1
#         fi
#         sleep 1
#     done
# }
# assert_photo '#2196F3'
# res_count=$(grep -c "resource-swapped:" "$WATCH_LOG" || true)
# cp "$HOT_PHOTO_FIXTURE" "$HOT_PHOTO"
# wait_for_resource_swap "$res_count"
# assert_photo '#FF0000'
# assert_ui "$COUNT_LINE"
# assert_pid
# echo "PASS: bitmap-edit"

# Case 13: multi-activity-resource — editing a resource while MainActivity is visible,
# then launching SecondActivity. SecondActivity must carry the updated resource
# overlay on start via ActivityTracker.onActivityResumed re-attaching the persistent loader (T22).
echo "Running case: multi-activity-resource"
res_count=$(grep -c "resource-swapped:" "$WATCH_LOG" || true)
perl -pi -e 's/RESOURCE PENDING/RESOURCE MULTI_ACT/' "$STRINGS"
wait_for_resource_swap "$res_count"
DUMP=$(adb exec-out uiautomator dump /dev/tty 2>/dev/null)
BOUNDS=$(echo "$DUMP" \
    | LC_ALL=C grep -oE 'content-desc="OPEN_SECOND"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
    | LC_ALL=C grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
[ -z "$BOUNDS" ] && { echo "OPEN_SECOND button not found"; exit 1; }
read -r X1 Y1 X2 Y2 <<< "$(echo "$BOUNDS" | tr '[],' '   ')"
X=$(( (X1 + X2) / 2 )); Y=$(( (Y1 + Y2) / 2 ))
adb shell input tap "$X" "$Y"
assert_ui 'text="RESOURCE MULTI_ACT"'
adb shell input keyevent KEYCODE_BACK
assert_pid
echo "PASS: multi-activity-resource"

END_TIME=$(date +%s)
echo "All cases PASS. Total time: $((END_TIME - START_TIME))s"
