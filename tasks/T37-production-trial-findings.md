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

## Pending target-project matrix

1. Start from the Marketplace plugin with normal user-facing settings and capture the full
   preflight/doctor result.
2. Verify app-module body edit and state behavior.
3. Verify literal edit, XML/resource edit, structural addition, signature change, and an edit in
   a reachable non-app module where the target has one.
4. After each result, record the plugin status/log line and a sanitized visual observation.
5. Restore every temporary target edit before ending the trial.

## Acceptance

- [ ] Public production-grade target cloned and built with an API-30+ debuggable app on one device.
- [ ] Marketplace plugin preflight/start observation recorded.
- [ ] Pending matrix executed or each blocked item recorded with exact sanitized evidence.
- [ ] All temporary edits restored; no target details or credentials committed here.
