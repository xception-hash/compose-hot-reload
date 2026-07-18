# T37: Phase F — Marketplace-plugin production-grade trial findings
Status: IN PROGRESS (2026-07-18)
Assignee: maintainer + coordinator

## Goal

Validate the JetBrains Marketplace plugin and its bundled CLI against a public,
production-grade Android/Compose project. Record only sanitized technical findings and classify
each result as **works**, **needs documentation**, or **needs a code change**.

## Privacy and scope

- Do not put a private project name, employer, package name, local path, screenshots containing
  identifying data, or credentials in this repository.
- The next target will be a public repository supplied by the maintainer in the next session.
- This task evaluates the shipped Marketplace plugin; it does not alter the target project's
  source, Gradle files, or dependency declarations unless the maintainer explicitly asks.

## Marketplace smoke — 2026-07-16

Target: this repository's public `samples/multi-module` fixture, opened in GUI-launched Android
Studio with the Marketplace-installed plugin. Device: an API-36 emulator. The app was already
installed and running.

Configuration observed:

- SDK path and watched module `:app` were populated; other settings were left unchanged.
- The session reached `ready` through the plugin's Start action.

Results:

| Check | Result | Classification |
|---|---|---|
| Marketplace plugin launches its bundled CLI and reaches ready | PASS | works |
| App-module composable body edit is applied live on device | PASS | works |
| State inside the edited `AppCounter` composable | Reset to its initializer, as expected for an invalidated edited subtree | needs documentation |
| State in untouched sibling `FeatureCard` after an `AppCounter` reload | Preserved (`1 → 1`) | works |
| Fixture source restored after the check | PASS; worktree clean | works |

The smoke does **not** prove blank-SDK auto-discovery, because the SDK path was already set, and
it is not the target-project portion of Phase F.

## Production target trial — 2026-07-16

Target identity and local checkout details are intentionally omitted. The supplied public target
is a production-grade, Compose-first multi-module Android project. Safe inspection found AGP
9.0.0, Kotlin 2.3.0, Gradle 9.4.0, a `demoDebug` debuggable app variant, two Android application
modules, and an included `build-logic` build.

Setup classification:

| Check | Result | Classification |
|---|---|---|
| Uninstrumented Gradle discovery | Found the target app and reachable module closure | works |
| Selecting the intended app when two application modules exist | Requires explicit app-module selection | needs documentation |
| Configured integration without target edits | Not viable; the target does not already apply `dev.hotreload`, and this trial forbids persistent target build-file changes | needs documentation |
| Zero-touch bootstrap on the target root build | Root Gradle invocation receives the bootstrap properties | works |
| Zero-touch bootstrap across the target’s included `build-logic` build | The shipped init script is evaluated again with an empty project-property map and aborts on missing `dev.hotreload.bootstrap.jar` | needs a code change |

The failure is reproducible with the shipped 0.1.6 bootstrap and occurs before the target is
built or installed. A harmless target `:app:help` invocation confirmed that Gradle 9.4 normally
accepts `-P` properties and that the empty-property evaluation is specific to the included-build
init-script pass. No target source, Gradle input, or dependency declaration was changed.

Preflight and Start evidence:

- Mandated host preflight passed: JBR 21, build-tools 36.0.0 `d8` and `dexdump`, exactly one
  connected API-36 emulator.
- Shipped CLI doctor passed Java, SDK, device, and zero-touch-project checks. It reported the
  selected debug app as not installed and skipped the runtime handshake because zero-touch
  preparation had already failed.
- Marketplace-plugin GUI Start was not initiated after the shipped bundled zero-touch path was
  reproducibly blocked before app installation; therefore no valid GUI Start result is claimed.
  No watcher was started, so the `watching …` readiness gate was never reached.

Edit matrix classification:

| Edit | Result | Classification |
|---|---|---|
| App-module composable body | Not run; blocked before app install/readiness | needs a code change |
| Literal | Not run; blocked before app install/readiness | needs a code change |
| XML/resource | Not run; blocked before app install/readiness | needs a code change |
| Structural addition | Not run; blocked before app install/readiness | needs a code change |
| Signature change | Not run; blocked before app install/readiness | needs a code change |
| Reachable non-app-module edit | Not run; blocked before app install/readiness | needs a code change |

