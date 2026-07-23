#!/usr/bin/env bash
# Release-candidate documentation gate. It makes a fresh local clone and an independent target
# copy, then follows the public configured-profile flow against locally published 0.2.0 artifacts.
# mavenLocal is the only pre-publication substitute for the README's JitPack repository; the
# separate release-artifact gate proves those exact coordinates before this script starts.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
unset JAVA_HOME
source "$REPO_ROOT/scripts/env.sh"

VERSION=0.2.0
ROOT="$(cd "$(mktemp -d "${TMPDIR:-/tmp}/hotreload-clean-clone.XXXXXX")" && pwd -P)"
CLONE="$ROOT/compose-hot-reload"
TARGET="$ROOT/target"
CONFIG="$ROOT/config"
WATCH_LOG="$ROOT/watch.log"
WATCH_PID=""
SERIAL=""
PACKAGE=dev.hotreload.sample

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

stop_watch() {
    if [ -n "$WATCH_PID" ]; then
        kill "$WATCH_PID" 2>/dev/null || true
        wait "$WATCH_PID" 2>/dev/null || true
        WATCH_PID=""
    fi
}

cleanup() {
    stop_watch
    if [ -n "$SERIAL" ]; then
        adb -s "$SERIAL" uninstall "$PACKAGE" >/dev/null 2>&1 || true
    fi
    if [ "${CLEAN_CLONE_KEEP_FIXTURE:-0}" = 1 ]; then
        echo "clean-clone evidence kept at: $ROOT"
    else
        rm -rf "$ROOT"
    fi
}
trap cleanup EXIT

wait_for() {
    local pattern="$1" previous="$2" timeout="$3" started
    started="$(date +%s)"
    until [ "$(grep -Ec "$pattern" "$WATCH_LOG" 2>/dev/null || true)" -gt "$previous" ]; do
        if ! kill -0 "$WATCH_PID" 2>/dev/null; then
            cat "$WATCH_LOG" >&2 || true
            fail "watcher exited before '$pattern'"
        fi
        [ $(( $(date +%s) - started )) -le "$timeout" ] || {
            tail -n 100 "$WATCH_LOG" >&2 || true
            fail "timeout waiting for '$pattern'"
        }
        sleep 1
    done
}

assert_ui() {
    local expected="$1" started
    started="$(date +%s)"
    until adb -s "$SERIAL" exec-out uiautomator dump /dev/tty 2>/dev/null | LC_ALL=C grep -Fq "$expected"; do
        [ $(( $(date +%s) - started )) -le 30 ] || {
            tail -n 100 "$WATCH_LOG" >&2 || true
            fail "UI did not contain '$expected'"
        }
        sleep 1
    done
}

tap_counter() {
    local dump bounds x1 y1 x2 y2
    dump="$(adb -s "$SERIAL" exec-out uiautomator dump /dev/tty 2>/dev/null)"
    bounds="$(printf '%s\n' "$dump" | LC_ALL=C grep -oE 'text="Count: [^"]*"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | LC_ALL=C grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1 || true)"
    [ -n "$bounds" ] || fail "Count button not found"
    read -r x1 y1 x2 y2 <<< "$(printf '%s\n' "$bounds" | tr '[],' '   ')"
    adb -s "$SERIAL" shell input tap "$(( (x1 + x2) / 2 ))" "$(( (y1 + y2) / 2 ))"
}

echo "--- Clean-clone configured documentation preflight ---"
[ -x "$JAVA_HOME/bin/java" ] || fail "missing CLI JBR: $JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1
[ -x "$ANDROID_HOME/build-tools/36.0.0/d8" ] || fail "missing build-tools d8"
[ -x "$ANDROID_HOME/build-tools/36.0.0/dexdump" ] || fail "missing build-tools dexdump"
[ -f "$HOME/.m2/repository/dev/hotreload/dev.hotreload.gradle.plugin/$VERSION/dev.hotreload.gradle.plugin-$VERSION.pom" ] || fail "missing published plugin marker in mavenLocal"
[ -f "$HOME/.m2/repository/com/github/xception-hash/compose-hot-reload/runtime-client/$VERSION/runtime-client-$VERSION.aar" ] || fail "missing published runtime AAR in mavenLocal"

SERIALS="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
[ "$(printf '%s\n' "$SERIALS" | awk 'NF { n++ } END { print n + 0 }')" -eq 1 ] || fail "exactly one device is required"
SERIAL="$SERIALS"
API="$(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$API" =~ ^[0-9]+$ ]] && [ "$API" -ge 30 ] || fail "API 30+ required (got '$API')"
echo "device: $SERIAL (API $API)"

