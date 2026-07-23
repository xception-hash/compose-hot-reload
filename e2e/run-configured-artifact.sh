#!/usr/bin/env bash
# Release-candidate smoke for the stable configured path. It deliberately copies the
# existing AGP fixtures, installs the locally published artifacts through mavenLocal,
# and exercises profiles rather than relying on this checkout's composite build.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
unset JAVA_HOME
source "$REPO_ROOT/scripts/env.sh"

VERSION=0.2.0
CLI="${HOTRELOAD_CLI:-$REPO_ROOT/cli/build/install/cli/bin/cli}"
# Gradle reports the physical path on macOS (/private/var rather than /var). Keep the
# profile and its discovery cache on that same spelling so cache metadata remains usable.
FIXTURE_ROOT="$(cd "$(mktemp -d "${TMPDIR:-/tmp}/hotreload-configured-artifact.XXXXXX")" && pwd -P)"
export HOTRELOAD_CONFIG_DIR="$FIXTURE_ROOT/config"
WATCH_PID=""
WATCH_LOG=""
SERIAL=""
PACKAGES=()

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
    if [ -n "$WATCH_LOG" ]; then
        if [ "${CONFIGURED_ARTIFACT_KEEP_FIXTURES:-0}" = "1" ]; then
            cp "$WATCH_LOG" "$FIXTURE_ROOT/watch-$(basename "$WATCH_LOG").log"
        fi
        rm -f "$WATCH_LOG"
        WATCH_LOG=""
    fi
}

cleanup() {
    local package_name
    stop_watch
    for package_name in "${PACKAGES[@]}"; do
        adb -s "$SERIAL" uninstall "$package_name" >/dev/null 2>&1 || true
    done
    if [ "${CONFIGURED_ARTIFACT_KEEP_FIXTURES:-0}" = "1" ]; then
        echo "fixtures kept at: $FIXTURE_ROOT"
    else
        rm -rf "$FIXTURE_ROOT"
    fi
}
trap cleanup EXIT

echo "--- Configured packaged-artifact preflight ---"
[ -x "$JAVA_HOME/bin/java" ] || fail "JAVA_HOME does not contain bin/java: $JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1
[ -x "$ANDROID_HOME/build-tools/36.0.0/d8" ] || fail "missing build-tools 36.0.0 d8"
[ -x "$ANDROID_HOME/build-tools/36.0.0/dexdump" ] || fail "missing build-tools 36.0.0 dexdump"
[ -x "$CLI" ] || fail "CLI distribution missing at $CLI; run ./gradlew :cli:installDist first"
[ -f "$HOME/.m2/repository/dev/hotreload/dev.hotreload.gradle.plugin/$VERSION/dev.hotreload.gradle.plugin-$VERSION.pom" ] \
    || fail "Gradle plugin marker is missing from mavenLocal; publish gradle-plugin first"
[ -f "$HOME/.m2/repository/com/github/xception-hash/compose-hot-reload/runtime-client/$VERSION/runtime-client-$VERSION.aar" ] \
    || fail "runtime-client AAR is missing from mavenLocal; publish runtime-client first"

adb devices
SERIALS="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
SERIAL_COUNT="$(printf '%s\n' "$SERIALS" | awk 'NF { n++ } END { print n + 0 }')"
[ "$SERIAL_COUNT" -eq 1 ] || fail "device gate requires exactly one device in state 'device' (found $SERIAL_COUNT)"
SERIAL="$SERIALS"
API="$(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$API" =~ ^[0-9]+$ ]] && [ "$API" -ge 30 ] || fail "device API must be >= 30 (got '$API')"
echo "device: $SERIAL (API $API)"

if [ -z "${JAVA17_HOME:-}" ]; then
    fail "JAVA17_HOME is required for the AGP 8/JDK 17 configured lane"
fi
[ -x "$JAVA17_HOME/bin/java" ] && [ -x "$JAVA17_HOME/bin/javac" ] \
    || fail "JAVA17_HOME must contain bin/java and bin/javac: $JAVA17_HOME"
