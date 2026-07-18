#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$REPO_ROOT/scripts/env.sh"

MODE="device"
if [ "${1:-}" = "--host-only" ]; then
    MODE="host"
elif [ "$#" -ne 0 ]; then
    echo "usage: $0 [--host-only]" >&2
    exit 2
fi

CLI="${HOTRELOAD_CLI:-$REPO_ROOT/cli/build/install/cli/bin/cli}"
FIXTURE_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/hotreload-zero-touch.XXXXXX")"
CONFIG_DIR="$FIXTURE_ROOT/config"
WATCH_PID=""
WATCH_LOG=""
SERIAL=""

cleanup() {
    if [ -n "$WATCH_PID" ]; then
        kill -9 "$WATCH_PID" 2>/dev/null || true
    fi
    if [ "${ZERO_TOUCH_KEEP_FIXTURES:-0}" = "1" ]; then
        echo "fixtures kept at: $FIXTURE_ROOT"
    else
        rm -rf "$FIXTURE_ROOT"
    fi
}
trap cleanup EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

echo "--- Zero-touch preflight ---"
[ -x "$JAVA_HOME/bin/java" ] || fail "JAVA_HOME does not contain bin/java: $JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1
[ -x "$ANDROID_HOME/build-tools/36.0.0/d8" ] || fail "missing build-tools 36.0.0 d8"
[ -x "$ANDROID_HOME/build-tools/36.0.0/dexdump" ] || fail "missing build-tools 36.0.0 dexdump"
[ -x "$ANDROID_HOME/build-tools/36.0.0/aapt2" ] || fail "missing build-tools 36.0.0 aapt2"
[ -x "$CLI" ] || fail "CLI distribution missing at $CLI; run ./gradlew :cli:installDist first"

if [ "$MODE" = "device" ]; then
    adb devices
    SERIALS="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
    SERIAL_COUNT="$(printf '%s\n' "$SERIALS" | awk 'NF { n++ } END { print n + 0 }')"
    [ "$SERIAL_COUNT" -eq 1 ] || fail "device gate requires exactly one device in state 'device' (found $SERIAL_COUNT)"
    SERIAL="$SERIALS"
    API="$(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
    [[ "$API" =~ ^[0-9]+$ ]] && [ "$API" -ge 30 ] || fail "device API must be >= 30 (got '$API')"
    echo "device: $SERIAL (API $API)"
fi

JAVA17_ENABLED=0
if [ -z "${JAVA17_HOME:-}" ]; then
    echo "SKIP: AGP 8/JDK 17 zero-touch fixture — set JAVA17_HOME to an installed JDK 17"