The next product fix should make the zero-touch init script a no-op for included builds that do
not receive the bootstrap properties, while retaining instrumentation for the selected root-build
module allowlist. After that fix ships, repeat preparation and the full matrix with the same
debuggable variant. The external target checkout was left clean.

## Source-level resolution — 2026-07-16

The source fix makes the bundled init script return immediately when Gradle identifies an
included build through its public `gradle.parent` API. Root-build validation and instrumentation
remain unchanged. The AGP 9 zero-touch fixture now includes and applies a minimal `build-logic`
plugin, then verifies the packaged CLI payload can instrument the selected `:mobile` debug APK
while the composite build evaluates normally. It also invokes the root build with that packaged
init script but without bootstrap properties and requires the existing
`zero-touch bootstrap: missing dev.hotreload.bootstrap.jar` failure.

This covers the source-level composite-build regression only. The shipped 0.1.6 trial remains
blocked until a new bundled CLI/plugin release contains the fix. The Marketplace production-target
matrix has not been retried and is not claimed as passed.

Source-level verification passed the packaged-payload host fixture on AGP 9/JDK 21 (including the
applied composite plugin, APK instrumentation, non-debuggable APK cleanliness, target-tree
immutability, and missing-root-property rejection) and the existing AGP 8/JDK 17 compatibility
leg. The unchanged configured single- and multi-module device regressions also passed. These are
not substitutes for the Marketplace production-target trial.

## Release retry and second compatibility finding — 2026-07-16

Marketplace plugin 0.1.7 shipped the composite-build bootstrap fix and was approved. Retrying
zero-touch preparation against the same target passed the former immediate
`zero-touch bootstrap: missing dev.hotreload.bootstrap.jar` failure, then reached Android manifest
merging. That exposed a second product compatibility issue:

| Check | Result | Classification |
|---|---|---|
| Composite included-build bootstrap | Fixed in approved Marketplace 0.1.7 | works |
| Runtime AAR manifest merge into a minSdk-23 app | Fails: runtime AAR declared minSdk 24 | needs a code change |

The target's device requirement is not the issue: hot reload remains API 30+ only. The runtime
initializer already returns before loading the API-30 hot-reload paths on older devices, so the
AAR's install/merge floor can safely be 23. The source fix lowers that declared floor to 23 and
changes both AGP 9 and AGP 8 zero-touch fixtures to minSdk 23. This is covered by a local plugin
**0.1.8 candidate**; it is not yet published.

Local validation completed: JBR/build-tools/API-36 preflight, rebuilt CLI distribution, and the
plugin's `test` plus `buildPlugin` tasks. The 0.1.8 ZIP must be installed from disk and used for a
fresh target `prepare --zero-touch` before running `verifyPlugin` and submitting a new Marketplace
update. Do not use a manifest override or edit the target project.

## Local 0.1.8 target retry — paused 2026-07-16

The local candidate now completes zero-touch preparation, installs, and launches the selected
debug variant on the API-36 emulator. This proves the minSdk-23 manifest fix. It does not yet prove
working target edits.

The retry uncovered these independent issues:

| Finding | Evidence | Classification |
|---|---|---|
| Preparation was mistaken for an active session | `prepare` installed/launched and wrote the fingerprint, but no watcher process or `watching …` readiness line existed | needs documentation |
| Reachable modules were omitted | Supplying an app id without an explicit module list bypassed discovery and watched only the conventional app module; omitting the redundant app id discovered the full reachable closure | needs a code change |
| Coverage changed installed class shapes | The packaged debug APK contained JaCoCo's synthetic `$jacocoInit`, while watched Kotlin class outputs did not; ART rejected a body edit because method counts differed | needs a code change |
| Compose libraries lacked function-key metadata | Zero-touch enabled `FunctionKeyMeta` only for the app module, so a library edit invalidated the whole tree and corrupted complex Compose state | needs a code change |
| Newer Compose metadata was ignored | The target emits `FunctionKeyMeta` as a runtime-visible annotation; the engine read only the older runtime-invisible form | needs a code change |
| Patch DEX ABI differs from the installed minSdk-23 APK | After targeted group invalidation worked, recomposition crashed with `NoSuchMethodError` for a Kotlin interface `$default` helper. The APK build had desugared the helper, while patch compilation forces D8 `--no-desugaring` | needs a code change |

