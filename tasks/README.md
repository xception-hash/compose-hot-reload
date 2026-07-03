# Delegated task specs

Specs for work delegated to Antigravity CLI (run by Jay) per `docs/WORKFLOW.md`.
One file per task: `T<NN>-<name>.md`. Status tracked by a line at the top of each spec.

Template:

```markdown
# T<NN>: <title>
Status: TODO | IN-REVIEW | DONE
Assignee: agy | jay

## Goal
One paragraph. Why it exists, what phase it serves.

## Spec
Exact requirements: files to create, versions to pin, APIs to use. No open design questions —
if a design decision is needed, it goes back to Claude first.

## Out of scope
What NOT to touch.

## Acceptance
Exact commands that must pass, run from repo root.
```
