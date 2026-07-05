# T26: IntelliJ/Android Studio plugin MVP (Phase 5)
Status: TODO
Assignee: agy (Jay for the runIde manual checks)
Priority: 6 of T21–T27

## Goal
Phase 5 MVP: hot reload without leaving the IDE. Decision (made, do not revisit): the plugin
does NOT embed the engine in-process — it **spawns the CLI** (`hotreload watch`) and parses its
stdout. Rationale: Gradle Tooling API + our classpath inside the IDE JVM is a known pain source;
the CLI's log lines are already the stable machine-readable surface we control, and the CLI
stays the single source of truth.

## Spec
1. New top-level `intellij-plugin/` Gradle project (IntelliJ Platform Gradle Plugin 2.x,
   `intellij { }` target = latest stable IntelliJ IDEA Community; do NOT target Android Studio
   specifically for the MVP — it runs there too). Own build like runtime-client (root project
   not touched); wire `runIde`, `buildPlugin`.
2. Plugin surface (keep it to exactly this):
   - **Settings** (project-level, persistent): project dir (default = project base path),
     app id, modules CSV, SDK path, extra CLI args. One settings page.
   - **Start/Stop action** (Tools menu + status-widget click): starts the CLI as a
     `GeneralCommandLine` process (`hotreload watch ...` — resolve the CLI via a configurable
     path to the repo's installDist for now), stops via destroying the process
     (the CLI's own shutdown already kills the session; verify no orphaned
     `dev.hotreload.cli.MainKt` after stop — same pkill contract as e2e).
   - **Status bar widget** with states: Off / Starting / Ready / Reloading / Error(n) /
     Rebuild-needed. Driven purely by parsing CLI stdout lines: the session-start banner →
     Ready; a save-triggered compile line → Reloading; `swapped:`/`resource-swapped:`/
     `injected:` → Ready (flash the latency from the line); `compose-error`/`error:` lines →
     Error with tooltip = first line; `rebuild required`/`reinstall required` → Rebuild-needed
     sticky until next successful swap. Read the exact line prefixes from
     `engine/.../WatchSession.kt` logging and list them in a `CliProtocol.kt` with unit tests
     against sample transcript fixtures (fixtures = copy real lines from an e2e watch log).
   - **Save integration**: none needed — the CLI's own file watcher handles saves. The plugin
     only ensures "save on frame deactivation" isn't required: document that autosave timing
     adds latency and recommend enabling explicit auto-save; do not hook typing events in MVP.
   - **Notifications**: one balloon on Error and on Rebuild-needed (with a "run build" hint);
     nothing else.
3. No gutter icons, no error banners in-editor, no auto-start, no bundled CLI binary in MVP.
4. Docs: `intellij-plugin/README.md` — build, install-from-disk, configure, known limits.

## Out of scope
- Publishing to JetBrains Marketplace; signing.
- Embedding engine classes; Gradle Tooling API from the IDE process.
- Android Studio-specific APIs (logcat panes, run configurations).
- Any change to engine/cli output format (if a needed line is missing/ambiguous, note it in the
  review notes instead of changing engine).

## Acceptance
1. `cd intellij-plugin && ./gradlew test buildPlugin` → green; plugin zip produced.
   `CliProtocol` unit tests cover every state transition from fixture lines.
2. `./gradlew runIde` manual script (Jay): open `samples/single-module`, configure, Start →
   widget Ready; edit `Counter.kt` body → widget flashes Reloading→Ready and the emulator
   updates (same PID via `scripts/ui-state.sh`); make a signature change → Rebuild-needed
   balloon; Stop → `pgrep -f dev.hotreload.cli.MainKt` empty.
3. Repo root `./e2e/run.sh` unaffected (no root-project changes).