# A local clone gives release-candidate evidence without exposing ignored maintainer handoff files.
git clone --quiet --no-hardlinks "$REPO_ROOT" "$CLONE"
[ ! -e "$CLONE/.agents" ] || fail "clean clone unexpectedly contains .agents"
git -C "$CLONE" diff --check
git -C "$CLONE" status --porcelain | grep -q . && fail "clean clone is dirty"

# The target begins as an unmodified public sample. Remove its local composites, then make only the
# public README-equivalent plugin/repository change; mavenLocal substitutes for not-yet-published JitPack.
cp -R "$CLONE/samples/single-module/." "$TARGET"
perl -0pi -e 's/^\s*includeBuild\("\.\.\/\.\.\/gradle-plugin"\)\n//m; s/^includeBuild\("\.\.\/\.\.\/runtime-client"\)\n//m' "$TARGET/settings.gradle.kts"
perl -0pi -e 's/(repositories\s*\{\n)/$1        mavenLocal()\n/g' "$TARGET/settings.gradle.kts"
perl -0pi -e 's/id\("dev\.hotreload"\)/id("dev.hotreload") version "0.2.0"/' "$TARGET/app/build.gradle.kts"
cp "$CLONE/gradlew" "$CLONE/gradlew.bat" "$TARGET/"
mkdir -p "$TARGET/gradle/wrapper"
cp "$CLONE/gradle/wrapper/gradle-wrapper.jar" "$TARGET/gradle/wrapper/"
chmod +x "$TARGET/gradlew"
[ "$(grep -Fc 'mavenLocal()' "$TARGET/settings.gradle.kts")" -eq 2 ] || fail "target repository substitution missing"
grep -Fq 'id("dev.hotreload") version "0.2.0"' "$TARGET/app/build.gradle.kts" || fail "target plugin setup missing"
git -C "$TARGET" init -q
git -C "$TARGET" add .
git -C "$TARGET" -c user.name='Clean Clone Fixture' -c user.email='fixture@invalid.example' commit -q --no-gpg-sign -m baseline

(cd "$CLONE" && ./gradlew -q :cli:installDist)
CLI="$CLONE/cli/build/install/cli/bin/cli"
[ -x "$CLI" ] || fail "fresh clone did not build the CLI distribution"
export HOTRELOAD_CONFIG_DIR="$CONFIG"
"$CLI" configure --project "$TARGET" --save-as clean-clone --app-id "$PACKAGE" \
    --app-module :app --app-module-dir app --module :app=app --variant debug \
    --project-java-home "$JAVA_HOME" --device "$SERIAL"
"$CLI" config show --profile clean-clone | tee "$ROOT/profile.log"
grep -Fq "project-java-home = \"$JAVA_HOME\"" "$ROOT/profile.log" || fail "profile did not pin target JDK"
grep -Fq "device = \"$SERIAL\"" "$ROOT/profile.log" || fail "profile did not pin device"
"$CLI" prepare --profile clean-clone --launch-activity .MainActivity
"$CLI" doctor --profile clean-clone

INITIAL_PID="$(adb -s "$SERIAL" shell pidof "$PACKAGE" | tr -d '\r')"
[ -n "$INITIAL_PID" ] || fail "prepared app is not running"
"$CLI" watch --profile clean-clone >"$WATCH_LOG" 2>&1 &
WATCH_PID=$!
wait_for 'watching ' 0 240
assert_ui 'Hello from the sample app'
tap_counter
assert_ui 'Count: 1 / Saved: 1'

SOURCE="$TARGET/app/src/main/kotlin/dev/hotreload/sample/MainActivity.kt"
SWAPS="$(grep -Ec 'hot-swapped:|interpreted:' "$WATCH_LOG" || true)"
perl -pi -e 's/Hello from the sample app/Hello from the clean clone/' "$SOURCE"
wait_for 'hot-swapped:|interpreted:' "$SWAPS" 120
assert_ui 'Hello from the clean clone'
[ "$(adb -s "$SERIAL" shell pidof "$PACKAGE" | tr -d '\r')" = "$INITIAL_PID" ] || fail "PID changed after body edit"

SWAPS="$(grep -Ec 'hot-swapped:|interpreted:' "$WATCH_LOG" || true)"
git -C "$TARGET" restore -- "$SOURCE"
wait_for 'hot-swapped:|interpreted:' "$SWAPS" 120
assert_ui 'Hello from the sample app'
[ "$(adb -s "$SERIAL" shell pidof "$PACKAGE" | tr -d '\r')" = "$INITIAL_PID" ] || fail "PID changed after restoration"
stop_watch
git -C "$TARGET" diff --quiet || fail "target source was not restored"
git -C "$CLONE" diff --quiet || fail "clean clone tracked files changed during gate"
echo "PASS: clean-clone README/AI configured profile Doctor/Ready/edit/restoration/PID/Stop"