JAVA17_VERSION="$("$JAVA17_HOME/bin/java" -version 2>&1 | head -1)"
echo "$JAVA17_VERSION"
[[ "$JAVA17_VERSION" =~ \"17([.]|\") ]] || fail "JAVA17_HOME must be JDK 17"
"$JAVA17_HOME/bin/javac" -version

make_fixture() {
    local lane="$1"
    local target="$FIXTURE_ROOT/$lane"
    cp -R "$SCRIPT_DIR/fixtures/zero-touch/$lane/." "$target"

    # The plugin marker and injected runtime must resolve from a published repository,
    # never from this checkout's includeBuild composite.
    perl -0pi -e 's/(pluginManagement\s*\{.*?repositories\s*\{\s*)/$1mavenLocal()\n        /s' "$target/settings.gradle.kts"
    perl -0pi -e 's/(dependencyResolutionManagement\s*\{.*?repositories\s*\{\s*)/$1mavenLocal()\n        /s' "$target/settings.gradle.kts"

    case "$lane" in
        agp9)
            perl -0pi -e 's/(id\("org\.jetbrains\.kotlin\.plugin\.compose"\)\n)/$1    id("dev.hotreload") version "0.2.0"\n/' \
                "$target/applications/mobile/build.gradle.kts"
            ;;
        agp8)
            perl -0pi -e 's/(id\("org\.jetbrains\.kotlin\.plugin\.compose"\)\n)/$1    id("dev.hotreload") version "0.2.0"\n/' \
                "$target/applications/phone/build.gradle.kts" "$target/features/card/build.gradle.kts"
            perl -0pi -e 's/(id\("org\.jetbrains\.kotlin\.jvm"\)\n)/$1    id("dev.hotreload") version "0.2.0"\n/' \
                "$target/shared/domain/build.gradle.kts"
            ;;
        *) fail "unknown fixture lane: $lane" ;;
    esac

    local maven_local_count
    maven_local_count="$(grep -Fc 'mavenLocal()' "$target/settings.gradle.kts")"
    [ "$maven_local_count" -ge 2 ] || fail "$lane fixture did not add mavenLocal to both resolution blocks"
    grep -R -Fq 'id("dev.hotreload") version "0.2.0"' "$target" \
        || fail "$lane fixture did not apply the published dev.hotreload plugin"
    cp "$REPO_ROOT/gradlew" "$target/gradlew"
    cp "$REPO_ROOT/gradlew.bat" "$target/gradlew.bat"
    cp "$REPO_ROOT/gradle/wrapper/gradle-wrapper.jar" "$target/gradle/wrapper/gradle-wrapper.jar"
    chmod +x "$target/gradlew"
    git -C "$target" init -q
    git -C "$target" add .
    git -C "$target" -c user.name='Configured Artifact Fixture' -c user.email='fixture@invalid.example' \
        commit -q --no-gpg-sign -m baseline
    printf '%s\n' "$target"
}

wait_for_ready() {
    local started="$(date +%s)"
    until grep -q 'watching ' "$WATCH_LOG"; do
        if ! kill -0 "$WATCH_PID" 2>/dev/null; then
            cat "$WATCH_LOG" >&2 || true
            fail "watcher exited before readiness"
        fi
        [ $(( $(date +%s) - started )) -le 240 ] || { cat "$WATCH_LOG" >&2; fail "timeout waiting for readiness"; }
        sleep 1
    done
}

wait_for_swap() {
    local previous="$1" started="$(date +%s)"
    until [ "$(grep -Ec 'hot-swapped:|interpreted:' "$WATCH_LOG" || true)" -gt "$previous" ]; do
        [ $(( $(date +%s) - started )) -le 120 ] || { tail -n 80 "$WATCH_LOG"; fail "timeout waiting for compiled edit"; }
        sleep 1
    done
}

assert_ui() {
    local expected="$1" started="$(date +%s)"
    until adb -s "$SERIAL" exec-out uiautomator dump /dev/tty 2>/dev/null | LC_ALL=C grep -Fq "$expected"; do
        [ $(( $(date +%s) - started )) -le 20 ] || { tail -n 80 "$WATCH_LOG"; fail "UI did not contain '$expected'"; }
        sleep 1
    done
}

tap_count_twice() {
    local dump bounds x1 y1 x2 y2
    dump="$(adb -s "$SERIAL" exec-out uiautomator dump /dev/tty 2>/dev/null)"
    bounds="$(printf '%s\n' "$dump" | LC_ALL=C grep -oE 'text="Count: [^"]*"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | LC_ALL=C grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1 || true)"
    [ -n "$bounds" ] || fail "Count button not found"
    read -r x1 y1 x2 y2 <<< "$(printf '%s\n' "$bounds" | tr '[],' '   ')"
    adb -s "$SERIAL" shell input tap "$(( (x1 + x2) / 2 ))" "$(( (y1 + y2) / 2 ))"
    adb -s "$SERIAL" shell input tap "$(( (x1 + x2) / 2 ))" "$(( (y1 + y2) / 2 ))"
    assert_ui 'Count: 2'
}

