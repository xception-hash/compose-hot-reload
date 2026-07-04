# T16: On-device experiment — drawable/color overlays + full-composition invalidation
Status: TODO
Assignee: Opus session + device (T14 proved this task shape works; failures are FINDINGS)

## Goal
Close the two gaps T14 left before resource-edit support can be built:
1. Do `painterResource`/`colorResource` pick up a `ResourcesLoader` overlay on tier-1
   recomposition, or do cached decoded assets force a recreate? (T14 caveat #1.)
2. Find the ZERO-APP-EDIT mechanism to recompose ALL resource readers with `remember`
   state PRESERVED. This is the real blocker: a pure resource edit has no edited-function
   key, and `invalidateGroupsWithKey` RESETS remember in the keyed subtree (phase-2
   finding), so key-based invalidation of everything = tier-2-grade state loss. T14's
   counter-tap only proved "a recomposition suffices" — we need something the engine can
   trigger.

Deliverable: `docs/resource-invalidation-experiment.md`. All sample edits reverted.

## Spec
0. FREE RESEARCH FIRST (thanks T13 — grep, no web): in `third_party/tools-base/deploy/`,
   find what the deployer does AFTER `ResourceOverlays.addResourceOverlays` to refresh UI
   (callers in `InstrumentationHooks.java`, anything Live-Edit/Compose-aware). One short
   section in the doc.
1. Rebuild the T14 harness (its doc §Setup is the exact recipe, incl. the
   `android.content.res.loader.*` import gotcha and the broadcast hook). Extend it:
   - resources: keep `exp_label` string; add a color `exp_color` and a drawable
     `exp_icon` (vector, e.g. filled circle; overlay flips string + color + shape/color
     of the drawable).
   - UI: one composable reading all three via `stringResource`/`colorResource`/
     `painterResource(Image(...))`, next to the counter button (recompose driver +
     remember-state canary).
2. Repeat T14's table for all three resource types: launch → `add` loader →
   tap (plain recomposition) → `recreate`. Columns: getString/getColor (direct),
   on-screen per type, counter value (state canary).
3. Invalidation candidates — for each: does the string/color update on screen, and is the
   counter (remember) PRESERVED? Drive via new broadcast cmds; log method lists first.
   a. `ControlledComposition.invalidateAll()` on every live composition, via reflection:
      start from what ComposeBridge already reaches (Recomposer.Companion, name-prefix
      match — see runtime-client/ComposeBridge.kt); log all companion/instance method+
      field names to find the route to known compositions (`_runningRecomposers` →
      recomposer's composition list is the likely path). PRIME CANDIDATE: plain scope
      invalidation preserves remember by construction.
   b. `invalidateGroupsWithKey` with ONLY the reader composable's key — confirm the
      subtree remember reset (counter placement: put one counter INSIDE the reader
      composable, one outside; record which survives).
   c. Whatever §0 says the deployer does, if different.
4. Write the doc: §0 findings, step→observation tables (2 and 3), and an explicit
   recommendation line: "engine should trigger X; drawables need tier Y".

## Out of scope
NO changes to engine/, runtime-client/, protocol/, gradle-plugin/ (log-and-observe only;
the temporary hook must not survive — `git status` clean at the end). No aapt2
add/remove-resource cases (ID renumbering stays out of scope).

## Acceptance
1. `test -f docs/resource-invalidation-experiment.md` — has the per-type table (step 2)
   AND the candidates table (step 3) with a state-preserved column.
2. Doc states an explicit recommendation for the engine mechanism + the minimum tier for
   drawables/colors.
3. `git status` clean except the doc + this spec's status line.
