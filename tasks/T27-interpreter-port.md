# T27: Phase 6 interpreter port — hot-apply classifier-rejected edits
Status: IN-PROGRESS — Phases 1 (steps 2,3) + 2 (steps 1,4) DONE host-side; Phase 3 (steps 5-8) + 2 device checkpoints remain. See Progress at bottom.
Assignee: agy/Opus (device needed throughout; the hardest spec in the queue — read the research
doc first and follow it, it answers "why" for every step)
Priority: 7 of T21–T27 (LAST; big)
Prereq reading (mandatory, in order): `docs/phase6-interpreter-research.md` (GO verdict, all
mechanisms, port plan — this spec implements its §4/§5), `spike/interpreter/` (working build +
on-device proof), `docs/code-map` sections in memory / `docs/phase2-findings.md` for engine flow.

## Goal
Edits the classifier rejects today (method/property REMOVAL, signature change, class-hierarchy
change → `Rebuild`) hot-apply by interpreting the edited method bodies on-device with the AOSP
LiveEdit interpreter (proven on our pins by the spike). Scope precisely: `Rebuild` caused by
removals/signature-changes/hierarchy on NON-constructor, non-`<clinit>` methods. composeKey
renumbering and `<clinit>`/constructor changes stay `Rebuild`.

## Spec
Work in the order below; each step has its own checkpoint so a failed step leaves a reviewable
state.

