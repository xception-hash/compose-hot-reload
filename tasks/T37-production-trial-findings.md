# T37: Phase F — Marketplace-plugin production-grade trial findings
Status: IN PROGRESS (2026-07-16)
Assignee: maintainer + coordinator

## Goal

Validate the JetBrains Marketplace 0.1.6 plugin and its bundled CLI against a public,
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

## Pending target-project matrix

1. After the zero-touch included-build fix ships, start from the Marketplace plugin with normal
   user-facing settings and capture the full preflight/doctor result.
2. Verify app-module body edit and state behavior.
3. Verify literal edit, XML/resource edit, structural addition, signature change, and an edit in
   a reachable non-app module where the target has one.
4. After each result, record the plugin status/log line and a sanitized visual observation.
5. Restore every temporary target edit before ending the trial.

## Acceptance

- [x] Public production-grade target cloned and safely inspected; target inputs remain clean.
- [x] Mandated host preflight and shipped-CLI doctor baseline recorded; the shipped zero-touch
      path is blocked before readiness by the included-build failure.
- [x] Pending matrix recorded as blocked with exact sanitized evidence and classifications.
- [x] No target edits, screenshots, credentials, package identifiers, or local paths committed.
- [ ] GUI-launched Marketplace Start, instrumented app build/install, and full edit matrix
      executed after the product fix.
