# T34: Plugin 0.1.5 — first-run UX preflight + IDE-compatibility bump
Status: DONE — superseded by T35 and later Marketplace release 0.1.8 (2026-07-20)
Assignee: coordinator (Opus) — NOT agy-delegable (device-gated behavior change + Marketplace judgment)
Recommended model: Opus (device verification + verifier-finding triage)

## Outcome (2026-07-16, host-side complete)

Both phases implemented on branch `t34/plugin-0.1.5-ux-compat`. Mechanical implementation was
delegated to a Sonnet subagent (per the maintainer: Sonnet, NOT agy — agy quota reached); the
coordinator designed the preflight, decided the platform target + verifier pins, and independently
re-verified every gate from the build artifacts.

- **Phase 1 — preflight:** new pure `HotReloadPreflight.parse(exitCode, output)` (unit-tested);
  `HotReloadWatchConfig.doctorArguments()`; `HotReloadService.start()` now runs `hotreload doctor`
  on a pooled thread first — pass ⇒ `launch()`, fail ⇒ `OFF` + a WARNING notification listing the
  `[FAIL]` items with a **"Start anyway"** action (no hard block). Advanced opt-out setting
  `skipPreflight` wired through settings + the Configurable UI. If doctor can't even run (no CLI
  launcher / bad config) the preflight falls through to `launch()`, which surfaces the real error.
- **Phase 2 — compat bump:** `platformVersion` 2025.1 → **2026.1.4** (current stable IC, confirmed
  in the intellij-repository maven repo). `pluginVersion` 0.1.4 → **0.1.5**. changeNotes added.
  Verifier `ides` pins the older 2025.1 line, the 2026.1.4 stable, and the 262 EAP. Required a
  one-line build-script fix: the main `intellijIdeaCommunity(...)` dependency needed
  `useInstaller = false` (2026.1.4 has no download.jetbrains.com DMG, only a maven artifact).
- **Host gates (independently re-verified from artifacts):** `compileKotlin` PASS vs 2026.1.4 (no
  API break → Phase 2 stayed in scope), `test` **34/34**, `buildPlugin` →
  `hotreload-intellij-plugin-0.1.5.zip` (8.2 MB, bundled CLI+engine intact), `verifyPlugin`
  **Compatible on all three IDEs** (only the 3 pre-existing `StatusBarWidget.PlatformType`
  deprecations — not internal/removed API, so no Marketplace-rejection risk). Confidentiality grep
  on the full diff clean.
- **Left for the maintainer:** (1) on-device verify in Android Studio on emulator-5554 — install
  the 0.1.5 zip, confirm the preflight fires on a broken env (e.g. app not running) and the happy
  path still Start→Ready→reloads the bundled sample; (2) merge the PR; (3) publish 0.1.5 via the
  standard signed `publishPlugin` chain (reuse the existing key).

## Goal

Ship IntelliJ/Android Studio plugin **0.1.5** as one release with two phases:
1. First-run UX for external Marketplace users (a pre-Start environment preflight + listing polish).
2. An IDE-compatibility bump (build against the current IntelliJ platform, verify against the
   newest IC/AS builds, fix any API drift).

Bundle both into one `verifyPlugin` + publish cycle. **Plugin-only** — the engine/CLI/protocol
must NOT change (protocol stays v8; no runtime-client AAR reinstall needed). Confirm that before
touching anything outside `intellij-plugin/`.

## Read first (do not skip — the memory mirror may be stale)

1. `.agents/STATUS.md` — newest dated sections (canonical live status).
2. `docs/OPUS-HANDOFF.md` — standing device/delegation/privacy protocol.
3. `tasks/T31-ide-plugin-release.md` — known plugin gaps: SDK-toolchain prereq
   (`build-tools/36.0.0` d8/dexdump), "app must be running before Start", bundled-CLI JAVA_HOME.
4. `intellij-plugin/src/main/kotlin/dev/hotreload/idea/HotReloadService.kt` and
   `HotReloadDiscovery.kt` — current Start flow and how `hotreload inspect` / `doctor` are invoked
   (today `doctor` is referenced only inside crash-report text, never as a proactive preflight).
5. `intellij-plugin/gradle.properties` + `build.gradle.kts` — `platformVersion` (currently 2025.1),
   `sinceBuild = "242"` / `untilBuild = null` (open), and the `verifyPlugin` pin list. PR #13
   (`chore/verifier-262-gate`) already added IC 2026.2 / build `262.8665.176` coverage — do not
   regress it.

## Spec

### Phase 1 — first-run UX
- Add a pre-Start environment preflight: run `hotreload doctor`, and if it fails, show an actionable
  IDE notification (missing SDK `build-tools/36.0.0` d8/dexdump; no device; app not running) instead
  of a raw CLI error. Do NOT hard-block advanced users — warning path with a "Start anyway"
  affordance where sensible.
- Polish Marketplace listing metadata: description, 0.1.5 change-notes, tags, source URL.

### Phase 2 — IDE-compatibility bump
- Raise `platformVersion` (`gradle.properties`) from 2025.1 to the current stable IntelliJ IDEA
  Community release — confirm the exact version exists in the IntelliJ maven repo FIRST.
- Add the newest IC/AS builds to the `verifyPlugin` pin list; keep the 262 pin. Run
  `./gradlew verifyPlugin` and FIX any flagged internal/removed API usage — treat verifier findings
  as **blockers, not warnings** (this is exactly what caused the prior Marketplace rejections in
  PRs #10 and #13). Re-confirm `sinceBuild="242"` / `untilBuild` open are still correct for the new
  platform.

## Constraints / conventions

- Bump `pluginVersion` in `intellij-plugin/gradle.properties` to **0.1.5** (covers both phases).
- **Device-verify on emulator-5554:** install the built 0.1.5 zip in Android Studio; confirm the
  preflight fires on a broken env (e.g. app not running) AND the happy path still
  Start→Ready→hot-reloads the bundled sample. Host-only evidence is NOT acceptance for a behavior
  change. Always `source scripts/env.sh` with an ABSOLUTE path.
- Confidentiality grep on the FULL diff before any commit (no maintainer name/employer/absolute home paths). The
  harness blocks pushing to `main` → land via a branch + PR for the maintainer to merge.
- Publish (maintainer's call — prep and verify, then ASK before running):
  ```bash
  cd intellij-plugin && source ../scripts/env.sh \
    && source ~/.config/jetbrains-plugin-signing/publish-env.sh \
    && ./gradlew publishPlugin
  ```
  Reuse the existing signing key — never regenerate.

## Fallback / de-scope rule

If Phase 2's `verifyPlugin` surfaces a large API break that needs real rework, split it back out:
ship Phase 1 alone as 0.1.5 and move Phase 2 to a 0.1.6 follow-up. Do NOT ship a plugin whose
declared compat range includes a build it fails the verifier on.

## Out of scope

Engine/runtime/classifier/protocol changes; embedding the engine in the IDE process (v1 stays
CLI-spawn); anything requiring a protocol version bump.

## Acceptance

- 0.1.5 zip builds; `./gradlew verifyPlugin` GREEN against the updated pin list (newest IC/AS + 262).
- Preflight demonstrated LIVE on a broken env, and the happy path on emulator-5554.
- Branch pushed + PR opened.
- `.agents/STATUS.md` and the memory handoff updated with a dated section.
