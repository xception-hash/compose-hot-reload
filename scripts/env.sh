# Source this from every script: exact pinned toolchain, no re-deriving paths.
#
# macOS development uses Android Studio's bundled JBR. CI runs on Linux, where that
# path cannot exist; when its setup step has intentionally cleared JAVA_HOME, use
# the Java executable supplied by the runner instead.
if [ -z "${JAVA_HOME:-}" ]; then
    if [ "$(uname -s)" = "Darwin" ]; then
        export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    else
        HOTRELOAD_JAVA_BIN="$(command -v java 2>/dev/null || true)"
        if [ -z "$HOTRELOAD_JAVA_BIN" ]; then
            echo "ERROR: JAVA_HOME is unset and no java executable is available on PATH" >&2
            return 1 2>/dev/null || exit 1
        fi
        HOTRELOAD_JAVA_BIN="$(readlink -f "$HOTRELOAD_JAVA_BIN")"
        export JAVA_HOME="$(cd "$(dirname "$HOTRELOAD_JAVA_BIN")/.." && pwd -P)"
    fi
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

export D8="$ANDROID_HOME/build-tools/36.0.0/d8"
export DEXDUMP="$ANDROID_HOME/build-tools/36.0.0/dexdump"
export JAVAP="$JAVA_HOME/bin/javap"
export EMULATOR="$ANDROID_HOME/emulator/emulator"

export AVD=Medium_Phone_API_36.0
export PKG=${PKG:-dev.hotreload.toy}
export REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export TOY="$REPO_ROOT/spike/toy-app"
export TOY_CLASSES="$TOY/app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
