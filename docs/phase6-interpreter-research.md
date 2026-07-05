# Phase 6 — AOSP LiveEdit bytecode-interpreter research + on-device spike

**Date:** 2026-07-05. **Verdict: GO** — the interpreter class of approach runs under ART/API 36
in a real app process, the AOSP sources compile with our exact toolchain unmodified, and the
port surface is smaller for us than for Studio (details in §5). Spike: `spike/interpreter/`
(`build.sh` then `run.sh`) — **PASS, 2/2 deterministic runs**.

Source of truth: `third_party/tools-base/deploy/` @ pin in `third_party/PINNED.txt` (fetched by
`scripts/fetch-aosp-deployer.sh`, T13). All file references below are relative to that tree.
Everything here was derived from those sources + live emulator runs; no web fetches.

## What this buys us (Phase 6 goal)

Today the classifier's `Rebuild` verdict (method removals, signature changes, hierarchy changes,
composeKey changes) stops the hot-reload loop and tells the user to rebuild. Studio's Live Edit
handles those same edits by **interpreting** the edited method bodies on-device instead of
redefining classes. This research answers how, and what a port into our engine costs.

## 1. Trampoline install path (how an edited method's entry reaches the interpreter)

On-device, in the deploy agent (`agent/native/live_edit.cc: LiveEdit()`), per request:

1. `SetUpInstrumentationJar` — extracts the runtime jar (interpreter + liveedit + jarjar'd ASM,
   the Bazel `:runtime` dex_library) and `jvmti->AddToBootstrapClassLoaderSearch(jar)`. Studio
   puts it on the **boot** classpath so instrumented app classes can always resolve
   `LiveEditStubs`.
2. `RegisterDispatchJNI` — `RegisterNatives` for `interpreter.JNI`'s 12 native methods
   (`agent/native/jni_dispatch/jni_dispatch.cc`, ~445 lines: arg unboxing +
   `CallNonvirtual*Method` for invokespecial + `MonitorEnter/Exit`).