Implemented but uncommitted source changes at pause time:

- The zero-touch bootstrap disables Android/unit-test coverage for its temporary app build.
- Compose function-key metadata is enabled for every watched project applying the Compose plugin,
  including Android libraries.
- The engine extracts function keys from both runtime-visible and runtime-invisible annotations.
- Host fixtures cover coverage-enabled packaging, Compose-library metadata, and the minSdk-23 AGP
  8 feature module. A focused engine regression covers both annotation retention forms.

Verification completed before the pause:

- Bootstrap compilation, CLI distribution, and the engine test suite passed.
- The full offline zero-touch host suite passed on AGP 9/JDK 21 and AGP 8/JDK 17.
- The real target watcher reached `watching …` with 27 discovered modules and 1,251 classes.
- After the annotation-reader fix, the edit reached `12 redefined, 21 groups invalidated`; device
  logs then exposed the remaining patch-desugaring crash. Therefore the visual edit gate remains
  **failed**, not partially passed.

Pause state: the temporary target edit was restored, the watcher was stopped, and no target source
change from the test remains. The target app process died during the deliberate failing gate; an
older public sample app is foregrounded on the still-running emulator. Resume by fixing patch DEX
desugaring/alignment, not by repeating preparation or changing the API-30 device floor. The plugin
version lookup change and the API-30 runtime floor are unrelated to these failures.

## Patch-desugaring resolution — 2026-07-16

Patch DEX generation now matches the installed APK's desugaring boundary. The engine reads the
installed package's declared minSdk, captures each watched Kotlin compilation's resolved library
classpath during Gradle discovery, and runs D8 with desugaring enabled. File-per-class output
keeps the class being redefined in a one-definition dex, while generated companion/backport
classes are injected separately before redefinition.

A focused engine regression proves that a minSdk-23 call to an interface `$default` helper is
rewritten to the `$-CC` companion owner and that both the primary and auxiliary dexes each contain
exactly one class definition.

The real-target gate passed against the maintainer's active Android Studio checkout and its
byte-for-byte matching installed APK. One watcher reached `watching …` with 27 modules, 1,251
classes, installed minSdk 23, and 331 patch-classpath entries. A reversible body edit produced
three injected desugaring backports followed by `hot-swapped: 12 redefined, 21 groups invalidated`.
The frame visibly changed, the app PID remained stable, and the reverse edit produced the same
successful swap signal. The maintainer's pre-existing source modification was restored exactly;
the watcher was then stopped. No target identity, package identifier, local path, or screenshot is
recorded here.

Host verification passed 155 engine tests, protocol tests, CLI compile/distribution, packaged
zero-touch distribution verification, and the AGP 9.2.1/JDK 21 host fixture. The AGP 8 fixture was
skipped in this final run because `JAVA17_HOME` was unavailable; it had passed earlier in this same
milestone before the patch-desugaring changes. JetBrains Plugin Verifier also reports the local
0.1.8 candidate Compatible with all three configured IDE baselines; only the three pre-existing
status-widget deprecation notices remain.

## Configured-mode coverage parity blocker — 2026-07-17

T38 Mode B reached the plugin's Ready state after a fresh matching configured `prepare`, but two
reversible body-edit attempts both stuck the widget on `reloading`. Device logs showed ART error
67 for a generated Compose/list lambda: the installed definition had four declared methods while
the patch had three. The one-method delta is JaCoCo instrumentation.

This explains why the local 0.1.8 **zero-touch** half passed while configured Mode B failed. The
zero-touch bootstrap disables Android and unit-test coverage before variants are finalized, so the
installed APK and watched Kotlin outputs have matching class shapes. The ordinary configured
`dev.hotreload` plugin does not currently apply that safeguard; the target's debug APK remains
coverage-instrumented after Kotlin writes the output the engine uses for patch D8.

Required product work before retrying configured Mode B: mirror the bootstrap's public
coverage-disable behavior in the configured plugin, add a configured-mode regression, and ensure
a device `Response.Failure` leaves the IDE widget in a clear error/rebuild-needed state rather
than indefinitely `reloading`. This is not a target setup, module-selection, fingerprint, or
loading-UI problem. Stop the watcher and restore any temporary marker before applying the fix and
running another matching configured `prepare`.

