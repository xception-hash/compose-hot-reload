# Delegated task specs

Specs for work delegated to Antigravity CLI (run by the maintainer) per `docs/WORKFLOW.md`.
One file per task: `T<NN>-<name>.md`. Status tracked by a line at the top of each spec.

Template:

````markdown
# T<NN>: <title>
Status: TODO | IN-REVIEW | DONE
Assignee: agy | maintainer
Recommended model: <least expensive capable model, chosen from current `agy models`>
Fallback model: <prefer a different GPT/Gemini/Claude family>

## Dispatch
```bash
scripts/delegate.sh tasks/T<NN>-<name>.md "<Model Name>"
```

## Goal
One paragraph. Why it exists, what phase it serves.

## Spec
Exact requirements: files to create, versions to pin, APIs to use. No open design questions —
if a design decision is needed, it goes back to the coordinator first.

## Out of scope
What NOT to touch.

## Acceptance
Exact commands that must pass, run from repo root.
````

For a long-running task intended for later execution, the coordinator writes this file and
reports the dispatch command but does not start it without the maintainer's request. Re-run
`agy models` before dispatch because model availability changes. A model appearing in that list
does not guarantee remaining quota; Opus 4.6 is unavailable until the maintainer confirms reset.
