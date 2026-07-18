#!/usr/bin/env bash
set -euo pipefail

export PKG=dev.hotreload.capture
unset JAVA_HOME
source "$(dirname "$0")/../scripts/env.sh"

FIXTURE="$REPO_ROOT/e2e/fixtures/configured-capture"
SOURCE="$FIXTURE/feature/src/main/kotlin/dev/hotreload/capture/feature/CaptureCard.kt"
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

scripts/emulator-up.sh
kill_watch
echo "--- Installing configured capture fixture ---"
adb uninstall "$PKG" >/dev/null 2>&1 || true
"$REPO_ROOT/gradlew" -p "$FIXTURE" -q :app:installDebug
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 2
INITIAL_PID=$(adb shell pidof "$PKG" || true)
[ -n "$INITIAL_PID" ] || { echo "capture fixture did not launch"; exit 1; }

echo "--- Starting configured capture watch ---"
(cd "$REPO_ROOT" && ./gradlew -q :cli:run --args="watch --project $FIXTURE --app-id $PKG --module app,feature") >"$WATCH_LOG" 2>&1 &
WATCH_PID=$!
wait_for '^watching '

echo "--- Editing capture-heavy library composable ---"
tap_capture
assert_ui 'Capture count: 1'
HOTSWAPS=$(grep -c 'hot-swapped:' "$WATCH_LOG" || true)
FEATURE_CLASS=$(find "$FIXTURE/feature/build/intermediates/built_in_kotlinc" -name CaptureCardKt.class -type f | head -1)
[ -n "$FEATURE_CLASS" ] || { echo "library output missing"; exit 1; }
BEFORE=$(shasum -a 256 "$FEATURE_CLASS" | awk '{print $1}')
perl -pi -e 's/Capture baseline:/Capture changed:/' "$SOURCE"

started=$(date +%s)
until [ "$(grep -c 'hot-swapped:' "$WATCH_LOG" || true)" -gt "$HOTSWAPS" ]; do
    if (( $(date +%s) - started > 90 )); then echo "hot swap timed out"; tail -n 80 "$WATCH_LOG"; exit 1; fi
    sleep 1
done
AFTER=$(shasum -a 256 "$FEATURE_CLASS" | awk '{print $1}')
[ "$BEFORE" != "$AFTER" ] || { echo "library output did not change"; exit 1; }
# The edited composable's own remember state may reset on targeted invalidation. What matters for
# this regression is that the visible body change lands and the post-swap lazy-item callback can
# still use its captured state without a type/capture failure.
assert_ui 'Capture changed:'
tap_capture
assert_ui 'Capture changed: 1'
assert_ui 'Capture count: 1'
[ "$(adb shell pidof "$PKG")" = "$INITIAL_PID" ] || { echo "PID changed"; exit 1; }
! grep -q 'recomposition failed:' "$WATCH_LOG" || { tail -n 80 "$WATCH_LOG"; exit 1; }
echo "PASS: configured library capture hot swap"
