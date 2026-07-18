# T38: Manual Android Studio plugin smoke — zero-touch and configured integration

Status: BLOCKED (Mode A PASS; Mode B compile routing works, but the single retry corrupts a Compose capture at runtime, 2026-07-18)
Assignee: maintainer, manually in Android Studio with the API-30+ device visible
Recommended model: Gemini 3.5 Flash (Low), only for organizing already-captured logs/docs
Fallback model: GPT-OSS 120B (Medium)

## Dispatch

This is a device/IDE truth-finding task. Do **not** delegate Start/Stop actions, visual verdicts,
source edits, PID checks, or Gradle-file restoration. If the maintainer has already completed the
manual checklist and saved sanitized evidence, an assistant may update the findings with:

```bash
agy models
scripts/delegate.sh tasks/T38-manual-plugin-dual-mode-smoke.md "Gemini 3.5 Flash (Low)"
```

Do not use Claude Opus 4.6: its quota is recorded as empty. Re-run `agy models` before any future
delegation because availability changes. The coordinator must still review every documentation
change and acceptance result.

## Goal

From the maintainer's real Android Studio target checkout and the installed local plugin candidate,
prove the same reversible Compose body edit in both supported integration modes:

1. **Plugin + zero-touch:** no target Gradle/source wiring for hot reload.
2. **Plugin + configured mode:** the target temporarily includes this repository's local
   `gradle-plugin` and `runtime-client` builds and applies `id("dev.hotreload")` to every module
   included in the smoke.

Both modes must reach the plugin's Ready state, visibly apply and reverse the edit without process
death, stop cleanly, and leave the maintainer's pre-existing target changes byte-for-byte intact.

## Fixed context

- Product milestone: commit `778d378` (`T37: align patch desugaring with installed APK`).
- Local plugin candidate: `intellij-plugin/build/distributions/hotreload-intellij-plugin-0.1.8.zip`.
- Plugin Verifier already reports 0.1.8 Compatible on all three configured IDE baselines.
- The target has multiple app modules. Select the intended app explicitly; do not let discovery
  guess. The previously validated app variant is `demoDebug` with app module `:app`.
- Use the target checkout opened in Android Studio. Do not use or edit a `/tmp` clone.
- The target currently has a maintainer-owned Compose source modification. It is the baseline, not
  cleanup debris. Record it before testing and restore to it—not to Git HEAD.
- Plugin Start launches `hotreload watch`, not `prepare`. Prepare/install the matching mode first.
- API 30 remains the runtime/device floor even though the target APK declares minSdk 23.

Set these locally; never paste their values into tracked docs:

```bash
export HOTRELOAD_ROOT=<absolute-compose-hot-reload-checkout>
export TARGET_ROOT=<absolute-Android-Studio-target-checkout>
export DEVICE=<adb-serial>
export APP_MODULE=:app
export VARIANT=demoDebug
export FEATURE_MODULE=<one-reachable-Compose-library-gradle-path>
export SOURCE_FILE=<the-maintainer-owned-Compose-file-used-for-the-reversible-edit>
```

## Before either mode

1. In the product checkout, confirm `git merge-base --is-ancestor 778d378 HEAD` succeeds and
   `git status --short` is empty. Documentation commits may sit above the product milestone.
2. From `$HOTRELOAD_ROOT`, run the required preflight:

   ```bash
   unset JAVA_HOME
   source scripts/env.sh
   "$JAVA_HOME/bin/java" -version 2>&1 | head -1
   ls "$ANDROID_HOME/build-tools/36.0.0/d8"
   ls "$ANDROID_HOME/build-tools/36.0.0/dexdump"
   adb devices
   adb -s "$DEVICE" shell getprop ro.build.version.sdk
   ```

   Require the Android Studio JBR, both build-tools binaries, exactly one selected online device,
   and device API >= 30.
3. Stop any existing plugin/CLI watcher. Never run two watchers against the same device.
4. Capture, outside the repository, the target baseline:

   ```bash
   git -C "$TARGET_ROOT" status --short > /tmp/t38-target-status.before
   git -C "$TARGET_ROOT" diff > /tmp/t38-target-diff.before
   shasum -a 256 "$TARGET_ROOT/$SOURCE_FILE" > /tmp/t38-source.before.sha256
   ```

   Inspect these files. Do not continue if there are unexpected target Gradle changes.
