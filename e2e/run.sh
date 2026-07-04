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
MAIN_ACTIVITY_BACKUP="${MAIN_ACTIVITY}.bak"

cp "$MAIN_ACTIVITY" "$MAIN_ACTIVITY_BACKUP"
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

END_TIME=$(date +%s)
echo "All cases PASS. Total time: $((END_TIME - START_TIME))s"