3. `SetUpLiveEditDex` — adds `live_edit.dex` (the codegen'd `Proxies` lambda library, see §Proxies)
   to the **app** classloader via `addDexPath`.
4. `LiveEditStubs.init(appClassLoader)` then `LiveEditStubs.addClasses(primary[][], proxy[][],
   structural)` — stores the edited **JVM `.class` bytes** (not dex!) in a
   `LiveEditContext` map keyed by internal class name.
5. **Priming** (`PrimeClass`, once per class): JVMTI `RetransformClasses` with a
   ClassFileLoadHook that runs `StubTransform` (dex-level, via slicer —
   `agent/native/transform/stub_transform.cc`), then feeds the transformed bytes to the **same
   ART structural-redefinition extension we already use** (`instrumenter.cc:326`). The transform:
   - adds a `public Object $liveEditBytecode` instance field to every class;
   - appends `LiveEditStubs.stubConstructor(className, this)` before each constructor's
     `return-void`;
   - prepends to every method (except `<clinit>`/abstract):
     ```
     Object bc = LiveEditStubs.getClassBytecode("pkg/Cls");        // static methods
     Object bc = LiveEditStubs.getInstanceBytecode("pkg/Cls", this); // instance methods
     if (bc != null) return LiveEditStubs.stub<T>(bc, "name", "(desc)T", new Object[]{null, this, args...});
     // else fall through to the original body
     ```
     (`stub<T>` per return kind: L/I/J/F/D/S/B/C/Z/V; reference returns come back as Object +
     checkcast.) `doStub` unpacks `params[1]` = this, `params[2..]` = args, and runs
     `MethodBodyEvaluator(context, bytecode, name, desc).eval(...)`.
6. After `addClasses`: if any class was **newly** primed → `LiveEditStubs.restartActivity()`
   (recreate all activities; ensures a LiveEdit lambda is at the root of all group trees).
   Otherwise → Compose invalidation (§3).

`getInstanceBytecode` also does per-instance compatibility gating: the new bytes are stored into
the instance's `$liveEditBytecode` only if `BytecodeValidator.checkCompatibleUpdate` passes
(vs the previous bytes, or vs the APK class on first edit); incompatible → keep old (returns the
stored bytecode; the stub then interprets the *old* version, not the new one).

**Host-side (Studio):** the edited file is compiled by the IDE's embedded Kotlin compiler
(`tools/adt/idea`, not in our clone — irrelevant: our engine already produces exactly the needed
artifact, per-class `.class` files from Gradle). The deployer just ships
`LiveEditRequest{target_classes, support_classes, invalidate_mode, group_ids, structural_redefinition}`
(`proto/deploy.proto:807`, `deployer/.../tasks/LiveUpdateDeployer.java`) — `class_data` = raw
JVM classfile bytes.

## 2. On-device dependency surface (and: does it compile with our pins?)

**YES — compiles as-is.** Verified: `spike/interpreter/build.sh` compiles
`interpreter/*.java` (23 files) + `liveedit/*.java` (21) + `liveedit/backported/*` (3) +
`instrument/ReflectionHelpers.java` with **JBR javac `--release 8`**, then **d8 (36.0.0,
`--min-api 30`, default desugaring, `--lib android-36`)** → single `classes.dex`, **499 KB**
including all of ASM. Zero source edits except one mechanical rename (below).

Complete dependency list:
- **ASM 9.0** — `lib/asm-all-9.0.jar` (in the clone). AOSP jarjar-relocates it to
  `com.android.deploy.asm` (`jarjar_asm_rules.txt`); we skip bytecode jarjar by sed-ing the
  *sources* to the jar's real package `org.jetbrains.org.objectweb.asm` at build time.
- **`com.android.annotations` {NonNull, Nullable, VisibleForTesting}** — source-retention only;
  the module (`tools/base/annotations`) is outside our sparse clone, so build.sh generates
  3 trivial stubs.
- **`android.jar`** — `android.app.Activity`, `android.util.Log` only.
- **`interpreter.JNI` natives** — the ONE native dependency: 10 `invokespecial*` overloads +
  `enterMonitor`/`exitMonitor`. Only used by `AndroidEval` for `super.m()` calls (constructor
  calls use `Constructor.newInstance`, everything else plain reflection) and for
  `monitorenter/monitorexit`. On Android the class expects the natives to be registered by the
  already-loaded agent (`JNI.loadLibrary` no-ops on Dalvik). Port: ~150 lines into
  `hotreload_agent.cpp` from `jni_dispatch.cc` + one `RegisterNatives` call. **The spike ran
  without them** (no super calls / no synchronized in interpreted code) — an
  `UnsatisfiedLinkError` only occurs if those two features are hit.
- **`liveedit.Proxies`** — looked up reflectively by `LiveEditContext`'s ctor
  (`Class.forName("com.android.tools.deploy.liveedit.Proxies")`) and **throws if absent**.
  It's a codegen'd lookup table (Bazel genrule running `codegen/LambdaGenerator.kt` + javapoet)
  of concrete classes extending `kotlin.jvm.internal.Lambda` + implementing `FunctionN`, used to
  instantiate *new* lambdas created inside interpreted code (java.lang.reflect.Proxy can't extend
  a class). A 5-line stub returning null satisfies the ctor; see §5 for why we may never need the
  real one.
- Backported `Map/List/Set` shims (30 lines each, for pre-N `getOrDefault` etc.) — compiled in,
  harmless at API 30.
- NOT needed: protobuf (that's the AOSP transport; ours is our own protocol), slicer (dex
  transform — we'd do the equivalent host-side in ASM), `instrument/*` beyond ReflectionHelpers,
  Houdini config flags (defaults fine).

## 3. Compose integration (`ComposeSupport.java` → our `ComposeBridge`)

`ComposeSupport` is thin and maps 1:1 onto what we already shipped:

| AOSP (`liveedit/ComposeSupport.java`, called from agent's `recompose.cc`) | Ours |
|---|---|
| `recomposeFunction(reloader, groupIds)` = `invalidateGroupsWithKey(id)` per id (with `$runtime_release` mangling fallback) | `ComposeBridge.invalidateGroupsWithKey` (same mangling tolerance) |
| `fetchPendingErrors(reloader)` = `getCurrentErrors` + `clearErrors` | `ComposeBridge.currentErrors(clear)` / protocol `GetErrors` |
| `versionCheck(reloader, expected)` reads `androidx.compose.runtime.ComposeVersion.version` | not built (T25 `hotreload doctor` candidate) |
| `LiveEditRequest.invalidate_mode`: `INVALIDATE_GROUPS` (keyed) / `SAVE_AND_LOAD` (= our tier-2) / `RESTART_ACTIVITY` (= our tier-3) | our tiers 1/2/3 — plus our keyless state-preserving `InvalidateAll` (0x08), which AOSP **doesn't have** (T16 finding) |

So the interpreter port needs **zero new Compose reflection**: after `addClasses`, invalidate
with targeted keys when the edited method has a composeKey (we extract them host-side already),
else `invalidateAll` — exactly the existing hot-swap flow. Interpreted composables execute their
*body* in the interpreter but call the real Compose runtime through reflection like any other
callee.

One AOSP behavior to note: `RiskyChange` — the Studio compiler flags lambda-capture/interface
changes, and recomposition errors mentioning proxy-missing-field/method get friendlier messages.
Not needed for v1.

## 4. Fit into our engine

- **Trigger:** `Classifier` verdicts that today → `Rebuild` (member **removal**, signature
  change, hierarchy change) become `Interpret(classes)`. Keep `Rebuild` for `<clinit>` changes
  (LiveEdit skips static initializers too — `stub_transform.cc:420`) and for anything
  BytecodeValidator would reject.
- **Priming, host-side (the main new build block):** AOSP does the stub transform on-device in
  dex because Studio only has the installed dex. **We have the original `.class` files**, d8, and
  the proven structural-redefine path (`nativeRedefine(structural=true)`). So: an ASM
  `ClassVisitor` port of `StubTransform` (add `$liveEditBytecode` field; wrap each method with
  the getBytecode/stub prologue; constructor tail-call) → d8 → existing `Redefine(structural)`
  opcode. ~200–300 lines next to `FactsExtractor` (same ASM toolkit). This drops slicer AND the
  ClassFileLoadHook machinery entirely.
- **Runtime delivery:** inject `interp.dex` (the 499 KB artifact of §2, built once, shipped as an
  engine resource) via the existing `InjectDex` opcode into the **app classloader** on the first
  interpreted edit of a session. No boot-classpath games needed: primed classes are *ours*,
  generated host-side, so they resolve `LiveEditStubs` from the same classloader the dex was
  injected into. (Boot classpath was Studio's constraint because their transform runs before/
  independent of the app classloader state; verify resolution live in T27, fallback =
  `AddToBootstrapClassLoaderSearch` which JVMTI also offers us.)
- **New protocol opcode:** `LiveEditClasses { className, classBytes (JVM .class), groupIds }*`
  → runtime-client calls `LiveEditStubs.init(loader)` once + `addClasses`, then the normal
  invalidate+verify tail (targeted keys else `invalidateAll`, then `GetErrors`). Protocol → v5.
- **Agent natives:** port `jni_dispatch.cc` into `hotreload_agent.cpp` (+`RegisterNatives` on
  `com.android.tools.deploy.interpreter.JNI`) so `super.m()` and `synchronized` work in
  interpreted bodies.
- **Ordering caveat (AOSP: restart-after-first-prime):** after priming a class whose composable
  is on screen, AOSP restarts the activity once (`live_edit.cc:202`) because the group tree still
  holds pre-prime lambda instances. Whether we need tier-3 on *first* prime (or whether
  structural-redefine + `invalidateAll` suffices — our redefine path is atomic and
  ART-native, unlike their retransform) is **the** open question T27 must answer live first.
- **Per-instance staleness:** `$liveEditBytecode` is refreshed on constructor exit and on each
  instance-method entry via `getInstanceBytecode` — instances created before priming never ran
  the ctor hook, but the method-entry path covers them (field defaults to null → validator
  gates the first assignment).

## 5. Go/no-go + port plan

**GO.** Live-proven on our exact pins; every AOSP piece is either compile-verbatim, a small
mechanical port, or replaced by machinery we already shipped.

Why our port is *smaller* than Studio's runtime:
1. **No lambda proxies (probably).** Studio needs `Proxies`/`ProxyClass*` because a *new* lambda
   in edited code has no on-device class. We compile with `-Xlambdas=class
   -Xsam-conversions=class`, so a new lambda is a plain new class file → our existing
   **InjectDex** path injects it for real, and interpreted `NEW` resolves it via
   `Constructor.newInstance`. Proxy machinery stays dormant behind the stub `Proxies`
   (compiled in, ~unused). If a case surfaces that needs it, the codegen is portable later.
2. **No INVOKEDYNAMIC problem.** `OpcodeInterpreter.naryOperation` throws on INDY — but our
   class-shape flags (`-Xlambdas=class -Xsam-conversions=class -Xstring-concat=inline`) already
   exist to eliminate indy from app code (T05). The interpreter's constraint = our pipeline's
   existing invariant.
3. **No dex-level transform, no boot classpath, no protobuf** (§4).

Portable verbatim (proven by spike): `interpreter/` + `liveedit/` + backported + ReflectionHelpers,
built by `spike/interpreter/build.sh` (asm package sed + annotation stubs are build-time only).
Mechanical port: `jni_dispatch.cc` → our agent. New code: host ASM StubTransform, `LiveEditClasses`
opcode + client handler, classifier routing. Not ported: slicer transform, protobuf plumbing,
LambdaGenerator/Proxies codegen, `RiskyChange` UX.

**Known limitations to document in T27 (all verified against source, first one also live):**
- **Callee exceptions skip interpreted try/catch.** `ByteCodeInterpreter.doInterpret` routes to
  the handler table only for interpreted `ATHROW` and for `InterpreterException`-wrapped
  evaluator errors; exceptions thrown by *compiled* callees are `Throw.sneaky`-rethrown raw
  (`AndroidEval.java:357` → `doInterpret`'s `catch (Exception) { Throw.sneaky }`). AOSP's own
  tests only cover throw+catch inside interpreted code (`TestException.java`). Spike asserts the
  escape explicitly (`parseX=escaped:NumberFormatException`). Candidate one-line-class fix in our
  copy: try `exceptionCaught(...)` (real `instanceOf`) before the sneaky rethrow — decide in T27.
- `<clinit>` edits unsupported (skipped by the transform).
- `super.<init>(...)` inside interpreted ctor bodies unsupported (`AndroidEval.invokeSpecial`:
  "Unable to do super.<init>") — interpreted `<init>` only via `Constructor.newInstance` of an
  already-defined class. (Ctor *body* edits of primed classes: the ctor hook runs after the
  original body; the interpreter is not used for the ctor itself. Effectively: constructor edits
  stay `Rebuild`.)
- Interpretation cost: ~130 ms for init+addClasses+5 method evals on the emulator (spike logcat
  timestamps); per-eval cost after warm-up is single-digit ms for these small bodies. Fine for
  "run the edited composable until next rebuild"; recursion-heavy bodies will be visibly slower
  (each nested interpreted call re-enters `interpreterLoop`).

**Task breakdown → `tasks/T27-interpreter-port.md`** (write from this doc): (1) agent natives,
(2) interp.dex build integration (reuse spike build.sh), (3) host StubTransform + tests,
(4) protocol v5 `LiveEditClasses` + client handler, (5) classifier routing + engine wiring,
(6) live validation incl. the first-prime/activity-restart question + e2e case
(signature-change edit hot-applied). Only (3) and (6) have real unknowns.

## Spike details (`spike/interpreter/`)

- `build.sh` — builds `build/dex/classes.dex` (499 KB) from third_party sources + driver.
  Toolchain = repo pins (JBR javac `--release 8`, d8 36.0.0 `--min-api 30`).
- Driver (`driver/dev/hotreload/spike/`): `SpikeTarget.java` **v1** compiled into the dex
  (the "APK" version); `target-v2/SpikeTarget.java` **v2** ("edited") compiled but embedded
  only as base64 `.class` bytes — never dexed, never loadable. `SpikeDriver.run()` drives the
  production entry path: `LiveEditStubs.init` → `addClasses(v2)` → `getClassBytecode` →
  `stubI/stubL` with the exact `doStub` params layout `[null, this|null, args...]`.
- `run.sh` — force-stop + relaunch toy app (injected classes can't be replaced in a live
  process), push dex, broadcast `inject` + `run` extras to the toy `PatchReceiver` (spike-only
  hook added for this), assert `SPIKE PASS` in logcat.
- **Result (2/2 runs):** `SPIKE PASS nativeFib=55 interpFib=1055 greet=v2:Hello, ART!
  parse42=int:42 parseX=escaped:NumberFormatException` — loops/branches/locals, NEW +
  invokespecial `<init>` + StringBuilder virtual calls, interpreted-ATHROW try/catch, and the
  edited-bytes-win-over-loaded-class property all verified under ART API 36 in the app process,
  via our own JVMTI dex-inject extension.
