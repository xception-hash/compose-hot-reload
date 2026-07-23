# Compose Hot Reload for Android — Project Plan

## Status (2026-07-23) — 0.2.0 published and Marketplace smoke verified

T01–T28 and the full T33 project-agnostic roadmap (phases 1–10) are done and **merged to `main`
(PR #19, `f674233`)**. The product works end-to-end (body edits, structural adds, multi-module,
resources incl. bitmaps, ~22 ms live literals, interpreter for removals/hierarchy/**signature
changes** incl. composables via lambda proxies, zero-touch `hotreload start`, IDE plugin with
discovery/profiles, doctor, e2e 15/15). The IntelliJ/Android Studio plugin **0.2.0 is approved
and live on the JetBrains Marketplace**. [Release 0.2.0](https://github.com/xception-hash/compose-hot-reload/releases/tag/0.2.0)
aligns that plugin with the CLI, Gradle plugin, and runtime-client artifacts through GitHub and
[JitPack](https://jitpack.io/#xception-hash/compose-hot-reload/0.2.0). Engineering and
**Phase F are complete**:
the Marketplace-plugin smoke passed on the multi-module fixture. The first public production
target blocker (the zero-touch init script aborting in an included `build-logic` build) is fixed
in 0.1.7. The retry then exposed a second blocker: the runtime AAR declared minSdk 24 while the
target declares 23. Local **0.1.8** now fixes that merge floor plus the production-trial coverage,
Compose-metadata, discovery, and patch-desugaring findings; source CLI device verification and
Plugin Verifier pass. T38's zero-touch plugin half now passes (Ready, visible reversible edit,
stable PID, Stop -> Off), and its bounded configured local-composite sync/prepare now passes.
The direct configured plugin now disables Android/unit-test coverage in the same public
`androidComponents.finalizeDsl` phase as zero-touch; its coverage-enabled configured regression
also checks that packaged DEX contains no JaCoCo shape additions. Device-response failures now
emit the stable rebuild contract, which moves the IDE out of Reloading. A fresh bounded configured
prepare and doctor pass, and its APK is JaCoCo-free. Per-module compile routing and coupled batch
D8 are implemented and verified. T39 found the final configured-library parity gap: zero-touch
enabled Compose FunctionKeyMeta generation in every Compose module, while the direct plugin did
so only for the app. The configured plugin now applies that option explicitly to every Compose
module and fails loud if it cannot. A Kotlin-2.3 target-shaped fixture visibly passes two
sequential library saves with capture/callback checks and stable PID. The real Android Studio
Mode B gate also passes first edit, second edit, restoration, stable PID, and Stop -> Off. T38 is
complete: its temporary target wiring and local compatibility scaffold were removed, only the
maintainer-owned source baseline remains, a matching zero-touch prepare installed/launched the
clean target, the plugin setting is back on zero-touch, and the widget is Off. T40 is complete:
discovery and Doctor now drain child stdout/stderr concurrently and retain stream separation; a
real 2 MiB stderr-before-stdout child regression prevents the pipe deadlock. The live retry found
and fixed the Settings-modal callback deferral too. Refresh discovered two modules, matching
Doctor passed, Start reached Ready, and Stop returned Off. The signed **0.1.8** update is approved
on JetBrains Marketplace and [PR #25](https://github.com/xception-hash/compose-hot-reload/pull/25)
is merged. The Marketplace production retry passes preparation/Doctor/Start, app and library body
edits, live literals, and watched XML resources. Its structural-reversion failure was an ABI gap:
the interpreter received raw JVM calls to interface static helpers while the minSdk-23 APK had
moved those helpers to D8 `$-CC` companions. Interpreted payloads now receive the same owner rewrite
as compiled patches. A target-shaped minSdk-23 configured device gate passes two body saves,
structural addition, structural removal through the interpreter, callback use, and stable PID.
The rebuilt local plugin then passed the production structural add/revert and a visible narrow
composable-signature add/revert, retaining one PID and ending at Stop -> Off with clean target
restoration. The IDE parser now recognizes successful `interpreted:` completions as Ready.
Optional housekeeping and one cosmetic follow-up (T36) remain separate. This table is the ONE
canonical roadmap — update it here, link it elsewhere.

| # | Work | Size | Executor |
|---|---|---|---|
| 0 | ✅ DONE — commits pushed, CI e2e green (PR #2 merged) | — | the maintainer |
| 1 | ✅ DONE 2026-07-07 — `tasks/T29-release-v0.1.md` v0.1.0 tagged, GitHub Release, JitPack resolvable | — | done |
| 2 | ✅ DONE 2026-07-06 — `tasks/T28-proxies-codegen.md` all 5 steps; checkpoints A/B/C + e2e case 15 validated live (Fable) | — | done |
| 3 | ✅ DONE 2026-07-10 — `tasks/T30-robustness-leftovers.md` all 4 items: super/invokespecial verified, synchronized-block SIGABRT found + classifier MONITORENTER→Rebuild guard, jpg/jpeg supported, 9-patch/fonts documented unsupported | — | done |
| 4 | Stretch (post-v0.1): Compose N-1 shims, suspend-lambda proxies | unbounded | defer |
| T33 | Project-agnostic generalization: shared config model, Gradle discovery, profiles, zero-touch init-script mode (10 phases) | large | ✅ DONE 2026-07-15, MERGED 2026-07-16 (PR #19 `f674233`) — all 10 phases; CI matrix includes AGP 8 standalone-KGP flavored fixture and AGP 9 built-in Kotlin, and `start` is device-verified in configured and zero-touch modes |
| Marketplace | Publish IntelliJ/Android Studio plugin to the JetBrains Marketplace | — | ✅ DONE — v0.1.4 approved + live |
| T34 | Plugin 0.1.5: first-run UX (pre-Start `hotreload doctor` preflight → actionable notification with "Start anyway") + IDE-compat bump (platform 2025.1→2026.1.4; verifier pins 2025.1/2026.1.4/262 all Compatible) | small | ✅ DONE 2026-07-16, MERGED (PR #20 `fb10af2`). Device testing surfaced two preflight UX bugs → superseded by T35 (0.1.5 not published) |
| T35 | Plugin 0.1.6: preflight surfaces fatal `hotreload:` aborts (raw output + Report-on-GitHub action, not a bulletless balloon), Android SDK auto-discovery (local.properties/`ANDROID_HOME`/platform default → injected as `ANDROID_HOME`) for GUI-launched IDEs, and `~` expansion in path settings | small | ✅ DONE 2026-07-16 — test 43/43, verifyPlugin Compatible×3, device-verified; **0.1.6 published**. MERGED (PR #21 squash → `9d8e42c`) |
| T36 | Cosmetic: IntelliJ renders notification bodies as HTML and collapses `\n` line breaks (the preflight "Fix these…" sentence runs onto the last bullet) → use `<br>` | tiny | 📋 QUEUED — `tasks/T36-notification-html-linebreaks.md`; bundle into the next version bump |
| T37 | Phase F: Marketplace-plugin trial on a public production-grade Android project | medium | ✅ DONE 2026-07-18 — Marketplace 0.1.8 approved and PR #25 merged. The full preparation/Doctor/Start, body, literal, and XML matrix passed; the rebuilt local plugin then passed structural add/revert and a visible narrow composable-signature add/revert with stable PID, Ready transitions, Stop -> Off, no watcher, and exact source restoration. |
| T38 | Maintainer Android Studio smoke of local plugin 0.1.8 in zero-touch and configured local-composite modes | medium | ✅ DONE 2026-07-18 — both modes pass; configured first/second/restoration edits retained one PID, Stop reached Off, temporary wiring/scaffold was removed, and matching zero-touch state was restored. |
| T39 | Configured watched-library repeat edit fails to update the rendered Compose frame | medium | ✅ DONE 2026-07-18 — configured plugin now enables FunctionKeyMeta in every Compose module; Kotlin-2.3 two-save fixture and real Android Studio Mode B first/second/restoration gate pass with stable PID. |
| T40 | Android Studio discovery/Doctor can deadlock while draining a noisy Gradle child process | medium | ✅ DONE 2026-07-18 — concurrent stream-separating collector and deterministic 2 MiB real-child regression; the Settings-modal callback now returns to the active dialog. Plugin host gates pass, and large-target Refresh discovered 2 modules, Start reached Ready, and Stop returned Off. |
| T41 | Narrow 0.2.0 release: stable configured-plugin/profile path, AI-assisted setup, aligned artifacts, and bounded packaged-artifact validation | large | ✅ DONE 2026-07-23 — [PR #29](https://github.com/xception-hash/compose-hot-reload/pull/29) merged as `7f3399b`; tag, GitHub Release, JitPack resolution, approved Marketplace 0.2.0 archive, and its configured production smoke are verified. |
| T43 | Marketplace-plugin onboarding: verified Marketplace link plus clear install/configure/Start guidance in the listing and public docs | small | ✅ DONE 2026-07-23 — Marketplace 0.2.0 publishes the reviewed onboarding description; the public archive, configured Start → Ready → visible edit/revert → Stop smoke, and final repository wording are verified. |

The 0.2.0 release is complete. Optional housekeeping remains outside its fixed scope:
- **0.2.0 release provenance:** tag `0.2.0` targets merged PR #29 commit `7f3399b`;
  [GitHub Release](https://github.com/xception-hash/compose-hot-reload/releases/tag/0.2.0) contains
  the signed IDE ZIP and CLI distribution; JitPack resolves the real marker, plugin module, and
  runtime AAR. The approved [Marketplace plugin](https://plugins.jetbrains.com/plugin/32850-compose-hot-reload)
  was downloaded and completed the configured production smoke.
- **Release provenance:** ✅ DONE 2026-07-20 — tag `0.1.8` targets merged PR #27 commit
  `6bca51b`; [GitHub Release](https://github.com/xception-hash/compose-hot-reload/releases/tag/0.1.8)
  contains the signed IDE-plugin ZIP and CLI distribution; JitPack publishes and a clean consumer
  resolves the Gradle plugin, marker, and runtime AAR at 0.1.8. Marketplace 0.1.8 was already live.
- **PatchServer wedge robustness:** re-arm `soTimeout` between sessions (long-standing nice-to-have).
- Stretch item 4 above (Compose N-1 shims, suspend-lambda proxies) if the project is extended.

## Context

Jetpack Compose has no true hot reload on Android. JetBrains' [compose-hot-reload](https://github.com/jetbrains/compose-hot-reload) works only on desktop JVM targets because it depends on the JetBrains Runtime's enhanced class redefinition (DCEVM, `-XX:+AllowEnhancedClassRedefinition`) — Android's ART runtime has no equivalent. Android Studio's Live Edit is limited (function-body edits, fragile, IDE-locked). The only Android solution today is [HotSwan](https://hotswan.dev/), which is paid and closed source.

**Goal:** an open-source, Flutter-style hot reload for Jetpack Compose on real Android devices/emulators: save a `.kt` file → UI updates in ~1s with app state preserved — **with zero modifications to existing application code** (a Gradle plugin wires everything into debug builds automatically). Target: Android 11+ (API 30) dev devices, debuggable builds only (a hard ART requirement, same as HotSwan/Live Edit). Public OSS project.

**Decisions already made with the user:**
- v1 is a **CLI daemon** (`hotreload watch`); IDE plugin is a later phase reusing the same engine.
- Minimum device API for hot-reload sessions: **API 30** (Android 11), because ART's *structural class redefinition* (add methods/fields) only exists there.
- Public OSS → design a version-shim boundary for Compose runtime internals from the start, but the first milestones pin one Kotlin/Compose/AGP version.
- **Not** server-driven UI. Real compiled code runs on device.

## How this is technically possible (research summary)

This is exactly how HotSwan works (confirmed from their docs) and every primitive is publicly available:

1. **ART JVMTI agent** — On debuggable apps (API 26+), a native agent `.so` can be loaded into the app process via `android.os.Debug.attachJvmtiAgent(...)` (called from a debug-only library at startup) or `adb shell am attach-agent`. JVMTI on ART supports `RedefineClasses` (method-body swaps) and, on **API 30+**, the **structural redefinition extension** (`RedefineClassesStructurally`): add new methods and fields (initialized to 0/null); cannot remove members or change existing signatures. Existing object instances survive the swap — the next call uses new method bodies. This is the state-preservation magic: no objects are recreated, so ViewModels, `remember{}`, navigation stacks all survive.
2. **Compose recomposition hooks** — The Compose *runtime* (app-side library, not framework, so no hidden-API restrictions; internals reachable via reflection):
   - `androidx.compose.runtime.Recomposer.Companion` / internal function `invalidateGroupsWithKey(key: Int)` — invalidates all composition groups with a given group key and triggers targeted recomposition. This is what Live Edit calls after a swap.
   - `androidx.compose.runtime.HotReloader` (internal, used by JetBrains CHR and Compose desktop) — `saveStateAndDispose()` / `loadStateAndCompose()`: full composition reset that preserves `rememberSaveable`. Fallback tier.
   - The Compose **compiler** emits `androidx.compose.runtime.internal.FunctionKeyMeta` annotations (flag: `generateFunctionKeyMetaAnnotations`/`liveLiterals` family of plugin options) mapping each composable function → its recomposition group key. This is how we know *which* group key to invalidate for a changed function.
3. **Google's machinery is open source (Apache-2.0)** — AOSP [`platform/tools/base/deploy`](https://android.googlesource.com/platform/tools/base/+/studio-master-dev/deploy/) contains Android Studio's entire Apply Changes / Live Edit stack: the `Deployer` (dex diffing against installed APK), the **native JVMTI agent** (`deploy/agent/native`), the installer, and the **LiveEditInterpreter** (ASM-based bytecode interpreter + "Primer" trampolines used for structural changes). We adapt this rather than writing a JVMTI agent from scratch. This is the single biggest de-risker of the project.
4. **Live Literals compiler mode** — Compose compiler's `liveLiterals` option turns constants into `MutableState`-backed providers keyed by file/offset; constants can then be patched over the wire in <50ms without any class redefinition. This is HotSwan's "literal fast path".
5. **Lambdas** — changed lambda captures alter synthesized class shapes, so redefinition fails; Live Edit's answer (and ours): load changed lambdas as brand-new classes via `InMemoryDexClassLoader` injected as a parent/companion of the app classloader, and point the swapped calling code at them.

### Why each edit type works (change classifier matrix)

| Edit | Mechanism | Latency |
|---|---|---|
| Constant literal (color, dp, string in code) | Live Literals patch over socket | <50 ms |
| Composable/function body change | JVMTI `RedefineClasses` + `invalidateGroupsWithKey` | <1 s |
| New composable/function/field in existing class | Structural redefinition (API 30+) + recompose | <1 s |
| New class / changed lambda | New dex via `InMemoryDexClassLoader` + redefine call sites | <1 s |
| Reordering composable calls, changed group structure | Redefine + composition reset tier (`HotReloader`) | ~1 s |
| XML resources (strings/colors/dimens) | `processDebugResources` → resource APK → swap via `ResourcesManager` reflection (as AOSP deployer does) → activity recreate if needed | 1–3 s |
| Signature changes, removed members, inline-function edits, hierarchy changes | **Detected by classifier → automatic fallback** to normal incremental build+install, hot reload resumes on next edit (HotSwan v1 behavior; interpreter closes this gap in Phase 6) | normal build |

## Architecture — five components (mirrors HotSwan)

```
┌───────────── dev machine ─────────────┐        ┌──────── device (debug app) ────────┐
│ CLI daemon "hotreload watch"          │        │ client runtime lib (auto-injected)  │
│  ├ file watcher → module resolver     │  ADB   │  ├ init via androidx.startup        │
│  ├ Gradle Tooling API (incr. compile) │ socket │  ├ attachJvmtiAgent(agent.so)       │
│  ├ class differ (checksums vs baseline)│──────▶│  ├ socket server (localabstract)    │
│  ├ change classifier                  │forward │  ├ dex receiver → agent swap        │
│  ├ D8 → patch dex                     │        │  └ Compose bridge (reflection shims)│
│  └ protocol client                    │        │ native JVMTI agent (.so in AAR)     │
└───────────────────────────────────────┘        └─────────────────────────────────────┘
        Gradle plugin: wires client lib into debug variant, sets Compose compiler
        flags, captures baseline class checksums, exposes module/classpath metadata
```

1. **`gradle-plugin`** (Kotlin, published to Maven) — the only thing users add (`id("...hotreload") version ...` in the app module or settings plugin for zero-touch). Debug-variant-only. Responsibilities: add `runtime-client` dependency to `debugImplementation`; pass Compose compiler options (`generateFunctionKeyMetaAnnotations=true`, later `liveLiterals`); register a `hotReloadSnapshot` task capturing per-class checksums + the function-key-meta map after each full build; write a `hotreload-metadata.json` per module (classpath, source dirs, applied Compose compiler version) consumed by the CLI.
2. **`runtime-client`** (Android AAR, debug only) — auto-initializes via `androidx.startup.Initializer` declared in its own manifest (this is what satisfies the "millions of LOC, zero edits" constraint — manifest merging injects it). Loads the bundled JVMTI agent from `nativeLibraryDir` via `Debug.attachJvmtiAgent`. Runs a `LocalServerSocket` (`localabstract:hotreload_<pkg>`) speaking a length-prefixed protobuf/flatbuffer protocol: `SwapRequest(dex bytes, class names, structural?)`, `LiteralUpdate(key, value)`, `InvalidateGroups(keys)`, `ResourceSwap(resApk)`, `Ping/Capabilities`. Contains the **Compose bridge**: a `ComposeVersionShim` interface with per-runtime-version implementations resolved by reflection at startup (detect `androidx.compose.runtime` version from classpath), implementing the three-tier invalidation chain: (1) `invalidateGroupsWithKey` targeted; (2) `HotReloader` save/dispose/recompose reset; (3) activity recreation.
3. **`agent`** (C++ NDK `.so`, packaged in `runtime-client` jniLibs) — adapted from AOSP `deploy/agent/native`. Exposes JNI entry points to the runtime-client: `redefineClasses(name→bytes[])`, `redefineStructurally(...)`, capability query. Keep it thin; all policy lives in Kotlin.
4. **`cli`** (Kotlin/JVM, distributed as a jar/`brew`-able launcher: `hotreload watch [--app :app] [--device serial]`) — the orchestrator/engine, structured as a reusable library (`engine`) + thin CLI so the IDE plugin later embeds `engine` directly. Pipeline per save event: map file → Gradle module (from metadata json) → invoke that module's `compileDebugKotlin` via Gradle Tooling API → diff `.class` outputs against snapshot checksums → classify change (parse both class versions with ASM: body-only? additive? incompatible? group-key renumbering?) → D8 the changed classes (reusing the build's desugaring config) → send over ADB-forwarded socket → send invalidation keys (from FunctionKeyMeta of changed methods) → report status line. Incompatible changes: run `installDebug` instead, re-snapshot, continue watching.
5. **`intellij-plugin`** (Phase 5) — on-save/on-type triggers, status widget, error banners; wraps `engine`.

Plus **`samples/`** (single-module and multi-module demo apps) and **`e2e/`** (scripted emulator tests).

## Repo layout

```
compose-hot-reload/
  gradle-plugin/          # Gradle plugin (Kotlin)
  runtime-client/         # Android AAR: startup init, socket server, Compose bridge shims
  agent/                  # NDK JVMTI agent (C++), adapted from AOSP tools/base/deploy
  engine/                 # JVM library: watcher, gradle driver, differ, classifier, d8, protocol
  cli/                    # thin CLI over engine
  protocol/               # shared message definitions (proto)
  samples/single-module/  samples/multi-module/
  e2e/                    # emulator-driven end-to-end tests
  docs/
```

## Phases

### Phase 0 — De-risking spike (**hard — Fable/current session territory**)
Prove the full loop by hand on a toy Compose app (pin: latest stable Kotlin + Compose BOM + AGP; Pixel emulator API 34). No product code yet — a scratch repo and scripts.
1. Build debug APK with `generateFunctionKeyMetaAnnotations` enabled (verify the Compose compiler option name against the pinned compiler version; it has shifted names across versions — check `androidx.compose.compiler.plugins.kotlin.ComposeConfiguration`/CLI option list, and dump the emitted `FunctionKeyMeta` annotations from the APK dex to confirm key extraction works).
2. Write a minimal JVMTI agent (or build AOSP's) that redefines one class from bytes; attach via `Debug.attachJvmtiAgent` from a test Initializer; swap a composable body compiled out-of-band; call `invalidateGroupsWithKey` via reflection; observe UI update with `remember` state intact.
3. Repeat for: structural redefinition (add a new composable in the same file), a changed lambda (new-class path), and `HotReloader` reset tier.
4. **Exit criteria:** documented working recipe + list of every reflection call and compiler flag used, with the exact pinned versions. Everything after this is engineering, not research.

### Phase 1 — MVP vertical slice (single module, body edits) (**hard core, then Opus-friendly**)
- `agent`: port/trim AOSP native agent to a standalone NDK build (Fable: initial port; Opus: build system, JNI glue polish).
- `runtime-client`: startup init, agent attach, socket server, redefine path, `invalidateGroupsWithKey` shim for the pinned Compose version.
- `engine` + `cli`: file watcher (io.methvin.directory-watcher), Gradle Tooling API incremental compile, checksum differ, D8 invocation, ADB forward + protocol, happy-path classifier (body-only vs "anything else → tell user to rebuild").
- `gradle-plugin`: inject runtime-client into debug, set compiler flags, snapshot task, metadata json.
- **Exit criteria:** on `samples/single-module`, editing a composable body reflects on the emulator in <2s with state preserved, via `hotreload watch`.

### Phase 2 — Robustness & breadth (**Opus-executable with the matrix above as spec**)
- Structural additions (new composables/functions/fields) via structural redefinition; new-class/lambda path via `InMemoryDexClassLoader`.
- Full change classifier with ASM: detect signature/removal/inline/group-renumbering cases → automatic fallback to `installDebug` + re-snapshot + resume (HotSwan's "quiet fallback" UX).
- Three-tier invalidation chain with automatic escalation on recomposition exceptions (Compose catches and restores last-good state — hook `Recomposer` error callbacks / `HotReloader.getCurrentErrors`).
- Multi-module: per-module metadata, compile only the affected module, cross-module diff.
- Resource edits: run `processDebugResources`, push resource APK, swap via `ResourcesManager` (AOSP deployer has this code), tier-3 recreate when needed.

### Phase 3 — Literal fast path (**Opus-executable**)
Enable Compose `liveLiterals` in debug; engine detects constant-only edits by source-diffing before compiling (skip Gradle entirely); send `LiteralUpdate` keyed by the live-literal key. Sub-100ms loop. Guard behind a flag (live literals add debug overhead).

### Phase 4 — OSS hardening (**Opus-executable**)
Compose version shim matrix (N and N-1 stable Compose runtimes), AGP/Kotlin version checks with clear errors, docs site, publish plugin + AAR to Maven Central, CI (GitHub Actions + emulator e2e), telemetry-free diagnostics (`hotreload doctor`).

### Phase 5 — IntelliJ/Android Studio plugin (**Opus-executable**)
Embed `engine`; trigger on save; status bar widget (Ready / Reloading / Fallback-building / Error); gutter feedback; settings page. CLI remains supported.

### Phase 6 — Structural interpreter engine (v2 parity) (**hard — Fable territory; optional/stretch**)
For edits the classifier rejects (signature changes, removed members, inline functions, hierarchy edits): adopt Live Edit's Primer approach from AOSP — install trampolines that route affected method entries into an on-device **bytecode interpreter** (AOSP `LiveEditInterpreter`: ASM Frame + opcode interpreter; `invokespecial`/`monitorenter` via JNI) executing the *new* bytecode without asking ART to redefine anything. When the session ends or a real build lands, swap back to native DEX. This is HotSwan v2's breakthrough; ship Phases 1–5 first — the fallback-to-build UX is already good.

## Key risks & mitigations
- **Compose runtime internals are `internal` and shift between releases** → all reflection isolated in `ComposeVersionShim`; e2e matrix per supported Compose version; loud capability report at session start.
- **Compose compiler group-key renumbering** when edits add/remove groups can invalidate the key map → classifier compares emitted `FunctionKeyMeta` before/after; renumbering → fallback build (HotSwan does the same).
- **Kotlin incremental compilation traps** (inline functions inlined into other modules, default-arg synthetics, `const val` inlined at call sites) → classifier treats these as incompatible → fallback build.
- **ADB/agent flakiness, OEM ART quirks** → capability handshake (`Ping/Capabilities`) at attach; API 30+ floor; emulator-first support statement.
- **Licensing** — AOSP `tools/base/deploy` and JetBrains compose-hot-reload are Apache-2.0: adaptation is fine with NOTICE attribution; keep provenance headers on ported files.

## Verification (every phase)
- `samples/` apps exercised by `e2e/` harness on a CI emulator: script edits a source file, waits, asserts via `uiautomator dump`/screenshot diff that the UI changed **without process death** (assert same PID) and that a counter `remember` state survived. Add one e2e case per row of the change matrix as it's implemented.
- Phase 0/1 manual gate: sub-2s save→pixel latency on a M-series Mac + emulator.

## Primary references
- HotSwan how-it-works: https://hotswan.dev/docs/how-it-works (pipeline, 3-tier recomposition, fallback UX) and v2 blog (interpreter approach)
- Live Edit deep dive: https://android-developers.googleblog.com/2023/07/deep-dive-into-live-edit-for-jetpack-compose-ui.html
- Structural class redefinition: https://medium.com/androiddevelopers/structural-class-redefinition-6fc0cbab9161 ; ART TI: https://source.android.com/docs/core/runtime/art-ti
- AOSP deployer/agent/interpreter source: https://android.googlesource.com/platform/tools/base/+/studio-master-dev/deploy/
- JetBrains CHR (orchestration & hot-classpath-tracking design to imitate): https://github.com/jetbrains/compose-hot-reload
