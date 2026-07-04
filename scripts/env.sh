# Source this from every script: exact pinned toolchain, no re-deriving paths.
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="$HOME/Library/Android/sdk"
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