run_lane() {
    local name="$1" project="$2" java_home="$3" app_id="$4" variant="$5" modules="$6" app_dir="$7" source="$8" edit_from="$9" edit_to="${10}" initial="${11}" edited="${12}"
    local profile="configured-artifact-$name" initial_pid swaps stopped_pid
    PACKAGES+=("$app_id")
    adb -s "$SERIAL" uninstall "$app_id" >/dev/null 2>&1 || true

    echo "--- Configured packaged artifact: $name ---"
    "$CLI" configure --project "$project" --app-id "$app_id" --module "$modules" \
        --app-module :mobile --app-module-dir "$app_dir" --variant "$variant" \
        --project-java-home "$java_home" --device "$SERIAL" --save-as "$profile"
    "$CLI" config show --profile "$profile" | tee "$FIXTURE_ROOT/$name-profile.log"
    grep -Fq "project-java-home = \"$java_home\"" "$FIXTURE_ROOT/$name-profile.log" \
        || fail "$name profile did not pin the target JDK"
    grep -Fq "device = \"$SERIAL\"" "$FIXTURE_ROOT/$name-profile.log" \
        || fail "$name profile did not pin the selected device"

    "$CLI" prepare --profile "$profile"
    "$CLI" doctor --profile "$profile"
    initial_pid="$(adb -s "$SERIAL" shell pidof "$app_id" | tr -d '\r')"
    [ -n "$initial_pid" ] || fail "$name app did not launch"

    WATCH_LOG="$(mktemp "${TMPDIR:-/tmp}/hotreload-configured-artifact-watch.XXXXXX")"
    "$CLI" watch --profile "$profile" >"$WATCH_LOG" 2>&1 &
    WATCH_PID=$!
    wait_for_ready
    assert_ui "$initial"
    tap_count_twice

    swaps="$(grep -Ec 'hot-swapped:|interpreted:' "$WATCH_LOG" || true)"
    perl -pi -e "s/\Q$edit_from\E/$edit_to/" "$source"
    grep -Fq "$edit_to" "$source" || fail "$name source edit did not apply"
    wait_for_swap "$swaps"
    assert_ui "$edited"
    [ "$(adb -s "$SERIAL" shell pidof "$app_id" | tr -d '\r')" = "$initial_pid" ] || fail "$name PID changed after edit"

    swaps="$(grep -Ec 'hot-swapped:|interpreted:' "$WATCH_LOG" || true)"
    git -C "$project" restore -- "$source"
    wait_for_swap "$swaps"
    assert_ui "$initial"
    [ "$(adb -s "$SERIAL" shell pidof "$app_id" | tr -d '\r')" = "$initial_pid" ] || fail "$name PID changed after reversal"

    stopped_pid="$WATCH_PID"
    stop_watch
    ! kill -0 "$stopped_pid" 2>/dev/null || fail "$name watcher remained alive after Stop"
    git -C "$project" diff --quiet || fail "$name fixture source was not restored"
    echo "PASS: configured packaged artifact $name Doctor/Ready/edit/reversal/PID/Stop"
}

AGP9_PROJECT="$(make_fixture agp9)"
run_lane agp9 "$AGP9_PROJECT" "$JAVA_HOME" dev.hotreload.fixture.agp9.qa qa \
    ':mobile=applications/mobile' applications/mobile \
    "$AGP9_PROJECT/applications/mobile/src/main/kotlin/dev/hotreload/fixture/agp9/MainActivity.kt" \
    'Text("literal: v1")' 'Text("literal: v2")' \
    'literal: v1' 'literal: v2'

AGP8_PROJECT="$(make_fixture agp8)"
run_lane agp8 "$AGP8_PROJECT" "$JAVA17_HOME" dev.hotreload.fixture.agp8.internal.stage internalStage \
    ':mobile=applications/phone,:feature=features/card,:core=shared/domain' applications/phone \
    "$AGP8_PROJECT/features/card/src/main/kotlin/dev/hotreload/fixture/agp8/feature/FeatureCard.kt" \
    'Text(coreLabel("Agp8 original"))' 'Text(coreLabel("Agp8 changed"))' \
    'Agp8 original' 'Agp8 changed'

echo "PASS: configured packaged-artifact smoke gate"
