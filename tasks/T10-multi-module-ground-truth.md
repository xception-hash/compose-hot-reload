# T10: Multi-module ground-truth doc (feeds the Fable design session)
Status: DONE (reviewed 2026-07-04: acceptance re-verified, paths spot-checked)
Assignee: agy (Opus-class OK; needs to run Gradle builds — if sandbox blocks, Jay runs interactively)

## Goal
Produce `docs/multi-module-ground-truth.md`: every verified fact the multi-module diffing
DESIGN session needs, so that session spends zero tokens exploring the repo or running builds.
This task gathers and verifies facts only — it makes **no design decisions and no code changes**.
Testbed: `samples/multi-module/` (app → feature → core; done in T08, commit e4df5f8, known to
build/install/render on the emulator). Source `scripts/env.sh` for all tool paths.

## Spec
Write `docs/multi-module-ground-truth.md` with the sections below. Every path/task-name claim
must be verified by an actual command run, and the doc must show the command used next to the fact.

1. **Module graph & plugin types.** For each of `:app`, `:feature`, `:core` in
   `samples/multi-module`: which Gradle plugin (com.android.application / com.android.library /
   kotlin-jvm), dependency edges, and how the compiler flags
   (`-Xlambdas=class -Xsam-conversions=class -Xstring-concat=inline`) are wired
   (`dev.hotreload` plugin vs hand-written `kotlin {}` block — :feature/:core are hand-wired).
2. **Compiled-classes output dir per module.** After `./gradlew :app:assembleDebug`, find the
   directory containing fresh `.class` files for each module. Known/expected:
   app = `app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes`;
   :feature (com.android.library) path DIFFERS — find and record it; :core = `core/build/classes/kotlin/main`.
   Verify by touching one source file per module, recompiling, and confirming that module's
   `.class` timestamp changed (and the others' did not, where applicable).
3. **Minimal per-module compile task.** The cheapest Gradle task that recompiles ONLY the edited
   module's Kotlin (what `GradleCompiler` would invoke per module), e.g. `:feature:compileDebugKotlin`
   vs `:core:compileKotlin` — exact names, verified by running them. Record whether editing :core
   and running only `:core:compileKotlin` is enough, or whether :app/:feature recompile too
   (ABI vs body-only change — try BOTH: a body-only edit and a signature change in :core, record
   which downstream compile tasks re-run in each case; `--console=plain` output is the evidence).
4. **Flags actually applied in all 3 modules.** Prove lambdas compile to classes (not indy) in
   each module: `javap -v` (JBR javap, see env.sh) on a class with a lambda per module — no
   `invokedynamic` for the lambda, and no `makeConcatWithConstants` anywhere (string-concat=inline).
5. **FunctionKeyMeta extractable from library modules.** Run
   `CLASSES_DIR=<feature classes dir> scripts/extract-keys.sh <a @Composable FQCN in :feature>`
   and include the output. Same for :core IF :core has any @Composable (if it's pure Kotlin
   logic with no Compose, state that instead).
6. **Timing.** Warm-daemon incremental compile time per module for a one-line body edit
   (3 samples each, `./gradlew` is fine; note it's an upper bound vs Tooling API warm connection).
7. **Where the engine's single-module assumptions live.** File:line list (grep-level, no fixes) of
   every place that assumes one classesDir / one module: `engine/.../WatchSession.kt`
   (Config.forProject hardcoded classesDir), `ClassSnapshot.kt`, `GradleCompiler.kt`,
   `SourceWatcher.kt` (watch roots), `cli/` arg parsing, `scripts/extract-keys.sh`, `e2e/run.sh`.
   One line each: path:line — what it assumes.

## Out of scope
- NO changes to engine/, runtime-client/, protocol/, gradle-plugin/ code.
- NO design proposals (module→classesDir mapping strategy, cross-module Classifier rules etc.
  are the Fable session's job).
- Do not touch samples/single-module.

## Acceptance
Run from repo root:
1. `test -f docs/multi-module-ground-truth.md` — exists, all 7 sections present.
2. Every directory path named in section 2 exists and contains `.class` files:
   `ls <each path> | head` shown in the doc.
3. Section 5 shows real `extract-keys.sh` output (keys + offsets) for a :feature composable.
4. `git status` clean except the new doc (+ any sample source touched for timing MUST be reverted).
