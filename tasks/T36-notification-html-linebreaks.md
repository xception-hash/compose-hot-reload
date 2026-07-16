# T36: IntelliJ notification bodies collapse newlines (use `<br>`)
Status: OPEN (cosmetic follow-up from T35, 2026-07-16)
Assignee: unassigned
Recommended model: Sonnet (pure string change + unit-test) — coordinator re-verifies

## Problem (observed live 2026-07-16, Android Studio 2026.1.x, 0.1.6 plugin)
The pre-Start preflight balloon rendered its `[FAIL]` bullets, then ran the trailing
`\n\nFix these, or click "Start anyway"…` line straight onto the end of the last bullet:

> • Runtime handshake: skipped due to missing device **Fix these, or click "Start anyway"** to launch regardless.

IntelliJ renders `Notification` content as **HTML**, so plain `\n` line breaks collapse to a
single space. The text is correct — only the wrapping/line breaks are wrong. Purely cosmetic;
does not block T35 (0.1.6 shipped with it).

## Fix
Use HTML line breaks in the text that IntelliJ renders as a notification body:
- `HotReloadPreflight.notice()` builds `body` with `\n` between the heading, each `• ` bullet /
  raw line, and the trailing "Fix these…" sentence — swap those separators for `<br>` (or
  `<br/>`). Keep the pure-function shape so `HotReloadPreflightTest` still covers it (assert the
  body contains `<br>` and no bare bullet-then-sentence run-on).
- Audit the other IntelliJ balloon bodies for the same latent bug and fix consistently:
  `HotReloadService.onStateEntered` REBUILD_NEEDED balloon (embeds a `"\n"` before the
  "Run a full install…" hint), and any multi-line `balloon(...)` content.
- Do NOT touch `reportBody()` — that string is GitHub-flavoured **Markdown** for the issue URL,
  not a notification body, and must keep its `\n`/```` ``` ```` fences.

## Acceptance
- [ ] Preflight balloon shows the heading, each failure/raw line, and the "Fix these…" sentence on
      their own visual lines in the IDE (manual check in `runIde` or a real install).
- [ ] `HotReloadPreflightTest` asserts the notice body uses `<br>` separators (regression guard).
- [ ] `./gradlew test` + `verifyPlugin` still green; no behavior change beyond rendering.

## Files
- `intellij-plugin/.../HotReloadPreflight.kt` — `notice()` body construction.
- `intellij-plugin/.../HotReloadService.kt` — `onStateEntered` REBUILD_NEEDED balloon (+ audit).
- `intellij-plugin/src/test/.../HotReloadPreflightTest.kt` — separator assertion.

## Notes
- Plugin-only, protocol unchanged (v8), no AAR reinstall. Bump pluginVersion when shipped.
- Related: T35 (introduced `notice()`), T34 (the preflight), T26 (notification infra).