1. **Agent natives** (`runtime-client/lib/src/main/cpp/hotreload_agent.cpp`): port
   `third_party/.../deploy/agent/native/jni_dispatch/jni_dispatch.cc` (~445 lines: unbox flags
   table, `CallNonvirtual*Method` invokespecial family, MonitorEnter/Exit) and register with
   `RegisterNatives` on `com.android.tools.deploy.interpreter.JNI` — registration must be lazy
   (a `nativeRegisterInterpreterJni(Class jniClass)` JNI export called by the client AFTER the
   interpreter dex is injected; the class doesn't exist before that). Keep the unbox-flag
   constants byte-identical to `AndroidEval.java`'s (the .cc has the matching table).
   Checkpoint: spike variant with a `super.toString()` call + a `synchronized` block in the v2
   target passes (extend `spike/interpreter/` target-v2 + driver — keep the spike green).
2. **interp.dex as an engine artifact**: promote `spike/interpreter/build.sh` to
   `scripts/build-interp-dex.sh` (same steps, minus the spike driver/target; keep the
   annotation stubs + asm package sed + stub `Proxies`) producing
   `engine/src/main/resources/dev/hotreload/interp.dex`; COMMIT the dex (record the tools-base
   pin + build command in a sibling `interp.dex.PROVENANCE` file). The spike keeps its own
   build.sh (unchanged behavior) but sources the shared parts if that's clean; don't
   over-engineer.
3. **Host-side priming transform** (`engine/.../StubTransform.kt`, ASM, next to
   `FactsExtractor`): JVM-bytecode equivalent of AOSP `stub_transform.cc` (the research doc §1
   has the exact prologue shape): add `public Object $liveEditBytecode`; every non-abstract,
   non-`<clinit>`, non-`<init>` method gets the
   `getClassBytecode`/`getInstanceBytecode` + null-check + `LiveEditStubs.stub<T>` +
   return/checkcast prologue; `<init>` gets the `stubConstructor` tail before each RETURN.
   maxStack/maxLocals via `COMPUTE_FRAMES`. Unit tests (JVM, no device): transform a fixture
   class, load it in a test classloader with a fake `LiveEditStubs` recording invocations,
   assert prologue behavior incl. null → falls through to original body, each return kind, and
   a >4-arg method (Object[] packing).
4. **Protocol v5 `LiveEditClasses` opcode 0x0A** (`protocol/Protocol.kt` + `Wire.kt`, codec in
   BOTH write and read; coordinate the version bump with T25 — see note there):
   `{ classes: [{internalName, classBytes}], primedDexName: String?, groupIds: [Int] }`.
   Response = existing Ack/Failure. Client handler (`PatchServer.kt`, main thread):
   on first use per process — InjectDex the interp.dex (engine sends it via the existing
   InjectDex opcode first; no new transport), call `nativeRegisterInterpreterJni`, then
   reflectively `LiveEditStubs.init(app classloader)`; every time — if `primedDexName` present,
   the primed classes were already delivered via the existing `Redefine(structural)` /
   `InjectDex` ops in the same batch (engine orchestrates order: inject interp.dex → redefine
   primed originals → LiveEditClasses), then reflectively `addClasses(primary, empty, false)`,
   then the standard invalidate tail: `invalidateGroupsWithKey` per groupId when non-empty else
   `invalidateAllCompositions()`, then the usual verify/GetErrors flow engine-side. ALL
   interpreter-runtime calls via reflection (the AAR never compiles against interp classes).
5. **Engine routing** (`Classifier.kt` + `WatchSession.kt`): `Rebuild` verdicts whose ONLY
   causes are removal/signature-change/hierarchy on eligible methods become
   `Interpret(classes, groupIds)` — a class-level verdict: ship the WHOLE new .class bytes of
   each affected class (the interpreter always evaluates the latest bytes; per-method granularity
   is AOSP's too). Engine keeps a session set of primed classes: first `Interpret` for a class →
   run StubTransform on the BASELINE class bytes → d8 (existing DexCompiler) → structural
   redefine, and record it. groupIds = composeKeys of the class's composables (from the
   snapshot facts; empty when none). Once a class is primed, ALL its subsequent edits (even
   body-only) go through `LiveEditClasses` (the stubs intercept entry regardless — mixing
   redefine into a primed class would fight the prologue).
   Log lines: `primed: <class>` and `interpreted: <classes> (<ms>)` for e2e/IDE parsing.
6. **The first-prime question** (the one real unknown — answer it LIVE before writing the e2e):
   after priming+addClasses of an on-screen composable's class, does targeted
   invalidateGroupsWithKey (resp. invalidateAll) render correctly on the same PID, or does the
   group tree hold stale lambda instances requiring one activity recreate (AOSP restarts —
   research doc §4 explains why ours may not need it)? Test with the sample: signature change
   `fun Greeting(name: String)` → `(name: String, suffix: String)` with updated call site.
   - If clean: done, record in the research doc §4.
   - If broken: on newly-primed classes send tier-3 (activity recreate via the existing Reset…
     no — Reset is tier-2; add the recreate to the runtime-client's LiveEditClasses handler
     behind a `restartActivities: Boolean` request field) and record it. Either way the answer
     goes into `docs/phase6-interpreter-research.md` §4 as a dated addendum.
7. **e2e case `interpreter-edit`** (`e2e/run.sh`): signature-change edit (as in step 6) lands
   on screen ≤5s, same PID, `primed:` + `interpreted:` lines present, remember state elsewhere
   preserved (or exactly the documented step-6 behavior), error case: a broken interpreted body
   (throw in composable) reports via the existing error flow and fix-and-save recovers.
8. **Docs**: README table row for "signature change / removed member" moving from
   "rebuild" to "interpreted (slower body, state per step 6)"; research doc addendum;
   `NOTICE`/provenance header on ported jni_dispatch code (Apache-2.0 attribution, matches
   third_party/PINNED.txt).

## Out of scope
- Lambda-capture/interface `RiskyChange` UX, `BytecodeValidator` beyond what addClasses already
  runs, the `Proxies` codegen (stub stays), inline-function edits (stay Rebuild), `<clinit>`/
  constructor edits (stay Rebuild), un-priming/swap-back-to-dex on rebuild (session restart
  covers it), the callee-exception routing fix (research doc §5 — separate decision, do NOT
  patch interpreter sources in this task).
- Multi-module wrinkles beyond what falls out for free (all modules' classes flow through the
  same snapshot; if a lib-module class primes incorrectly, record and stop).

## Acceptance
From repo root, emulator booted (runtime-client + protocol changed ⇒ reinstall first):
1. Step-3 unit tests green host-only: `./gradlew :engine:test`.
2. Spike still green incl. new super/synchronized coverage: `spike/interpreter/run.sh` (2×).
3. `./e2e/run.sh` full suite incl. `interpreter-edit` → green twice back-to-back; pgrep clean.
4. `./e2e/run-multi.sh` 4/4 (no regression; multi-module interpreter coverage not required).
5. Manual: 3 consecutive signature-change edits in one watch session, all land, same PID.

## Progress (phased across sessions — device/quota constrained)
Split into 3 phases. **Phase 1 = host-only (no device), DONE this session (commits d6d19d2, 5e959c5).**

- **Phase 1 (DONE):**
  - **Step 3** — `engine/.../StubTransform.kt`: ASM (tree API) port of `stub_transform.cc`, run
    host-side on baseline `.class` bytes. Adds `$liveEditBytecode`; per-method
    getClassBytecode/getInstanceBytecode + null-check + `stub<T>` prologue (params `[null,
    this|null, boxed args…]`); `<init>` stubConstructor tail before each RETURN. Frames via a
    classpath-aware `ClassWriter` (`getCommonSuperClass` over a supplied loader; falls back to
    Object, on-device verifier is the backstop). Tests: `engine/src/test/{kotlin/.../StubTransformTest.kt,
    java/.../stubfixture/Fixture.java, java/com/android/tools/deploy/liveedit/LiveEditStubs.java}` —
    8 tests, loading each transformed class runs the JVM verifier. **Acceptance #1 GREEN**
    (`./gradlew :engine:test`).
  - **Step 2** — `scripts/build-interp-dex.sh` → committed `engine/src/main/resources/dev/hotreload/
    interp.dex` (495820 B, 269 classes) + `interp.dex.PROVENANCE`. Spike build.sh untouched.

- **Phase 2 CODE DONE (commit 9c1b4a4) — host-verified; 2 device checkpoints deferred:**
  - **Step 1** — `runtime-client/lib/src/main/cpp/interpreter_jni.cpp`: port of `jni_dispatch.cc`
    (NOT into hotreload_agent.cpp — separate file, cleaner; 12 interpreter.JNI natives, unbox flags
    byte-identical to AndroidEval.java, file-local + explicit `RegisterNatives`). Lazy
    `HotSwap.nativeRegisterInterpreterJni(jniClass)` export (called by LiveEditInterp AFTER
    interp.dex inject). CMakeLists updated. Apache-2.0 provenance header present. **Verified:** clean
    CMake rebuild (arm64+x86_64), export symbol present via llvm-nm. **DEFERRED (device):** spike
    super.toString()/synchronized checkpoint (extend `spike/interpreter/` target-v2 + driver).
  - **Step 4** — protocol **v7** (`Protocol.kt` VERSION 6→7, opcode `LIVE_EDIT_CLASSES=0x0A`,
    `ClassBytes` + `Request.LiveEditClasses{classes, primedDexName:String?, groupIds}`),
    `Wire.kt` codec both ways, `WireTest` round-trip (null/non-null primedDexName, empty/non-empty
    groupIds) — **`:protocol:test` 12 GREEN**. Client: `LiveEditInterp.kt` (reflective bridge:
    ensureInitialized = register JNI + LiveEditStubs.init once; addClasses per req) +
    `PatchServer.kt` `LiveEditClasses` handler (main thread: addClasses → invalidateGroups per
    groupId else invalidateAllCompositions). **Verified:** `runtime-client:assembleDebug` (Kotlin +
    NDK). **DEFERRED (device):** live protocol handshake — **reinstall device apps before e2e**
    (Ping shows client version; v7 now). Engine still SENDS nothing yet (that's Phase 3 step 5).

- **Phase 3 (FINAL, device throughout):** steps 5 (Classifier/WatchSession Interpret routing +
  session primed-class set; engine calls StubTransform→d8→structural redefine, then sends
  InjectDex(interp.dex) + LiveEditClasses), 6 (LIVE first-prime/activity-recreate question — the
  real unknown, answer before the e2e), 7 (e2e `interpreter-edit`), 8 (docs/README/research §4
  addendum/**NOTICE file** for the ported jni_dispatch — header already in interpreter_jni.cpp).
  Fold in the two deferred Phase 2 device checkpoints here (spike super/synchronized; live v7).

Acceptance #1 met (host). #2–#5 need the emulator (Phase 3).
