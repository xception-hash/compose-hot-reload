#!/usr/bin/env bash
set -euo pipefail

export PKG=dev.hotreload.capture
unset JAVA_HOME
source "$(dirname "$0")/../scripts/env.sh"

FIXTURE="$REPO_ROOT/e2e/fixtures/configured-capture"
SOURCE="$FIXTURE/feature/src/main/kotlin/dev/hotreload/capture/feature/CaptureCard.kt"
FEATURE_CLASSES="$FIXTURE/feature/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
WATCH_LOG=$(mktemp)
SOURCE_BACKUP=$(mktemp)
cp "$SOURCE" "$SOURCE_BACKUP"

kill_watch() {
    if [ -n "${WATCH_PID:-}" ]; then kill -9 "$WATCH_PID" 2>/dev/null || true; fi
    pkill -9 -f "dev.hotreload.cli.MainKt" 2>/dev/null || true
}

cleanup() {
    local exit_code=$?
    kill_watch
    cp "$SOURCE_BACKUP" "$SOURCE"
    if [ "$exit_code" -ne 0 ]; then cp "$WATCH_LOG" /tmp/t39-configured-capture-last.log; fi
    rm -f "$SOURCE_BACKUP" "$WATCH_LOG"
}
trap cleanup EXIT

wait_for() {
    local pattern="$1" timeout="${2:-90}" started=$(date +%s)
    until grep -q "$pattern" "$WATCH_LOG"; do
        if (( $(date +%s) - started > timeout )); then
            echo "TIMEOUT waiting for $pattern"; tail -n 80 "$WATCH_LOG"; exit 1
        fi
        sleep 1
    done
}

assert_ui() {
    local expected="$1" started=$(date +%s)
    until adb exec-out uiautomator dump /dev/tty 2>/dev/null | LC_ALL=C grep -q "$expected"; do
        if (( $(date +%s) - started > 15 )); then
            echo "UI missing: $expected"; tail -n 80 "$WATCH_LOG"; exit 1
        fi
        sleep 1
    done
}

tap_capture() {
    local dump bounds x1 y1 x2 y2
    dump=$(adb exec-out uiautomator dump /dev/tty 2>/dev/null)
    bounds=$(echo "$dump" | LC_ALL=C grep -oE 'text="Capture count: [^"]*"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | LC_ALL=C grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
    [ -n "$bounds" ] || { echo "capture button not found"; exit 1; }
    read -r x1 y1 x2 y2 <<< "$(echo "$bounds" | tr '[],' '   ')"
    adb shell input tap "$(( (x1 + x2) / 2 ))" "$(( (y1 + y2) / 2 ))"
}

capture_count() {
    adb exec-out uiautomator dump /dev/tty 2>/dev/null |
        LC_ALL=C grep -oE 'text="Capture count: [0-9]+"' |
        head -1 |
        LC_ALL=C grep -oE '[0-9]+'
}

assert_capture_increment() {
    local before expected
    before=$(capture_count)
    [ -n "$before" ] || { echo "capture count missing"; exit 1; }
    expected=$((before + 1))
    tap_capture
    assert_ui "Capture count: $expected"
}

hot_swap_count() {
    grep -c 'hot-swapped:' "$WATCH_LOG" || true
}

wait_for_hotswap() {
    local previous_count="$1" started=$(date +%s)
    until [ "$(hot_swap_count)" -gt "$previous_count" ]; do
        if (( $(date +%s) - started > 90 )); then
            echo "hot swap timed out"; tail -n 80 "$WATCH_LOG"; exit 1
        fi
        sleep 1
    done
}

feature_output_hash() {
    find "$FEATURE_CLASSES" -name '*.class' -type f -print0 |
        sort -z |
        xargs -0 shasum -a 256 |
        shasum -a 256 |
        awk '{print $1}'
}

scripts/emulator-up.sh
kill_watch
echo "--- Installing configured capture fixture ---"
adb uninstall "$PKG" >/dev/null 2>&1 || true
"$REPO_ROOT/gradlew" -p "$FIXTURE" -q :app:installDebug
[ -d "$FEATURE_CLASSES" ] || { echo "library output missing"; exit 1; }
FEATURE_ITEM_CLASS="$FEATURE_CLASSES/dev/hotreload/capture/feature/CaptureCardKt\$captureItem\$1.class"
[ -f "$FEATURE_ITEM_CLASS" ] || { echo "captured item class missing"; exit 1; }
javap -v -p "$FEATURE_ITEM_CLASS" | LC_ALL=C grep -q 'androidx.compose.runtime.internal.FunctionKeyMeta' || {
    echo "configured Compose library is missing FunctionKeyMeta"; exit 1;
}
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 2
INITIAL_PID=$(adb shell pidof "$PKG" || true)
[ -n "$INITIAL_PID" ] || { echo "capture fixture did not launch"; exit 1; }

echo "--- Starting configured capture watch ---"
(cd "$REPO_ROOT" && ./gradlew -q :cli:run --args="watch --project $FIXTURE --app-id $PKG --module app,feature") >"$WATCH_LOG" 2>&1 &
WATCH_PID=$!
wait_for '^watching '

echo "--- Editing capture-heavy library composable (first save) ---"
assert_capture_increment
HOTSWAPS=$(hot_swap_count)
BEFORE=$(feature_output_hash)
perl -pi -e 's/Capture baseline:/Capture changed one:/' "$SOURCE"
wait_for_hotswap "$HOTSWAPS"
AFTER=$(feature_output_hash)
[ "$BEFORE" != "$AFTER" ] || { echo "library output did not change after first save"; exit 1; }
# The edited composable's own remember state may reset on targeted invalidation. What matters for
# this regression is that the visible body change lands and the post-swap lazy-item callback can
# still use its captured state without a type/capture failure.
assert_ui 'Capture changed one:'
assert_capture_increment

echo "--- Editing capture-heavy library composable (second save) ---"
HOTSWAPS=$(hot_swap_count)
BEFORE=$(feature_output_hash)
perl -pi -e 's/Capture changed one:/Capture changed two:/' "$SOURCE"
wait_for_hotswap "$HOTSWAPS"
AFTER=$(feature_output_hash)
[ "$BEFORE" != "$AFTER" ] || { echo "library output did not change after second save"; exit 1; }
assert_ui 'Capture changed two:'
assert_capture_increment
[ "$(adb shell pidof "$PKG")" = "$INITIAL_PID" ] || { echo "PID changed"; exit 1; }
! grep -q 'recomposition failed:' "$WATCH_LOG" || { tail -n 80 "$WATCH_LOG"; exit 1; }
echo "PASS: configured library capture hot swaps"
