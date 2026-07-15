#!/usr/bin/env bash
# Delegate a task spec to Antigravity CLI (headless).
# Usage: scripts/delegate.sh tasks/T01-repo-skeleton.md "Model Name"
# The coordinator may run this in the background, then reviews the Acceptance commands.
set -euo pipefail
source "$(dirname "$0")/env.sh"

if [[ $# -ne 2 ]]; then
  echo 'usage: scripts/delegate.sh <task-spec.md> "<Model Name>"' >&2
  echo 'run `agy models` first and choose the least expensive capable model' >&2
  exit 2
fi

SPEC="$1"
MODEL="$2"
cd "$REPO_ROOT"
[ -f "$SPEC" ] || { echo "no such spec: $SPEC"; exit 1; }

# Runs inside agy's sandbox. If the sandbox blocks the task (e.g. Gradle needs network),
# The maintainer runs the spec interactively instead: agy -i "implement tasks/T01-... exactly"
exec agy --print "You are executing a delegated task in this git repository ($REPO_ROOT).
Read the spec file '$SPEC' and implement it EXACTLY as written — no extra features,
no touching anything listed under 'Out of scope'. When implementation is done, run the
commands in the spec's Acceptance section and fix failures until they pass. Finally edit
'$SPEC' changing 'Status: TODO' to 'Status: IN-REVIEW'. Do not git commit." \
  --model "$MODEL" --print-timeout 45m --sandbox --dangerously-skip-permissions
