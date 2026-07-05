# T24: Live-literals fast path (Phase 3, flag-guarded)
Status: TODO
Assignee: agy (device needed for the tail; engine part is host-only)
Priority: 5 of T21–T27

## Goal
Sub-100ms loop for literal-only edits (string/number/boolean constants inside composables):
skip Gradle+d8+redefine entirely and push the new constant through Compose's live-literals
mechanism. Flag-guarded because `liveLiterals` instrumentation adds debug-build overhead.
Design is fixed below — no open questions; the on-device setter surface is proven AOSP code
(`third_party/.../deploy/agent/runtime/src/main/java/com/android/tools/deploy/instrument/
LiveLiteralSupport.java` — read it first; it shows the exact runtime entry points and the
enable/disable dance).

## Spec
1. **Opt-in wiring** (`gradle-plugin/HotReloadPlugin.kt`): when Gradle property
   `hotreload.liveLiterals=true`, enable the Compose compiler's live-literals v2 mode. The
   exact option name under Kotlin 2.4's built-in Compose compiler must be verified first
   (mechanical: check the pinned `composeCompiler` extension / plugin CLI options for
   `liveLiterals`-named flags — historic names are `liveLiterals` and `liveLiteralsV2Enabled`;
   use the v2 one). Prove it took effect by finding `LiveLiterals$*` classes in the sample's
   compiled output before writing any engine code. Off by default; README gets a short
   flag-guarded section.
2. **Key table, not key derivation**: with liveLiterals on, each source file compiles to a
   `LiveLiterals$<FileName>Kt` class whose members carry
   `@androidx.compose.runtime.internal.LiveLiteralInfo(key, offset)`. Engine
   (`FactsExtractor`-adjacent, new `LiteralTable.kt`): after the initial build, extract per
   source file the list `(key, offset, constantType)` from those classes with ASM. NEVER
   re-derive Compose's key-naming algorithm — offsets are the lookup.
3. **Detection before compile** (`WatchSession.onSave`, flag-guarded): keep the previous text
   of watched `.kt` files (session cache). On save, diff old→new. If the diff is exactly one
   contiguous replacement whose old and new spans both lex as a single Kotlin
   literal (string content without template `$`, int/long/float/double, true/false) and
   the surrounding context is byte-identical → literal fast path: look up the table entry whose
   offset matches the edit start (adjusted by this session's cumulative offset shifts for that
   file — maintain per-file shift list, updated on every save incl. non-literal ones). No match
   → normal path. Anything else (multiple hunks, template strings, `const val`) → normal path.
   IMPORTANT: after a literal push, ALSO run the normal compile+diff in the background so the
   baseline snapshot/table stays truthful; suppress the redundant swap when the classifier
   reports body-only on the same function (the literal already landed) by comparing against the
   just-pushed edit — simplest correct rule: still perform the swap (idempotent, state-safe),
   fast path only wins the latency race.
4. **Protocol** `LiteralUpdate` opcode `0x09` (bump to the next free version — coordinate with
   T25/T27 which both touch the version; add codec in BOTH Wire.write and Wire.read):
   `{ key: String, type: Byte (0=String,1=Int,2=Long,3=Float,4=Double,5=Boolean,6=Char), value }`.
   Runtime-client handler (main thread): enable once via
   `androidx.compose.runtime.internal.LiveLiteralKt.enableLiveLiterals()` equivalent —
   copy the exact reflective sequence from AOSP `LiveLiteralSupport.java` (it handles the
   `$runtime_release` mangling and the "seen" state) — then
   `updateLiveLiteralValue(key, value)`. Respond Ack; recomposition is driven by the live-literal
   state object itself (no invalidate op needed — that is the point).
5. **CLI**: `--literals` flag on `watch` → engine Config flag; watch startup verifies the app
   was built with `hotreload.liveLiterals=true` (LiveLiterals classes present in the snapshot)
   and fails loud if not.
6. e2e: new case in `run.sh`, guarded so it SKIPs (not fails) when the sample wasn't built with
   the property: rebuild sample with `-Photreload.liveLiterals=true`, watch `--literals`, edit
   `"Count: $count"`? NO — template strings are excluded; edit the `ResourceLabel` prefix or a
   plain string literal in the sample (add `Text("literal: v1")` testbed line), assert on-screen
   change, same PID, and assert the watch log line `literal-pushed:` appears with latency < 500ms.

## Out of scope
- Colors/dp/sp literal types beyond the 7 above; array literals.
- Turning the flag on by default; CI matrix for the flag.
- Any interpreter/T27 interaction.

## Acceptance
From repo root, emulator booted:
1. Flag off (default): `./e2e/run.sh` current-count green twice, byte-identical behavior
   (no LiveLiterals classes in the snapshot, fast path dormant).
2. Flag on: manual watch session — literal edit lands on screen with `literal-pushed:` logged;
   a *non*-literal edit in the same session still hot-swaps normally; state + PID preserved
   across both.
3. New e2e case green when run with the property, SKIP without.
4. `pgrep -f dev.hotreload.cli.MainKt` empty after runs.
