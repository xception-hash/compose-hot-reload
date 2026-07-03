#!/usr/bin/env bash
# SessionStart hook: surface project protocol pointer + pending state at session start.
cd "$(dirname "$0")/../.." || exit 0

todo=$(grep -l "Status: TODO" tasks/T*.md 2>/dev/null | paste -sd' ' - || true)
review=$(grep -l "Status: IN-REVIEW" tasks/T*.md 2>/dev/null | paste -sd' ' - || true)
dirty=$(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')

ctx="Read docs/WORKFLOW.md and follow it: scripts/ for routine ops, token discipline, delegation protocol, update docs + memory status and commit before session end."
[ -n "$todo" ] && ctx="$ctx Delegated task specs still TODO: $todo."
[ -n "$review" ] && ctx="$ctx Delegated tasks awaiting review (run their Acceptance commands): $review."
[ "$dirty" != "0" ] && ctx="$ctx Working tree has $dirty uncommitted change(s) — reconcile/commit early."

printf '{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":%s}}\n' \
  "$(printf '%s' "$ctx" | jq -Rs .)"
