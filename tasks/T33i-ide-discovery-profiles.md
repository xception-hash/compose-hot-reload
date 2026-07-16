# T33i: IDE discovery, profiles, and resolved watch command
Status: DONE (2026-07-15)
Assignee: agy
Recommended model: Gemini 3.5 Flash (High)
Fallback model: GPT-OSS 120B (Medium)

## Dispatch

```bash
scripts/delegate.sh tasks/T33i-ide-discovery-profiles.md "Gemini 3.5 Flash (High)"
```

## Goal

Complete T33 phase 9 without embedding the engine in the IntelliJ plugin. The plugin must drive
the already-bundled CLI, expose discovery and profiles through structured project settings, and
show the exact resolved `hotreload watch` command that it will execute.

## Spec

- Add a pure Kotlin command/configuration model under `intellij-plugin/src/main/kotlin/dev/hotreload/idea`.
  It must build argument-token lists (never shell-split strings) for `watch` with project, profile,
  app module, application id, variant, target project JDK, watched modules, device, SDK, literals,
  zero-touch, and repeatable Gradle arguments. Retain a clearly-labelled advanced raw override field
  as an append-only token list.
- Persist those structured fields in `HotReloadSettings`. Keep existing stored values compatible:
  blank/new fields must preserve the old default `watch --project … --app-id … --module app` behavior.
- `HotReloadService` must consume only that model when spawning the CLI and expose a rendered,
  safely quoted command line for the settings panel. It must not embed engine classes or change the
  CLI protocol parser.
- Add a refreshable discovery service that invokes the resolved launcher as
  `inspect --project <dir> --json` (adding `--profile` and `--zero-touch` where selected), parses
  schema-v1 JSON with Gson 2.11.0, and returns debuggable app-module/variant/application-id choices
  plus the discovered module closure. Run the subprocess off the EDT and surface errors in the
  settings UI; do not alter the target project.
- Replace the free-form application/module setup with structured UI controls. Discovered app modules,
  debuggable variants, app IDs, and modules must be selectable after Refresh discovery; values must
  remain editable for advanced/unavailable projects. Add controls for profile, target JDK, device,
  zero-touch, literals, repeatable Gradle args, and advanced overrides. Display the resolved CLI
  command read-only, updating whenever controls change.
- Add focused plain-JUnit tests for command tokenization/rendering, legacy defaults, profile/zero-touch
  inclusion, and schema-v1 discovery parsing. Do not require a full IDE fixture.
- Update the root README IDE section and `intellij-plugin/README.md` to describe Refresh discovery,
  profiles, structured fields, raw overrides, and the resolved command.

## Out of scope

- Engine embedding, ComposeBridge/reflection, protocol changes, target-project writes, and changes to
  the watch status protocol.
- Do not modify owner-owned `AGENTS.md`, `docs/WORKFLOW.md`, `scripts/delegate.sh`, `tasks/README.md`,
  or `.agents/`.

## Acceptance

```bash
cd intellij-plugin && ./gradlew test
cd intellij-plugin && ./gradlew buildPlugin
./e2e/run.sh
./e2e/run-multi.sh
```