5. If the 0.1.8 ZIP is missing or the product HEAD changed, rebuild it:

   ```bash
   ./gradlew :cli:installDist
   (cd intellij-plugin && unset JAVA_HOME && source ../scripts/env.sh && ./gradlew test buildPlugin verifyPlugin)
   ```
6. In Android Studio: Settings > Plugins > gear > Install Plugin from Disk; select the 0.1.8 ZIP
   and restart the IDE if requested. Confirm the installed plugin reports version 0.1.8.

## Mode A — plugin zero-touch

### Prepare

From `$HOTRELOAD_ROOT`:

```bash
./gradlew -q :cli:run --args="prepare --zero-touch --project $TARGET_ROOT --app-module $APP_MODULE --variant $VARIANT --device $DEVICE"
```

Require successful preparation, install, launch, and fingerprint creation. Confirm the intended
app is foregrounded before pressing Start.

### Android Studio settings

Open Settings > Tools > Compose Hot Reload:

- CLI launcher: blank, proving the plugin's bundled CLI is used.
- Project dir: the actual `$TARGET_ROOT` checkout.
- Refresh discovery, then explicitly select app module `:app` and variant `demoDebug`. If the
  current candidate remains on `Discovering…` for this large target, do not repeatedly refresh:
  record it as the discovery UI defect below, close/reopen settings, and enter the already
  verified app/variant/id/module-closure values manually.
- Application id: the value discovered for that exact app/variant; do not record it in this repo.
- Watched modules: accept the discovered reachable closure.
- Device: `$DEVICE` when more than one device is visible.
- SDK: discovered/valid SDK path.
- `Use zero-touch bootstrap`: **checked**.
- Environment preflight: enabled; do not use Start Anyway unless the failure is understood and
  recorded.
- Confirm the read-only command preview contains `watch`, `--zero-touch`, the real target root,
  selected app/variant, and no `/tmp` checkout.

### Live check

1. Press Start once. Wait for widget `starting` -> `ready`. Ready is the only edit gate.
2. Record the app PID:

   ```bash
   adb -s "$DEVICE" shell pidof <application-id>
   ```
