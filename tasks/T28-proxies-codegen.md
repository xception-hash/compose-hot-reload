# T28: Proxies codegen — lift the composable signature-change limitation
Status: TODO
Assignee: steps 1–3 agy | steps 4–5 opus

## Goal
T27's interpreter handles member removals and hierarchy edits, but a `@Composable` **signature
change** stays `Rebuild`: Compose regenerates the restart lambda (e.g. `Greeting$1`) with a
changed constructor, and the interpreter cannot construct it — the AOSP lambda-proxy machinery
is compiled into our interp.dex but dormant behind a stub `Proxies` lookup table. This task
generates the real `Proxies.java` offline (AOSP's `LambdaGenerator`), rebuilds interp.dex with
it, extends the protocol so changed lambda classes travel as **support classes**, and teaches the
classifier to route signature changes to `Interpret`. Research with every hard part answered:
`docs/phase6-interpreter-research.md` **§7** — read it first; do NOT re-read AOSP sources.

## Spec

Toolchain rule for every step: `source <repo-root>/scripts/env.sh`
with an ABSOLUTE path before any build command (relative source silently fails → stale outputs).

### Step 1 (agy) — generate `Proxies.java` offline

Create `scripts/gen-proxies.sh` (same style as `scripts/build-interp-dex.sh`: `set -euo pipefail`,
source `env.sh`). It must:

1. Require the interp build's compiled classes: run `scripts/build-interp-dex.sh` first if
   `build/interp-dex/classes` is absent (LambdaGenerator imports `ProxyClass`,
   `ProxyClassHandler`, `SourceLocationAware` and the relocated ASM `Opcodes`/`Type`).
2. Copy `third_party/tools-base/deploy/agent/runtime/src/main/java/com/android/tools/deploy/codegen/LambdaGenerator.kt`
   into `build/gen-proxies/` and apply the SAME source-level relocation the interp build does:
   `sed -i '' 's/com\.android\.deploy\.asm/org.jetbrains.org.objectweb.asm/g'`.
3. Locate jars offline under `~/.gradle/caches/modules-2/files-2.1` (fail loud if missing;
   they are verified present on this machine):
   - `com.squareup/javapoet/1.13.0/*/javapoet-1.13.0.jar`
   - `org.jetbrains.kotlin/kotlin-stdlib/2.4.0/*/kotlin-stdlib-2.4.0.jar`
   - `org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.0/*/kotlin-compiler-embeddable-2.4.0.jar`
4. Compile the generator with the embeddable compiler (no kotlinc install needed):
   `"$JAVA_HOME/bin/java" -cp <kotlin-compiler-embeddable> org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
     -classpath <javapoet>:<asm-all-9.0.jar>:build/interp-dex/classes \
     -d build/gen-proxies/out build/gen-proxies/LambdaGenerator.kt`
   (`$RT/lib/asm-all-9.0.jar` as in build-interp-dex.sh.)
5. Run it — the entry point is top-level `main(args)`, class `LambdaGeneratorKt`; **arg 0 is the
   output path** (note: it also drops a stray `Proxies.java` in the CWD — run from a temp dir or
   delete it):
   `"$JAVA_HOME/bin/java" -cp build/gen-proxies/out:<javapoet>:<kotlin-stdlib>:<asm-all>:build/interp-dex/classes \
     com.android.tools.deploy.codegen.LambdaGeneratorKt third_party/generated/liveedit/Proxies.java`
6. Commit `third_party/generated/liveedit/Proxies.java` + a sibling `PROVENANCE` file (pin from
   `third_party/PINNED.txt`, generator command, jar versions, date).

### Step 2 (agy) — rebuild interp.dex with the real Proxies

Edit `scripts/build-interp-dex.sh`:

1. Replace the stub-`Proxies` heredoc (section “3.” of the script) with a copy of
   `third_party/generated/liveedit/Proxies.java` into
   `$BUILD/gen/com/android/tools/deploy/liveedit/` (fail loud if the generated file is absent,
   pointing at `scripts/gen-proxies.sh`).
2. The generated file references `kotlin.jvm.internal.Lambda`, `kotlin.jvm.functions.Function*`,
   `kotlin.coroutines.jvm.internal.*` — add kotlin-stdlib 2.4.0 (same cache lookup as step 1) to
   the **javac `-cp`** AND to d8 as **`--classpath` only** (compile-time reference; the stdlib
   must NOT be dexed into interp.dex — device apps already ship it).
3. Update the PROVENANCE heredoc: “stub liveedit.Proxies” → “generated liveedit.Proxies
   (scripts/gen-proxies.sh, LambdaGenerator)”.
4. Rebuild; commit the new `engine/src/main/resources/dev/hotreload/interp.dex` + PROVENANCE.
   Expected growth: **+300–500 KB** (was 495 820 B). Must stay single-dex (script asserts).

Gotcha to keep in the script comment: injected dex is immutable per process — after this lands,
any device session must **force-stop + relaunch** apps before the new interp.dex is used.

### Step 3 (agy) — protocol v7→v8: support classes on the wire

1. `protocol/src/main/kotlin/dev/hotreload/protocol/Protocol.kt`: `VERSION` 7→**8**;
   `Request.LiveEditClasses` gains `supportClasses: List<ClassBytes>` (immediately after
   `classes`). Reuse the existing `ClassBytes` type. KDoc: support classes = lambda-shaped
   classes with changed constructors, registered proxy-flagged, never primed.
2. `Wire.kt`: encode/decode `supportClasses` exactly like the existing `classes` list (count +
   per-entry internalName/bytes), both directions.
3. `WireTest`: update the existing LiveEditClasses round-trip AND add a case with 2 support
   classes + empty support list.
4. Engine call sites (`WatchSession.applyInterpret`): pass `emptyList()` for now — step 4 fills
   it. Must compile.
5. Client: `runtime-client/.../LiveEditInterp.kt` — `addClasses` currently reflectively calls
   `LiveEditStubs.addClasses(primary, new byte[0][], false)`; change the public API to
   `addClasses(primary: Array<ByteArray>, support: Array<ByteArray>)` and pass the support bytes
   through. `PatchServer.kt` LiveEditClasses handler passes `request.supportClasses` bytes.

Standing reminder (memory + docs): protocol bump → **reinstall device apps + rebuild
`:cli:installDist`** before any e2e (Ping prints client protocol; doctor/WatchSession fail loud).

### Step 4 (opus — NOT delegable) — classifier routing

In `engine` classifier (`Classifier.kt` + `WatchSession`):

1. Lift the no-added-method restriction: `Interpret` becomes eligible when the causes are
   removal / **signature change (paired add+remove)** / hierarchy on non-`<init>`/`<clinit>`
   **methods** — including when the batch also ADDS methods (`$default` bridges, new overloads;
   §7.4 says interpreted callers resolve them).
2. Hard `Rebuild` unchanged for: composeKey renumbering, `<clinit>` changes, field
   add/remove/type-change, constructor changes on NON-lambda classes.
3. **Whole-batch rule (§7.4):** if ANY class in the batch routes to Interpret, ALL changed
   classes in the batch prime+interpret — never mix hot-swap and interpret in one edit.
4. Class partition per batch:
   - top-level changed class → **primary**: prime (StubTransform→dex→structural redefine) +
     send in `classes`;
   - lambda-shaped class with a changed `<init>` → **support**: send in `supportClasses`, do
     NOT prime (its old class stays; proxies replace instances). Lambda-shaped = ASM
     `ClassReader.superName` ∈ {`kotlin/jvm/internal/Lambda`,
     `kotlin/coroutines/jvm/internal/SuspendLambda`,
     `kotlin/coroutines/jvm/internal/RestrictedSuspendLambda`,
     `kotlin/jvm/internal/FunctionReference`, `kotlin/jvm/internal/FunctionReferenceImpl`,
     `kotlin/jvm/internal/AdaptedFunctionReference`};
   - brand-new class (no baseline) → existing InjectDex path, unchanged.
5. Host tests in `ClassifierTest` (or sibling): signature change → Interpret; field change →
   Rebuild; mixed batch → all-interpret; lambda partition → support list.

### Step 5 (opus + device) — live checkpoints + e2e

Preconditions: emulator up (`scripts/emulator-up.sh`), apps REINSTALLED (protocol v8), CLI
launcher rebuilt (`./gradlew :cli:installDist`), apps force-stopped once (new interp.dex).

1. Checkpoint A (T27's blocked headline case): `samples/single-module` —
   `Greeting(name: String)` → `Greeting(name: String, suffix: String = "!")` + caller updated in
   the same save. Expect `interpreted:` log, UI updates, Counter state preserved, SAME PID.
2. Checkpoint B (T27's mixed-batch failure): cross-module `coreLabel` signature change producing
   a new `coreLabel$default` — whole-batch rule must interpret both classes; no
   `NoSuchMethodError`.
3. Checkpoint C (§7.5): verify the transient old-scope pass converges (one recompose may render
   baseline output before the parent recomposes interpreted).
4. e2e case 15 `signature-change-edit` in `e2e/run.sh` following case-14's pattern (assert_ui
   is text-only).

## Risks (watch for, do not pre-fix)
- `BytecodeValidator.checkCompatibleUpdate` may reject REPEAT edits of a primed class whose
  shape keeps drifting — if it bites, capture the exact error in this file and stop.
- Function references (`::foo`) untested end-to-end (proxy classes exist for them).
- Suspend lambdas OUT OF SCOPE (proxy classes generated but untested; classifier must not route
  suspend-lambda ctor changes to support — leave Rebuild if `superName` is a suspend base? NO —
  keep them Rebuild only if problems surface; the generated table covers them, try Interpret
  first and note the outcome).
- Interpretation cost: signature-change edits interpret the whole changed class until next
  rebuild; recursion-heavy bodies visibly slower.

## Out of scope
Marketplace publishing, Compose N-1 shims, `RiskyChange` UX, suspend-lambda e2e coverage.

## Acceptance
From repo root, in order:
```
scripts/gen-proxies.sh                          # step 1: writes third_party/generated/liveedit/Proxies.java
grep -c "public static class" third_party/generated/liveedit/Proxies.java   # ≥ 140
scripts/build-interp-dex.sh                     # step 2: single dex, size 700–1100 KB
./gradlew :protocol:test :engine:test           # steps 3–4: green
(cd runtime-client && ./gradlew :runtime-client:assembleDebug)   # step 3: green
./e2e/run.sh                                    # step 5: 15/15 green, twice
```