## Configured-mode coverage remediation — 2026-07-18

The direct `dev.hotreload` plugin now disables Android and unit-test coverage through the public
`androidComponents.finalizeDsl` lifecycle, matching zero-touch before variants are finalized. The
configured single-module fixture deliberately enables both coverage modes and its configured
regression rejects any packaged `$jacocoInit` shape. `Response.Failure` from the device is now
translated by the watch loop into the existing `cannot hot-swap:` plus reinstall contract, and a
plugin parser regression proves that this terminal output changes `Reloading` to `Rebuild Needed`.

Host engine/protocol/CLI gates, Gradle-plugin compilation, IntelliJ-plugin tests/build, and Plugin
Verifier all passed; Verifier reported compatibility on the three configured IDE baselines with
only the known status-widget deprecations. The fixture's full configured emulator gate could not
assemble while the documented temporary runtime-client composite is aligned to the real target's
older AGP line; that scaffold was deliberately left untouched.

A fresh bounded configured prepare and matching doctor on the production target passed, including
the runtime handshake. Inspection of the resulting APK confirmed that it contains no JaCoCo
synthetic method. The rebuilt local candidate's bundled CLI reached `watching` with the bounded
app/library pair. The Android Studio watcher then received a saved body-only marker in the watched
configured Compose library and reported `Reloading → Ready`, but the marker was absent from that
library's watched Kotlin output and the visible frame remained unchanged. The installed foreground
app was verified to byte-match the configured target APK, so this is not an installation or
fingerprint issue. `WatchSession` always invokes the app module compile task; in this target that
task does not compile the changed library. This is a **needs-code-change** multi-module
compile-routing defect. Restore the marker and stop the watcher; do not claim configured
edit/reverse, widget transition, or UI Stop. Per T38, temporary target wiring and local composite
compatibility scaffolding remain pending the fix and retry.

## Configured library patch capture failure — 2026-07-18

The locally rebuilt candidate includes per-changed-module Kotlin compile routing: a watched
library batch uses its selected Kotlin task rather than assuming the app task covers it. Focused
engine coverage proves library-only routing, and the multi-module device gate was strengthened to
require the library class output hash to change before accepting a hot swap. Engine/protocol/CLI
gates, plugin tests/build, and all pinned Plugin Verifier baselines passed. The fixture device gate
could not assemble because the intentionally retained local composite scaffold uses the production
target's older AGP line; the scaffold was not changed just to run that fixture.

A fresh bounded configured prepare then passed and the plugin performed its single Android Studio
Mode B retry. The library patch was compiled and delivered: the runtime injected desugaring support
and redefined the changed class set. Compose then captured a `ClassCastException` in a lazy item
lambda, treating a state object as a function value. This is a patch-path failure: after Stop, a
clean uninstall/install/launch with no watcher was healthy and showed no redefine/injection
activity. The failure is neither a stale APK nor the earlier app-only compile omission.

The watcher is Off; temporary target wiring and local compatibility scaffolding remain in place.
Do not repeat the manual edit or restore the scaffolding yet. [`T39`](T39-configured-library-compose-capture-crash.md)
defines the required deterministic reproducer, patch-set comparison, fix, and gates.

## Configured repeat-save failure — 2026-07-18

The local candidate was force-rebuilt after the T39 coupled-D8 change and the engine embedded in
the Android Studio-installed plugin was confirmed to match the rebuilt CLI engine. After a fresh
matching configured prepare/install, the first visible body edit applied. Later saves in the same
configured watcher session did not visibly update the frame, even after Stop/Start. Device logs
can still record successful redefine batches and zero Compose errors; those signals are therefore
not sufficient acceptance evidence.

This is not resolved by reinstalling the APK or plugin. T39 is now blocked until its deterministic
configured regression applies two distinct sequential edits in one session and proves both values
render. Do not retry the real target manually, restore the temporary scaffolding, publish, or push
until that regression and the revised T38 gate pass.

## Configured repeat-save resolution — 2026-07-18

The direct configured plugin enabled Compose FunctionKeyMeta only in the application module,
whereas zero-touch enabled it in every module applying the Compose compiler plugin. The production
target's edited library lambda therefore lacked the stable group-key annotation even though the
equivalent zero-touch build had complete metadata. The configured plugin now applies the metadata
option to every Compose module and fails loud when it cannot be applied.