3. Make one unmistakable, reversible body-only text change in `$SOURCE_FILE` (for example append
   one `!` to the maintainer's current string). Do not replace the maintainer's baseline edit.
4. Require widget `reloading` -> `ready`, a reload latency tooltip, a visibly changed frame, and
   the same PID. A compile/recomposition error or Rebuild Needed is a failure to investigate.
5. Remove only the temporary marker. Require a second `reloading` -> `ready`, the original visible
   frame, and the same PID.
6. Press Stop. Require widget `off`. Confirm no watcher remains before Mode B.

Record sanitized results in the T38 Results section below. Screenshots/logs may stay in `/tmp` but
must not be committed.

### Mode A result — 2026-07-17

The maintainer completed the local 0.1.8 plugin zero-touch smoke against the real Android Studio
target:

- Matching zero-touch `prepare` built, installed, launched, and wrote a fingerprint.
- Plugin Start reached Ready with the bundled CLI, preflight enabled, and zero-touch enabled.
- A reversible body-only edit visibly applied, then visibly reverted; the app PID stayed stable
  across both reloads.
- Plugin Stop returned to Off.

One plugin defect was found: on this large target, Refresh discovery remained on `Discovering…`
even though the bundled CLI's non-mutating `inspect --json --zero-touch` completed successfully.
The discovery service drains process stdout before stderr, which can deadlock when Gradle emits
enough diagnostics. This is a **needs-code-change** issue, not a target configuration failure.
The smoke continued with the verified reachable module closure entered manually. A malformed
whitespace-containing module entry caused a fingerprint refusal until the exact comma-separated
closure was saved; do not use `--ignore-fingerprint` as a workaround.

## Mode B — plugin configured integration using this local checkout

### Add temporary Gradle wiring

Do not use JitPack for this smoke: it does not contain the unshipped 0.1.8 candidate. Edit the
target's existing Gradle blocks rather than creating duplicate blocks:

1. Inside the existing root `pluginManagement {}` block in `settings.gradle.kts`, add:

   ```kotlin
   includeBuild("<compose-hot-reload-root>/gradle-plugin")
   ```

2. At settings top level, add:

   ```kotlin
   includeBuild("<compose-hot-reload-root>/runtime-client")
   ```

   Replace the placeholder locally with the real path, but never commit that path here. This
   direct runtime-client composite is for an AGP 9 target; the README's `mavenLocal()` workaround
   must be used instead for an AGP 8 target.
3. In the app module and the chosen `$FEATURE_MODULE`, put the hot-reload plugin last in their
   existing `plugins {}` blocks:

   ```kotlin
   id("dev.hotreload")
   ```

   Apply it to every module in the smoke's watched-module list. For this bounded check, watch only
   the app plus `$FEATURE_MODULE`; do not claim the other discovered modules are configured.
4. Sync Gradle successfully. Inspect `git -C "$TARGET_ROOT" diff` and confirm it contains only
   the intended composite/plugin lines plus the maintainer's pre-existing source change.

### Prepare

From `$HOTRELOAD_ROOT`, omit `--zero-touch` and restrict discovery to the configured library:

```bash
./gradlew -q :cli:run --args="prepare --project $TARGET_ROOT --app-module $APP_MODULE --variant $VARIANT --include-module $FEATURE_MODULE --device $DEVICE"
```

This matching configured prepare must replace the zero-touch APK/fingerprint, install, and launch
successfully.

### Mode B setup and prepare result — 2026-07-17

The bounded target wiring was added to the intended app plus one reachable Compose feature module.
The target Gradle sync and the matching configured `prepare` both passed: the APK installed,
launched, and replaced the prior zero-touch fingerprint. No watcher was started and no Mode B
plugin Start or source edit has happened yet.

Two compatibility adjustments are temporarily present only in the local product checkout for this
smoke. The standalone runtime-client composite must have a root-project name distinct from its
Android subproject, otherwise Gradle type-safe project accessors generate duplicate accessors. Its
AGP declaration must also temporarily match the target's AGP 9.0 line; Gradle rejects a composite
with mixed AGP versions. These are findings to assess separately, not a T38 product-code fix, and
both local changes must be restored before final cleanup. The target wrapper remains at its
original version.

### Android Studio settings and live check

1. Refresh discovery and retain the same app/variant/application id.
2. Set Watched modules to the app plus `$FEATURE_MODULE` only.
3. Uncheck `Use zero-touch bootstrap`. Keep the bundled CLI and preflight enabled.
4. Confirm the preview contains `watch` and does **not** contain `--zero-touch` or `/tmp`.
5. Press Start once and wait for `ready` before editing.
6. Repeat the same temporary marker edit and reverse-edit used in Mode A. Each leg must show
   `reloading` -> `ready`, visibly update, keep the same configured-session PID, and avoid
   `$default`/`$-CC`, redefine-shape, or recomposition errors.
7. Press Stop and require widget `off`.

### Configured-mode coverage regression — 2026-07-17

The Mode B live gate was attempted twice after a matching configured `prepare`, with the
plugin settings above (bundled CLI, `:app` plus the bounded Compose feature, selected debug
variant, one device, and **zero-touch unchecked**). Both edits reached the device but then
stuck the widget on `reloading`.

Device evidence identifies the cause: ART rejected a generated Compose/list lambda because its
declared method count changed from four in the installed APK to three in the patch. This is the
one-method JaCoCo shape drift previously fixed for zero-touch: the bootstrap deliberately
disables Android/unit-test coverage before variants are finalized, whereas the ordinary
configured `dev.hotreload` plugin currently does not. The target's configured debug APK is
therefore coverage-instrumented after Kotlin writes the class outputs watched by the engine.

This is a **product-code blocker**, not a bad module list, a stale fingerprint, or loading UI.
Do not keep retrying body edits in Mode B. The next implementation task must make the configured
plugin apply the same public coverage-disable behavior as the zero-touch bootstrap, add a
configured-mode regression, and make a device `Response.Failure` leave the IDE widget in an
actionable error/rebuild-needed state instead of indefinitely `reloading`. After that fix, reverse
the temporary marker, run a fresh matching configured `prepare`, and repeat the Mode B gate.

### Coverage remediation and bounded retry — 2026-07-18

Configured `dev.hotreload` now disables Android/unit-test coverage through public
`androidComponents.finalizeDsl`, and the configured fixture explicitly enables both coverage modes
before the plugin removes them. Its regression rejects any `$jacocoInit` in the packaged APK. A
device `Response.Failure` now prints the stable rebuild contract; the plugin parser regression
proves that a Reloading batch ends in Rebuild Needed.

A rebuilt local candidate passed plugin tests/build and all pinned Plugin Verifier targets. A fresh
matching configured prepare plus doctor passed on the bounded target, and the produced APK was
verified free of JaCoCo synthetic methods. The bundled candidate CLI reached `watching` with the
same app/library pair. The actual Android Studio watcher received a saved marker in the watched
library and showed `Reloading → Ready`, but the changed text was absent from the library Kotlin
output and the visible target frame remained unchanged. The installed foreground APK was verified
to byte-match the configured target output. This is a product defect: the watch loop compiles only
the app module task, which does not compile this changed library. Restore the marker and stop the
watcher. Do not mark the edit/reverse/PID/Stop gate passed, and do not remove temporary wiring or
composite scaffolding until per-changed-module compile routing is fixed and the smoke is repeated.

### Per-module routing retry: Compose capture failure — 2026-07-18

Per-module compile routing was implemented locally: the owning watched library's selected Kotlin
task runs in the debounced batch, while Gradle retains one invocation for task ordering. Focused
engine coverage asserts a library-only batch does not rely on the app task; the multi-module device
gate now also requires the library Kotlin output hash to change before accepting the hot swap.
Engine/protocol/CLI distribution gates, plugin tests/build, and all pinned Plugin Verifier targets
passed. The fixture device gate was not run because the required retained compatibility scaffold is
incompatible with that fixture's newer AGP line.

After a fresh matching configured prepare, the one permitted Android Studio Mode B retry compiled
and delivered the library patch. The runtime injected desugaring support and redefined the changed
classes, then Compose reported a capture `ClassCastException` in a lazy item lambda: a state object
was treated as a function. Stop was used to end the watcher. A subsequent watcher-stopped clean
uninstall/install/launch was healthy and showed no patch activity, proving this is a patch-specific
runtime defect rather than a baseline install failure.

Do not perform another manual edit, remove wiring/scaffolding, or restore the target yet.
[`T39`](T39-configured-library-compose-capture-crash.md) is the required reproduce/fix plan. Only
after T39's gates pass may the exact Mode B sequence be repeated once.

### Repeat-save result after the local T39 candidate — 2026-07-18

The T39 batch-D8 candidate was force-rebuilt and reinstalled; the engine JAR inside Android
Studio's installed plugin was verified equal to the newly rebuilt CLI engine. A fresh matching
configured prepare/install followed. The first visible library body edit applied, but later
body-only saves did not visibly update the rendered frame, including after plugin Stop/Start.
Device evidence may still show successful redefine batches and zero Compose errors, so the IDE
state and ART acknowledgement cannot be accepted as the visual gate. This rules out the stale ZIP,
APK reinstall, and one-shot watcher-start explanations.

No further ad-hoc edit attempts are authorized. T39 must first extend its deterministic fixture to
prove two sequential visible values in one watcher session, then diagnose/fix the repeat-save
failure. The post-fix manual retry must use two distinct edits before restoration.

## Restoration and final state

1. Remove only the T38 additions from target settings/module Gradle files. Do not use a broad
   `git checkout`, `git restore`, or reset that could erase maintainer work.
2. Confirm the source hash equals the saved baseline and the complete target diff equals the
   saved baseline:

   ```bash
   shasum -a 256 "$TARGET_ROOT/$SOURCE_FILE" | diff -u /tmp/t38-source.before.sha256 -
   diff -u /tmp/t38-target-diff.before <(git -C "$TARGET_ROOT" diff)
   diff -u /tmp/t38-target-status.before <(git -C "$TARGET_ROOT" status --short)
   ```

3. Restore the installed app to zero-touch mode so the clean target Gradle tree and APK agree:

   ```bash
   ./gradlew -q :cli:run --args="prepare --zero-touch --project $TARGET_ROOT --app-module $APP_MODULE --variant $VARIANT --device $DEVICE"
   ```

4. Leave the plugin stopped. Set its zero-touch checkbox back to checked for the next session.
5. Confirm the product checkout remains clean. Do not publish or push as part of T38.

## Failure triage

- No `ready`: do not edit; fix preflight/discovery/preparation first.
- Fingerprint mismatch or stale runtime/protocol: stop, run matching `prepare` for that mode, then
  Start exactly once.
- No `reloading` after save: confirm the exact Android Studio checkout and watched-module list.
- `NoSuchMethodError` mentioning an interface `$default` helper or `$-CC`: record as a regression
  of commit `778d378`; preserve the full plugin output history and device logcat.
- ART redefine member-count/shape rejection in configured mode: this is the known coverage
  parity blocker above. Stop the watcher, restore the temporary marker, and fix the direct
  `dev.hotreload` plugin; do not workaround it by checking zero-touch or ignoring fingerprints.
- Recomposition failure: preserve the last-good frame, restore the edit, and record the first
  exception; do not hide it with reinstall before evidence is saved.
- Configured mode succeeds only with zero-touch still checked: invalid result; repeat after a
  configured prepare and verify the preview has no `--zero-touch`.

## Acceptance

- [x] Preflight passes and the installed Android Studio plugin is local candidate 0.1.8.
- [x] Zero-touch plugin Start reaches Ready using the actual Android Studio target checkout.
- [x] Zero-touch temporary edit and reverse-edit both visibly succeed with a stable PID.
- [x] Configured local-composite preparation succeeds with zero-touch absent.
- [ ] Configured plugin Start reaches Ready, and the same edit/reverse-edit visibly succeeds with
      a stable PID.
- [ ] Plugin Stop returns to Off after both modes; no duplicate/leaked watcher remains.
- [ ] Source bytes and full target diff exactly match the saved pre-test baseline.
- [ ] Target Gradle wiring is removed and the installed app is returned to matching zero-touch
      preparation.
- [ ] T37 findings, `docs/PLAN.md`, `.agents/STATUS.md`, and `.claude/HANDOFF.md` are updated with
      sanitized results. No target identity, package id, local path, or screenshot is committed.
- [ ] Nothing is pushed or published without a separate explicit maintainer instruction.

## Results (fill next session)

| Check | Result | Evidence/classification |
|---|---|---|
| Zero-touch preflight/Ready | PASS | Bundled local 0.1.8 CLI; zero-touch and preflight enabled. |
| Zero-touch edit/reverse/PID | PASS | Both visible reloads succeeded; PID was unchanged. |
| Discovery refresh | FAIL — needs code change | UI remained `Discovering…` while the equivalent bundled CLI inspect completed; manual exact closure was used. |
| Configured sync/prepare | PASS | Bounded local composites/plugins synced; matching configured prepare installed, launched, and wrote a replacement fingerprint. |
| Configured plugin Ready | PASS | The plugin reached Ready with zero-touch unchecked after matching prepare. |
| Configured coverage / fresh prepare / doctor | PASS | Direct plugin coverage disablement applied before variant finalization; fresh bounded prepare and runtime handshake passed; APK inspection found no JaCoCo synthetic method. |
| Configured bundled-CLI Ready | PASS | Rebuilt candidate reached `watching` with zero-touch absent and the bounded app/library pair. |
| Configured edit/reverse/PID | BLOCKED — needs code change | The current local engine/plugin applied the first visible library edit, but later saves did not visibly update even after Stop/Start; ART can still acknowledge redefines without a rendered change. T39 must prove/fix two sequential visible saves. |
| Stop/no watcher | PASS for failure containment | Android Studio Stop ended the failed watcher; a fresh watcher-stopped clean install was healthy. Final Mode B Stop remains part of the post-fix retry. |
| Exact source/Gradle restoration | PENDING | |

## Out of scope

- Publishing 0.1.8, pushing branches/tags, or opening/merging a PR.
- T36 notification cosmetics.
- The remainder of the T37 literal/resource/structural/signature matrix.
- Product-code fixes discovered during the smoke. Record a precise finding first; diagnose/fix in
  a separately authorized task.
- Changing the API-30 device floor, adding manifest overrides, or testing against a `/tmp` clone.