else
    [ -x "$JAVA17_HOME/bin/java" ] || fail "JAVA17_HOME does not contain bin/java: $JAVA17_HOME"
    JAVA17_VERSION="$("$JAVA17_HOME/bin/java" -version 2>&1 | head -1)"
    echo "$JAVA17_VERSION"
    [[ "$JAVA17_VERSION" =~ \"17([.]|\") ]] || fail "JAVA17_HOME must be JDK 17"
    JAVA17_ENABLED=1
fi

export HOTRELOAD_CONFIG_DIR="$CONFIG_DIR"
mkdir -p "$CONFIG_DIR"

GRADLE_ARGS=()
if [ "${ZERO_TOUCH_OFFLINE:-0}" = "1" ]; then
    GRADLE_ARGS=(--gradle-arg --offline)
fi

make_fixture() {
    local name="$1"
    local target="$FIXTURE_ROOT/$name"
    cp -R "$SCRIPT_DIR/fixtures/zero-touch/$name/." "$target"
    cp "$REPO_ROOT/gradlew" "$target/gradlew"
    cp "$REPO_ROOT/gradlew.bat" "$target/gradlew.bat"
    cp "$REPO_ROOT/gradle/wrapper/gradle-wrapper.jar" "$target/gradle/wrapper/gradle-wrapper.jar"
    chmod +x "$target/gradlew"

    # The local Git baseline models files an owner expects zero-touch mode to preserve.
    # Only ordinary Gradle output is ignored by the committed fixture .gitignore.
    git -C "$target" init -q
    git -C "$target" add .
    git -C "$target" \
        -c user.name='Zero Touch Fixture' \
        -c user.email='fixture@invalid.example' \
        commit -q --no-gpg-sign -m baseline
    printf '%s\n' "$target"
}

assert_tree_clean() {
    local project="$1"
    local phase="$2"
    local status
    status="$(git -C "$project" status --porcelain --untracked-files=all)"
    if [ -n "$status" ]; then
        echo "target tree changed during $phase:" >&2
        printf '%s\n' "$status" >&2
        git -C "$project" diff -- >&2 || true
        fail "zero-touch modified tracked target inputs"
    fi
}

assert_no_configured_integration() {
    local project="$1"
    if find "$project" -type f \( -name '*.gradle' -o -name '*.gradle.kts' \) -print0 \
        | xargs -0 grep -nE 'id[(]["]dev[.]hotreload|runtime-client|compose-hot-reload' >/dev/null 2>&1; then
        find "$project" -type f \( -name '*.gradle' -o -name '*.gradle.kts' \) -print0 \
            | xargs -0 grep -nE 'id[(]["]dev[.]hotreload|runtime-client|compose-hot-reload' >&2 || true
        fail "fixture accidentally contains configured-mode integration"
    fi
}

run_inspect() {
    local project="$1"
    local java_home="$2"
    local expected_gradle="$3"
    local expected_module="$4"
    local expected_variant="$5"
    local log="$FIXTURE_ROOT/inspect-$(basename "$project").json"

    "$CLI" inspect \
        --zero-touch \
        --project "$project" \
        --project-java-home "$java_home" \
        "${GRADLE_ARGS[@]+${GRADLE_ARGS[@]}}" \
        --json > "$log"

    grep -Eq "\"gradleVersion\"[[:space:]]*:[[:space:]]*\"$expected_gradle\"" "$log" \
        || fail "inspect did not report Gradle $expected_gradle"
    grep -Eq "\"gradlePath\"[[:space:]]*:[[:space:]]*\"$expected_module\"" "$log" \
        || fail "inspect did not report module $expected_module"
    grep -Eq "\"name\"[[:space:]]*:[[:space:]]*\"$expected_variant\"" "$log" \
        || fail "inspect did not report variant $expected_variant"
    assert_tree_clean "$project" "zero-touch inspect"
}

run_configure() {
    local project="$1"
    local java_home="$2"
    local variant="$3"
    local profile="$4"

    "$CLI" configure \
        --zero-touch \
        --project "$project" \
        --variant "$variant" \
        --project-java-home "$java_home" \
        "${GRADLE_ARGS[@]+${GRADLE_ARGS[@]}}" \
        --save-as "$profile"

    local toml="$CONFIG_DIR/projects/$profile.toml"
    local sidecar="$CONFIG_DIR/projects/$profile.discovery.json"
    [ -f "$toml" ] || fail "configure did not write profile $toml"
    grep -Fxq 'zero-touch = true' "$toml" \
        || fail "configure profile did not persist zero-touch mode"
    [ -s "$sidecar" ] || fail "configure did not write discovery sidecar $sidecar"
    assert_tree_clean "$project" "zero-touch configure"
}

apk_has_startup() {
    local apk="$1"
    local dex_dir="$FIXTURE_ROOT/dex-check-$RANDOM-$RANDOM"
    local descriptor="Class descriptor  : 'Landroidx/startup/InitializationProvider;'"

    "$ANDROID_HOME/build-tools/36.0.0/aapt2" dump xmltree "$apk" --file AndroidManifest.xml \
        | grep -F 'androidx.startup.InitializationProvider' >/dev/null \
        || return 1

    mkdir -p "$dex_dir"
    unzip -qq "$apk" 'classes*.dex' -d "$dex_dir"
    local dex
    for dex in "$dex_dir"/classes*.dex; do
        if "$ANDROID_HOME/build-tools/36.0.0/dexdump" "$dex" \
            | grep -F "$descriptor" >/dev/null; then
            return 0
        fi
    done
    return 1
}

apk_has_jacoco_init() {
    local apk="$1"
    local dex_dir="$FIXTURE_ROOT/dex-jacoco-$RANDOM-$RANDOM"
    mkdir -p "$dex_dir"
    unzip -qq "$apk" 'classes*.dex' -d "$dex_dir"
    local dex
    for dex in "$dex_dir"/classes*.dex; do
        if "$ANDROID_HOME/build-tools/36.0.0/dexdump" -s "$dex" \
            | grep -F '$jacocoInit' >/dev/null; then
            return 0
        fi
    done
    return 1
}

assert_plain_release_clean() {
    local project="$1"
    local java_home="$2"
    local task="$3"
    local apk_pattern="$4"

    local wrapper_args=("$task")
    if [ "${ZERO_TOUCH_OFFLINE:-0}" = "1" ]; then
        wrapper_args+=(--offline)
    fi
    (cd "$project" && JAVA_HOME="$java_home" ./gradlew "${wrapper_args[@]}")

    local apk
    apk="$(find "$project" -path "$apk_pattern" -type f -name '*.apk' | head -1)"
    [ -n "$apk" ] || fail "plain release APK not found under $project"
    if unzip -l "$apk" | grep 'libhotreload_agent[.]so' >/dev/null; then
        fail "runtime native agent leaked into plain release APK $apk"
    fi
    # Compose/activity dependencies may already bring an older Startup runtime into a
    # perfectly plain app. The security boundary is the hot-reload runtime/native agent,
    # which must stay absent from every non-debuggable APK.
    assert_tree_clean "$project" "plain release build"
}

extract_packaged_bootstrap() {
    local cli_root engine_jar
    cli_root="$(cd "$(dirname "$CLI")/.." && pwd)"
    engine_jar="$(find "$cli_root/lib" -maxdepth 1 -type f -name 'engine*.jar' | head -1)"
    [ -n "$engine_jar" ] || fail "installed CLI has no engine JAR under $cli_root/lib"
    BOOTSTRAP_DIR="$FIXTURE_ROOT/packaged-bootstrap"
    mkdir -p "$BOOTSTRAP_DIR"
    unzip -p "$engine_jar" dev/hotreload/bootstrap/bootstrap.jar > "$BOOTSTRAP_DIR/bootstrap.jar"
    unzip -p "$engine_jar" dev/hotreload/bootstrap/runtime-client.aar > "$BOOTSTRAP_DIR/runtime-client.aar"
    unzip -p "$engine_jar" dev/hotreload/bootstrap/zero-touch.init.gradle > "$BOOTSTRAP_DIR/zero-touch.init.gradle"
    [ -s "$BOOTSTRAP_DIR/bootstrap.jar" ] && [ -s "$BOOTSTRAP_DIR/runtime-client.aar" ] \
        && [ -s "$BOOTSTRAP_DIR/zero-touch.init.gradle" ] \
        || fail "could not extract packaged zero-touch payloads"
}

assert_instrumented_apk() {
    local project="$1"
    local variant_dir="$2"
    local apk
    apk="$(find "$project" -path "*/build/outputs/apk/$variant_dir/*" -type f -name '*.apk' | head -1)"
    [ -n "$apk" ] || fail "instrumented APK not found for $variant_dir"
    unzip -l "$apk" | grep 'libhotreload_agent[.]so' >/dev/null \
        || fail "zero-touch APK does not contain the runtime native agent"
    apk_has_startup "$apk" \
        || fail "zero-touch APK does not contain AndroidX Startup 1.2.0"
    if apk_has_jacoco_init "$apk"; then
        fail "zero-touch APK contains JaCoCo-instrumented class shapes"
    fi
}

bootstrap_gradle() {
    local project="$1"
    local java_home="$2"
    local modules="$3"
    local app_module="$4"
    local variant="$5"
    shift 5
    local args=(
        --no-configuration-cache
        --init-script "$BOOTSTRAP_DIR/zero-touch.init.gradle"
        "-Pdev.hotreload.bootstrap.jar=$BOOTSTRAP_DIR/bootstrap.jar"
        "-Pdev.hotreload.bootstrap.runtimeAar=$BOOTSTRAP_DIR/runtime-client.aar"
        "-Pdev.hotreload.bootstrap.modules=$modules"
        "-Pdev.hotreload.bootstrap.appModule=$app_module"
        "-Pdev.hotreload.bootstrap.variant=$variant"
    )
    if [ "${ZERO_TOUCH_OFFLINE:-0}" = "1" ]; then
        args+=(--offline)
    fi
    (cd "$project" && JAVA_HOME="$java_home" ./gradlew "${args[@]}" "$@")
}

assert_missing_root_bootstrap_rejected() {
    local project="$1"
    local java_home="$2"
    local log="$FIXTURE_ROOT/missing-root-bootstrap.log"

    if (cd "$project" && JAVA_HOME="$java_home" ./gradlew --no-configuration-cache \
        --init-script "$BOOTSTRAP_DIR/zero-touch.init.gradle" :mobile:help) >"$log" 2>&1; then
        fail "zero-touch init script accepted a root build without bootstrap properties"
    fi
    grep -Fq 'zero-touch bootstrap: missing dev.hotreload.bootstrap.jar' "$log" \
        || { cat "$log" >&2; fail "missing root bootstrap property did not fail loudly"; }
    assert_tree_clean "$project" "missing root bootstrap-property rejection"
}

assert_bootstrap_build() {
    local project="$1"
    local java_home="$2"
    local modules="$3"
    local app_module="$4"
    local variant="$5"
    local assemble_task="$6"
    local apk_dir="$7"

    bootstrap_gradle "$project" "$java_home" "$modules" "$app_module" "$variant" \
        -Photreload.liveLiterals=true "$assemble_task"
    assert_instrumented_apk "$project" "$apk_dir"

    local classes
    classes="$(find "$project" -type f -path "*/$variant/*" -name '*.class')"
    [ -n "$classes" ] || fail "bootstrap build produced no Kotlin classes"
    local class_metadata
    class_metadata="$(find "$project" -type f -path "*/$variant/*" -name '*.class' -exec "$java_home/bin/javap" -v {} +)"
    [[ "$class_metadata" == *FunctionKeyMeta* ]] \
        || fail "bootstrap build omitted FunctionKeyMeta compiler instrumentation"
    if [ -d "$project/features" ]; then
        local feature_classes
        feature_classes="$(find "$project/features" -type f -path "*/$variant/*" -name '*.class')"
        [ -n "$feature_classes" ] || fail "bootstrap build produced no feature-module Kotlin classes"
        local feature_metadata
        feature_metadata="$(find "$project/features" -type f -path "*/$variant/*" -name '*.class' \
            -exec "$java_home/bin/javap" -v {} +)"
        [[ "$feature_metadata" == *FunctionKeyMeta* ]] \
            || fail "bootstrap build omitted FunctionKeyMeta instrumentation from a Compose library"
    fi
    find "$project" -type f -path "*/$variant/*" -name 'LiveLiterals*$*Kt.class' | grep -q . \
        || fail "bootstrap --literals build produced no LiveLiterals helper"
    local class_bytecode
    class_bytecode="$(find "$project" -type f -path "*/$variant/*" -name '*.class' -exec "$java_home/bin/javap" -c -p {} +)"
    if [[ "$class_bytecode" == *invokedynamic* ]]; then
        fail "bootstrap build emitted invokedynamic despite deterministic class-shape flags"
    fi
    assert_tree_clean "$project" "instrumented zero-touch assemble"
}

assert_bootstrap_release_clean() {
    local project="$1"
    local java_home="$2"
    local modules="$3"
    local app_module="$4"
    local selected_variant="$5"
    local release_task="$6"
    local apk_pattern="$7"

    # Force a fresh non-debuggable package after the instrumented build so stale outputs
    # cannot make the runtime-absence assertion pass accidentally.
    bootstrap_gradle "$project" "$java_home" "$modules" "$app_module" "$selected_variant" \
        "$app_module:clean" "$release_task"
    local apk
    apk="$(find "$project" -path "$apk_pattern" -type f -name '*.apk' | head -1)"
    [ -n "$apk" ] || fail "bootstrap release APK not found under $project"
    if unzip -l "$apk" | grep 'libhotreload_agent[.]so' >/dev/null; then
        fail "zero-touch runtime leaked into non-debuggable APK $apk"
    fi
    assert_tree_clean "$project" "zero-touch non-debuggable assemble"
}

assert_nondebuggable_selected_rejected() {
    local project="$1"
    local java_home="$2"
    local modules="$3"
    local app_module="$4"
    local log="$FIXTURE_ROOT/nondebuggable-rejection.log"
    if bootstrap_gradle "$project" "$java_home" "$modules" "$app_module" release \
        "$app_module:tasks" >"$log" 2>&1; then
        fail "zero-touch accepted selected non-debuggable variant 'release'"
    fi
    grep -Fq "selected variant 'release' is not debuggable" "$log" \
        || { cat "$log" >&2; fail "non-debuggable rejection did not name the selected variant"; }
    assert_tree_clean "$project" "non-debuggable selected-variant rejection"
}

BOOTSTRAP_DIR=""
extract_packaged_bootstrap

AGP9_PROJECT="$(make_fixture agp9)"
assert_no_configured_integration "$AGP9_PROJECT"
echo "--- Host fixture: AGP 9.2.1 built-in Kotlin / JDK 21 ---"
run_inspect "$AGP9_PROJECT" "$JAVA_HOME" "9.6.1" ":mobile" "qa"
run_configure "$AGP9_PROJECT" "$JAVA_HOME" qa fixture-agp9
assert_missing_root_bootstrap_rejected "$AGP9_PROJECT" "$JAVA_HOME"
assert_plain_release_clean "$AGP9_PROJECT" "$JAVA_HOME" ":mobile:assembleRelease" '*/build/outputs/apk/release/*'
assert_bootstrap_build "$AGP9_PROJECT" "$JAVA_HOME" ':mobile' ':mobile' qa ':mobile:assembleQa' qa
assert_bootstrap_release_clean "$AGP9_PROJECT" "$JAVA_HOME" ':mobile' ':mobile' qa \
    ':mobile:assembleRelease' '*/build/outputs/apk/release/*'
assert_nondebuggable_selected_rejected "$AGP9_PROJECT" "$JAVA_HOME" ':mobile' ':mobile'

AGP8_PROJECT=""
if [ "$JAVA17_ENABLED" -eq 1 ]; then
    AGP8_PROJECT="$(make_fixture agp8)"
    assert_no_configured_integration "$AGP8_PROJECT"
    echo "--- Host fixture: AGP 8.12.3 standalone Kotlin / JDK 17 ---"
    run_inspect "$AGP8_PROJECT" "$JAVA17_HOME" "8.13" ":mobile" "internalStage"
    run_configure "$AGP8_PROJECT" "$JAVA17_HOME" internalStage fixture-agp8
    grep -Eq '"gradlePath"[[:space:]]*:[[:space:]]*":core"' "$FIXTURE_ROOT/inspect-agp8.json" \
        || fail "AGP 8 inspect did not report the Kotlin/JVM dependency module"
    grep -Eq '"projectDir"[[:space:]]*:[[:space:]]*"shared/domain"' "$FIXTURE_ROOT/inspect-agp8.json" \
        || fail "AGP 8 inspect did not preserve the nonstandard physical directory"
    assert_plain_release_clean "$AGP8_PROJECT" "$JAVA17_HOME" ":mobile:assembleInternalRelease" \
        '*/build/outputs/apk/internal/release/*'
    assert_bootstrap_build "$AGP8_PROJECT" "$JAVA17_HOME" ':mobile,:feature,:core' ':mobile' \
        internalStage ':mobile:assembleInternalStage' 'internal/stage'
    assert_bootstrap_release_clean "$AGP8_PROJECT" "$JAVA17_HOME" ':mobile,:feature,:core' ':mobile' \
        internalStage ':mobile:assembleInternalRelease' '*/build/outputs/apk/internal/release/*'
fi

if [ "$MODE" = "host" ]; then
    echo "PASS: zero-touch host fixture checks"
    exit 0
fi

wait_for_log() {
    local pattern="$1"
    local initial_count="$2"
    local timeout="${3:-90}"
    local start
    start="$(date +%s)"
    while true; do
        local count
        count="$(grep -Ec "$pattern" "$WATCH_LOG" 2>/dev/null || true)"
        if [ "$count" -gt "$initial_count" ]; then
            return
        fi
        if [ $(( $(date +%s) - start )) -gt "$timeout" ]; then
            echo "TIMEOUT waiting for log pattern: $pattern" >&2
            tail -n 40 "$WATCH_LOG" >&2 || true
            exit 1
        fi
        sleep 1
    done
}

wait_for_ready() {
    local timeout=240
    local start
    start="$(date +%s)"
    while ! grep -q 'watching ' "$WATCH_LOG" 2>/dev/null; do
        if ! kill -0 "$WATCH_PID" 2>/dev/null; then
            cat "$WATCH_LOG" >&2 || true
            fail "watcher exited before readiness"
        fi
        if [ $(( $(date +%s) - start )) -gt "$timeout" ]; then
            cat "$WATCH_LOG" >&2 || true
            fail "timeout waiting for watcher readiness"
        fi
        sleep 1
    done
}

assert_ui() {
    local expected="$1"
    local timeout=15
    local start
    start="$(date +%s)"
    while ! adb -s "$SERIAL" exec-out uiautomator dump /dev/tty 2>/dev/null | LC_ALL=C grep -Fq "$expected"; do
        if [ $(( $(date +%s) - start )) -gt "$timeout" ]; then
            echo "UI did not contain: $expected" >&2
            adb -s "$SERIAL" exec-out uiautomator dump /dev/tty 2>/dev/null \
                | LC_ALL=C grep -oE 'text="[^"]+"' | sort -u >&2 || true
            tail -n 40 "$WATCH_LOG" >&2 || true
            exit 1
        fi
        sleep 1
    done
}

tap_button() {
    local prefix="$1"
    local times="${2:-1}"
    local dump bounds
    dump="$(adb -s "$SERIAL" exec-out uiautomator dump /dev/tty 2>/dev/null)"
    bounds="$(printf '%s\n' "$dump" \
        | LC_ALL=C grep -oE "text=\"${prefix}[^\"]*\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" \
        | LC_ALL=C grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1 || true)"
    [ -n "$bounds" ] || fail "button with prefix '$prefix' not found"
    local x1 y1 x2 y2 x y
    read -r x1 y1 x2 y2 <<< "$(printf '%s\n' "$bounds" | tr '[],' '   ')"
    x=$(( (x1 + x2) / 2 ))
    y=$(( (y1 + y2) / 2 ))
    for _ in $(seq "$times"); do
        adb -s "$SERIAL" shell input tap "$x" "$y"
        sleep 0.4
    done
}

wait_for_pid() {
    local package_name="$1"
    local timeout=20
    local start pid
    start="$(date +%s)"
    while true; do
        pid="$(adb -s "$SERIAL" shell pidof "$package_name" 2>/dev/null | tr -d '\r')"
        if [ -n "$pid" ]; then
            printf '%s\n' "$pid"
            return
        fi
        [ $(( $(date +%s) - start )) -le "$timeout" ] || fail "$package_name did not start"
        sleep 1
    done
}

assert_pid() {
    local package_name="$1"
    local initial_pid="$2"
    local current
    current="$(adb -s "$SERIAL" shell pidof "$package_name" 2>/dev/null | tr -d '\r')"
    [ "$current" = "$initial_pid" ] || fail "PID changed for $package_name: expected $initial_pid, got ${current:-DEAD}"
}

start_session() {
    local command="$1"
    shift
    local project="$1"
    local java_home="$2"
    local variant="$3"
    shift 3
    WATCH_LOG="$(mktemp "${TMPDIR:-/tmp}/hotreload-zero-touch-watch.XXXXXX")"
    "$CLI" "$command" \
        --zero-touch \
        --project "$project" \
        --variant "$variant" \
        --project-java-home "$java_home" \
        --device "$SERIAL" \
        "${GRADLE_ARGS[@]+${GRADLE_ARGS[@]}}" \
        "$@" > "$WATCH_LOG" 2>&1 &
    WATCH_PID=$!
    wait_for_ready
}

start_watch() {
    start_session watch "$@"
}

start_zero_touch() {
    start_session start "$@"
}

stop_watch() {
    if [ -n "$WATCH_PID" ]; then
        kill -9 "$WATCH_PID" 2>/dev/null || true
        wait "$WATCH_PID" 2>/dev/null || true
        WATCH_PID=""
    fi
    rm -f "$WATCH_LOG"
    WATCH_LOG=""
}

echo "--- Device fixture: AGP 9 zero-touch + live literals ---"
AGP9_PACKAGE="dev.hotreload.fixture.agp9.qa"
adb -s "$SERIAL" uninstall "$AGP9_PACKAGE" >/dev/null 2>&1 || true
start_zero_touch "$AGP9_PROJECT" "$JAVA_HOME" qa --literals
AGP9_PID="$(wait_for_pid "$AGP9_PACKAGE")"
assert_instrumented_apk "$AGP9_PROJECT" "qa"
grep -q 'start: preparing (app not installed' "$WATCH_LOG" \
    || fail "zero-touch start did not prepare the absent fixture app"
assert_tree_clean "$AGP9_PROJECT" "zero-touch start readiness"
grep -q 'live-literals fast path enabled' "$WATCH_LOG" \
    || fail "matching --literals watcher did not enable the fast path"
assert_ui 'Agp9 resource'
tap_button 'Count: ' 2
assert_ui 'Count: 2'

AGP9_SOURCE="$AGP9_PROJECT/applications/mobile/src/main/kotlin/dev/hotreload/fixture/agp9/MainActivity.kt"
compiled_count="$(grep -Ec 'hot-swapped:|interpreted:' "$WATCH_LOG" || true)"
perl -pi -e 's/Text\(transform\(stringResource\(R.string.fixture_label\)\)\)/Text(transform(stringResource(R.string.fixture_label)).uppercase())/' "$AGP9_SOURCE"
grep -q 'fixture_label)).uppercase()' "$AGP9_SOURCE" || fail "AGP 9 source edit did not apply"
wait_for_log 'hot-swapped:|interpreted:' "$compiled_count"
assert_ui 'AGP9 RESOURCE'
assert_ui 'Count: 2'
assert_pid "$AGP9_PACKAGE" "$AGP9_PID"

literal_count="$(grep -c 'literal-pushed:' "$WATCH_LOG" || true)"
perl -pi -e 's/literal: v1/literal: v2/' "$AGP9_SOURCE"
wait_for_log 'literal-pushed:' "$literal_count" 60
assert_ui 'literal: v2'
assert_ui 'Count: 2'
assert_pid "$AGP9_PACKAGE" "$AGP9_PID"

AGP9_STRINGS="$AGP9_PROJECT/applications/mobile/src/main/res/values/strings.xml"
resource_count="$(grep -c 'resource-swapped:' "$WATCH_LOG" || true)"
perl -pi -e 's/Agp9 resource/Agp9 resource edited/' "$AGP9_STRINGS"
wait_for_log 'resource-swapped:' "$resource_count" 120
assert_ui 'AGP9 RESOURCE EDITED'
assert_ui 'Count: 2'
assert_pid "$AGP9_PACKAGE" "$AGP9_PID"
stop_watch
git -C "$AGP9_PROJECT" restore .
assert_tree_clean "$AGP9_PROJECT" "AGP 9 edit restoration"
echo "PASS: AGP 9 zero-touch start/compiled/literal/resource paths"

if [ "$JAVA17_ENABLED" -eq 1 ]; then
    echo "--- Device fixture: AGP 8 zero-touch / JDK 17 ---"
    AGP8_PACKAGE="dev.hotreload.fixture.agp8.internal.stage"
    adb -s "$SERIAL" uninstall "$AGP8_PACKAGE" >/dev/null 2>&1 || true
    "$CLI" prepare \
        --zero-touch \
        --project "$AGP8_PROJECT" \
        --variant internalStage \
        --project-java-home "$JAVA17_HOME" \
        --device "$SERIAL" \
        "${GRADLE_ARGS[@]+${GRADLE_ARGS[@]}}"
    AGP8_PID="$(wait_for_pid "$AGP8_PACKAGE")"
    assert_instrumented_apk "$AGP8_PROJECT" "internal/stage"
    assert_tree_clean "$AGP8_PROJECT" "AGP 8 zero-touch prepare"
    start_watch "$AGP8_PROJECT" "$JAVA17_HOME" internalStage
    assert_tree_clean "$AGP8_PROJECT" "AGP 8 zero-touch watch readiness"
    assert_ui 'Agp8 original'
    tap_button 'Count: ' 2
    assert_ui 'Count: 2'

    AGP8_SOURCE="$AGP8_PROJECT/shared/domain/src/main/kotlin/dev/hotreload/fixture/agp8/core/CoreLabel.kt"
    compiled_count="$(grep -Ec 'hot-swapped:|interpreted:' "$WATCH_LOG" || true)"
    perl -pi -e 's/fun coreLabel\(value: String\): String = value$/fun coreLabel(value: String): String = value.uppercase()/' "$AGP8_SOURCE"
    grep -q 'value.uppercase()' "$AGP8_SOURCE" || fail "AGP 8 source edit did not apply"
    wait_for_log 'hot-swapped:|interpreted:' "$compiled_count"
    assert_ui 'AGP8 ORIGINAL'
    assert_ui 'Count: 2'
    assert_pid "$AGP8_PACKAGE" "$AGP8_PID"
    stop_watch
    git -C "$AGP8_PROJECT" restore .
    assert_tree_clean "$AGP8_PROJECT" "AGP 8 edit restoration"
    echo "PASS: AGP 8 zero-touch JDK 17 multi-module hot swap"
fi

echo "PASS: zero-touch fixture gate"
