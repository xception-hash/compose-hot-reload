#!/usr/bin/env bash
# Runs the offline zero-touch compatibility suite and asserts the AGP 8/JDK 17
# leg actually executed (rather than being silently skipped).
#
# This has to be a single script invoked as one command, not an inline
# multi-line `script:` block in the emulator-runner workflow step: that action
# splits its `script` input on newlines and runs EACH line as an independent
# `sh -c` process (see reactivecircus/android-emulator-runner's
# src/script-parser.ts + main.ts). Shell state — `code=$?`, `set +e`, local
# vars — does not survive across those lines, so capturing an exit code on
# one line and using it on another silently breaks.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

test -n "${JAVA17_HOME:-}" || { echo "ERROR: JAVA17_HOME is required; refusing to skip the AGP 8/JDK 17 leg" >&2; exit 1; }
test -x "$JAVA17_HOME/bin/java" || { echo "ERROR: JAVA17_HOME has no java executable: $JAVA17_HOME" >&2; exit 1; }
"$JAVA17_HOME/bin/java" -version

ZERO_TOUCH_OFFLINE=1 JAVA17_HOME="$JAVA17_HOME" bash "$SCRIPT_DIR/run-zero-touch.sh" > zero-touch-compatibility.log 2>&1
code=$?
cat zero-touch-compatibility.log

if grep -Fq 'SKIP: AGP 8/JDK 17' zero-touch-compatibility.log; then
    echo 'ERROR: AGP 8/JDK 17 leg was skipped' >&2
    exit 1
fi

grep -Fq 'PASS: AGP 8 zero-touch JDK 17 multi-module hot swap' zero-touch-compatibility.log || {
    echo 'ERROR: AGP 8/JDK 17 leg did not reach its device assertion' >&2
    exit 1
}

exit "$code"