The configured-capture regression now uses Kotlin 2.3 and a target-shaped staggered-grid library
item lambda. In one watcher session it requires two distinct library output hashes, two visible
values, a type-correct captured callback after each edit, and stable PID. It passes. After a fresh
matching configured prepare, the real Android Studio plugin also visibly applied the first and
second library edits, restored the original frame byte-for-byte, retained one PID, and stopped to
Off without a leaked watcher. T39 is complete. T38 then removed the temporary target wiring and
local compatibility scaffold, retained only the maintainer-owned source baseline, ran a matching
zero-touch prepare/install/launch, restored the zero-touch plugin setting, and left the widget Off.

## Discovery process-output and Settings-modality fix — T40 DONE 2026-07-18

The large-target Refresh discovery attempt remained at `Discovering…`, while the exact bundled
CLI `inspect --json --zero-touch` control completed and returned valid discovery data. This rules
out Gradle inspection itself and isolates the failure to the IDE integration.

Inspection of the plugin found a deterministic pipe deadlock: the discovery service reads stdout
to EOF before reading stderr and waiting for the child. A noisy Gradle child can fill its stderr
pipe and block before closing stdout, while the parent waits forever for stdout EOF. The
Doctor/preflight path repeats the same sequential drain pattern. This is a **plugin process-I/O
defect**, not a target compatibility or CLI discovery defect.

T40 added one shared concurrent collector that preserves stdout/stderr separation, plus a real
child-JVM 2 MiB pipe-saturation regression. The live retry exposed a second issue: the completed
background result used the default IntelliJ modality and was deferred behind the modal Settings
dialog. Delivering it in the initiating dialog's modality fixed the stuck label. Host acceptance
and Plugin Verifier pass; the large-target Refresh discovered two modules, matching Doctor passed,
Start reached Ready, and Stop returned Off. T40 is complete.

## Pending target-project matrix

1. The maintainer approved publication and the signed 0.1.8 update was submitted to JetBrains
   Marketplace. Wait for it to become available before this user-facing matrix; its source is in
   [PR #25](https://github.com/xception-hash/compose-hot-reload/pull/25), which still needs its
   required checks and review before merge.
2. Start from the available Marketplace plugin with normal user-facing settings and capture the full
   preflight/doctor result.
3. Verify app-module body edit and state behavior.
4. Verify literal edit, XML/resource edit, structural addition, signature change, and an edit in
   a reachable non-app module where the target has one.
5. After each result, record the plugin status/log line and a sanitized visual observation.
6. Restore every temporary target edit before ending the trial.

## Acceptance

- [x] Public production-grade target cloned and safely inspected; target inputs remain clean.
- [x] Mandated host preflight and shipped-CLI doctor baseline recorded; the shipped zero-touch
      path is blocked before readiness by the included-build failure.
- [x] Pending matrix recorded as blocked with exact sanitized evidence and classifications.
- [x] No target edits, screenshots, credentials, package identifiers, or local paths committed.
- [x] Source-level composite-build regression fixed and covered with packaged-payload AGP 9 and
      existing AGP 8/JDK 17 host coverage; root bootstrap-property rejection retained.
- [x] Local 0.1.8 candidate merges into the minSdk-23 target and reaches zero-touch preparation.
- [x] Patch DEX desugaring matches the installed APK and a real target edit survives targeted
      recomposition with a visibly changed frame.
- [x] Local 0.1.8 passes Plugin Verifier on all three configured IDE baselines.
- [x] T39 capture/repeat-save fix plus T38 configured-integration first edit, second edit,
      source restoration, stable PID, and Stop/Off pass.
- [x] T38 target Gradle wiring/local compatibility scaffold removed and a matching zero-touch
      preparation restored before submission.
- [x] T40 concurrent process-output and Settings-modality fixes pass the deterministic regression,
      Plugin Verifier, and large-target discovery/Doctor/Start/Stop gate.
- [x] 0.1.8 submitted after explicit maintainer approval; Marketplace availability remains an
      external follow-up, and PR #25 awaits required checks/review.
- [ ] GUI-launched Marketplace Start, instrumented app build/install, and full edit matrix
      executed after a release contains both product fixes.
