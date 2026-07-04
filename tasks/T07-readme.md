# T07: README quickstart
Status: DONE
Assignee: agy

## Goal
Top-level `README.md` so a new user can go from clone to a working hot-reload session.

## Spec
Write `README.md` at repo root. Source material (do NOT invent facts — everything needed
is in these files): `docs/PLAN.md` (project pitch, architecture), `docs/phase1.md`
(how to run), `docs/phase2-findings.md` (what works + state semantics),
`samples/single-module/` (reference setup with the `dev.hotreload` Gradle plugin).
Sections:
1. What it is (Flutter-style hot reload for Jetpack Compose on Android devices; zero
   app-code edits; CLI-driven v1) + a one-paragraph how-it-works (JVMTI redefine +
   dex inject + targeted Compose invalidation).
2. Requirements: API 30+ debuggable build, pinned toolchain (Kotlin 2.4.0, AGP 9.2.1,
   Gradle 9.6.1, Compose BOM 2026.06.01), emulator or device via adb.
3. Quickstart: apply `dev.hotreload` plugin (show the two build-file lines from the
   sample), install+launch, run `hotreload watch` (exact command from docs/phase1.md),
   edit a composable, see it on device.
4. What hot-reloads today (table from docs/phase2-findings.md) + state-reset semantics
   (edited function's subtree resets — Live Edit parity) + broken-edit behavior
   (error reported, last-good frame kept, fix-and-save recovers).
5. Repo layout: one line each for protocol/, runtime-client/, engine/, cli/,
   gradle-plugin/, samples/, e2e/, docs/, tasks/.
6. Status: experimental; single-module only; link docs/PLAN.md phases.

## Out of scope
No code changes. No docs/ changes. No badges/logos/marketing fluff.

## Acceptance
1. `README.md` exists; every command in it is copy-paste runnable from repo root
   (verify the watch command matches docs/phase1.md verbatim).
2. No claim in the README contradicts docs/phase2-findings.md (spot-check the
   state-preservation and error-recovery claims).
