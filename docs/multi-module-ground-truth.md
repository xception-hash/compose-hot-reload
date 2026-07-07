# Multi-module ground truth (for the diffing DESIGN session)

Every fact below was produced by an actual command, shown next to the fact. Testbed:
`samples/multi-module/` (`:app → :feature → :core`, from T08 commit `e4df5f8`).
Source `scripts/env.sh` for tool paths (`$JAVAP` = JBR javap). All Gradle commands run from
`samples/multi-module/` against a **warm daemon**. This doc gathers facts only — **no design
decisions, no code changes**. Sample sources were edited only transiently for verification and
reverted (`git status` is clean; see §Acceptance).

> **Env gotcha (applies to every command here):** the Bash shell is **zsh**, and env vars do
> **not** persist between separate shell invocations. Each command must `source scripts/env.sh`
> itself, or `./gradlew` fails with *"Unable to locate a Java Runtime"* and **silently no-ops
> (exit 0)** — a build that appears to succeed but compiles nothing. Also: unquoted `$var` does
> not word-split in zsh (unlike bash).

---

## 1. Module graph & plugin types

| Module | Gradle plugin | Depends on | Compiler flags wired via |
|---|---|---|---|
| `:app` | `com.android.application` (+ `org.jetbrains.kotlin.plugin.compose`) | `project(":feature")` | **`dev.hotreload` plugin** (reflection) |
| `:feature` | `com.android.library` (+ `kotlin.plugin.compose`) | `project(":core")` | **hand-wired** `kotlin { compilerOptions { … } }` |
| `:core` | `org.jetbrains.kotlin.jvm` | — (leaf) | **hand-wired** `kotlin { compilerOptions { … } }` |

Graph: `:app → :feature → :core` (linear). Composables: `MainScreen`, `AppCounter` (`:app`),
`FeatureCard` (`:feature`); `:core` is pure Kotlin (`coreLabel(n: Int): String`, no Compose).

Evidence — plugin/dependency blocks:
```
$ sed -n '1,5p;24,26p' samples/multi-module/app/build.gradle.kts
plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose"); id("dev.hotreload") }
dependencies { implementation(project(":feature")) … }
$ sed -n '1,4p' samples/multi-module/feature/build.gradle.kts   # com.android.library + compose
$ sed -n '1,3p' samples/multi-module/core/build.gradle.kts       # org.jetbrains.kotlin.jvm
```

Flag wiring:
- **`:app` (via plugin)** — `dev.hotreload` adds `debugImplementation com.github.xception-hash.compose-hot-reload:runtime-client` (group renamed for JitPack in T29)
  and the three flags reflectively; `app/build.gradle.kts` has **no** `kotlin {}` block.
  `gradle-plugin/src/main/kotlin/dev/hotreload/gradle/HotReloadPlugin.kt:58-60`:
  ```
  "-Xlambdas=class", "-Xsam-conversions=class", "-Xstring-concat=inline"
  ```
- **`:feature` / `:core` (hand-wired)** — `feature/build.gradle.kts:20-28` and
  `core/build.gradle.kts:5-14` add the same three flags in a top-level `kotlin { compilerOptions
  { freeCompilerArgs.addAll(…) } }` block (each carries a `// TODO: absorbed by dev.hotreload
  plugin once multi-module lands` comment). The plugin does **not** yet support library / jvm
  modules — that gap is design input, not addressed here.

---

## 2. Compiled-classes output dir per module

After `./gradlew :app:assembleDebug` (warm), the directory holding fresh `.class` files:

| Module | Compiled-classes dir |
|---|---|
| `:app` | `app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes` |
| `:feature` | `feature/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes` |
| `:core` | `core/build/classes/kotlin/main` |

> **Correction to the T10 spec's guess:** the spec expected `:feature`'s path to *differ* from
> `:app`'s. It does **not** — `com.android.library` uses the same AGP **built-in kotlinc** output
> layout as `com.android.application`, so `:app` and `:feature` share the identical
> `build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes` suffix. Only `:core`
> (plain `kotlin-jvm`, no AGP) differs: `build/classes/kotlin/main`. This is the key ground truth
> for the design: **AGP modules and pure-JVM modules use two different output layouts.**

Evidence — dirs exist and contain `.class` files (`ls … | head`):
```
$ ls app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/dev/hotreload/multisample
ComposableSingletons$MainActivityKt.class   MainActivity.class   MainActivityKt.class
MainActivityKt$AppCounter$1$1.class   MainActivityKt$AppCounter$2.class   MainActivityKt$MainScreen$2.class  …
$ ls feature/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/dev/hotreload/multisample/feature
FeatureCardKt.class   FeatureCardKt$FeatureCard$1$1$1.class   FeatureCardKt$FeatureCard$1$2.class   FeatureCardKt$FeatureCard$2.class
$ ls core/build/classes/kotlin/main/dev/hotreload/multisample/core
CoreLabelKt.class
```

Isolation verified — edit **one** module's body, rebuild, compare `.class` mtimes (only the edited
module's class file changes; the edit is reverted after each). Method:
```
# per module: perl -0pi -e 's/<old string>/<new string>/' <module source>
#             ./gradlew :app:assembleDebug --console=plain -q
#             stat -f '%Sm %N' <each module's top-level .class>   # then git checkout <source>
```
Observed `stat` (HH:MM:SS) of `MainActivityKt.class` / `FeatureCardKt.class` / `CoreLabelKt.class`:

| Edited module | app class | feature class | core class |
|---|---|---|---|
| baseline | 16:28:35 | 12:37:04 | 12:37:03 |
| `:app` body | **16:27:58→** changed | unchanged | unchanged |
| `:feature` body | unchanged | **16:28:36** changed | unchanged |
| `:core` body | unchanged | (see §3) | **16:28:38** changed |

(Whether a `:core` edit also touches `:feature`'s class depends on ABI vs body — see §3.)
**Caveat for the design:** Gradle snapshots source inputs by **content hash, not mtime** — a bare
`touch` of a source file triggers **no** recompile. The engine's `SourceWatcher` (mtime/event
based) and Gradle's up-to-date check therefore use different change signals.

---

## 3. Minimal per-module compile task + downstream recompilation

Exact cheapest task that recompiles only the edited module's Kotlin:

| Module | Minimal compile task |
|---|---|
| `:app` | `:app:compileDebugKotlin` |
| `:feature` | `:feature:compileDebugKotlin` |
| `:core` | `:core:compileKotlin`  ← **not** `compileDebugKotlin` (pure `kotlin-jvm` has no variants) |

> **Design-critical:** the task name is **not uniform**. AGP modules use
> `compile<Variant>Kotlin` (`compileDebugKotlin`); a `kotlin-jvm` module uses `compileKotlin`.
> The engine currently hardcodes `:<module>:compileDebugKotlin` (§7) — wrong for `:core`.

Downstream recompilation, `:core` edited two ways, evidence = `--console=plain` task status
(`./gradlew :app:assembleDebug --console=plain`, filtered to Kotlin compile tasks):

**A. Body-only edit** (`"core says: $n"` → `"core value: $n"`):
```
> Task :core:compileKotlin
> Task :feature:compileDebugKotlin UP-TO-DATE
> Task :app:compileDebugKotlin UP-TO-DATE
```
→ **only `:core` recompiles.** Gradle compile-avoidance: `:core`'s ABI is unchanged, so dependents
stay UP-TO-DATE.

**B. Signature/ABI change** (`coreLabel(n: Int)` → `coreLabel(n: Int, suffix: String = "")`;
existing `coreLabel(count)` call in `:feature` still compiles):
```
> Task :core:compileKotlin
> Task :feature:compileDebugKotlin           ← re-runs (direct dependent, ABI changed)
> Task :app:compileDebugKotlin UP-TO-DATE    ← :feature's OWN ABI unchanged, so :app stays put
```
→ `:core` **and** `:feature` recompile; `:app` does **not** (propagation stops when a module's own
ABI is unchanged).

**Ground truth for the design:** per-module compilation is real and cheap, but a cross-module edit
is not always single-module — an **ABI change fans out one hop to direct dependents**. The diffing
engine must (a) pick the right compile task per module layout, and (b) after compiling the edited
module, re-scan any dependent module whose compile task also fired.

---

## 4. Flags actually applied in all 3 modules (lambdas→classes, no indy string-concat)

`$JAVAP -v` over **every** `.class` in each module — zero `invokedynamic`, zero
`makeConcatWithConstants`:

```
$ # for each class: "$JAVAP" -v <class> | grep -c invokedynamic ; ... | grep -c makeConcatWithConstants
:app      classes=9  invokedynamic(total)=0  makeConcatWithConstants(total)=0
:feature  classes=4  invokedynamic(total)=0  makeConcatWithConstants(total)=0
:core     classes=1  invokedynamic(total)=0  makeConcatWithConstants(total)=0
```

- **`-Xlambdas=class`** — lambdas are named subclasses, not indy metafactory calls, e.g.
  `$JAVAP -p …/FeatureCardKt$FeatureCard$1$1$1.class`:
  ```
  final class dev.hotreload.multisample.feature.FeatureCardKt$FeatureCard$1$1$1
      extends kotlin.jvm.internal.Lambda implements kotlin.jvm.functions.Function0<kotlin.Unit>
  ```
- **`-Xstring-concat=inline`** — `:core`'s `"core says: $n"` compiles to inline `StringBuilder`,
  not `makeConcatWithConstants`. `$JAVAP -c …/CoreLabelKt.class`:
  ```
  0: new           // class java/lang/StringBuilder
  9: invokevirtual // StringBuilder.append:(Ljava/lang/String;)…
  13: invokevirtual // StringBuilder.append:(I)…
  16: invokevirtual // StringBuilder.toString:()…
  ```

All three modules produce hot-reload-safe bytecode (indy string-concat would `NoSuchMethodError`
inside recomposition — see `DexCompiler.kt` note). The flags land whether wired via the plugin
(`:app`) or by hand (`:feature`/`:core`).

---

## 5. FunctionKeyMeta extractable from library modules

`:feature` (`com.android.library`) — `FunctionKeyMeta` reads cleanly from its built-in-kotlinc
classes dir:
```
$ CLASSES_DIR=samples/multi-module/feature/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes \
    scripts/extract-keys.sh dev.hotreload.multisample.feature.FeatureCardKt
          key=2142768522
          startOffset=625
          endOffset=974
```
→ `extract-keys.sh` works unchanged for a library module given the right `CLASSES_DIR`; the
compose group key is present in the `.class` (host-side, as with `:app`).

`:core` — **pure Kotlin, no `@Composable`**, so **no `FunctionKeyMeta`** to extract:
```
$ grep -rn "@Composable" samples/multi-module/core/src
(no matches)   # coreLabel(n) is plain Kotlin
```

---

## 6. Timing — warm-daemon incremental compile per module (one-line body edit)

3 samples each; `/usr/bin/time -p ./gradlew :<module>:<compileTask> --console=plain -q`, source
edit varied per sample and reverted between. **Upper bound** — includes `./gradlew` client→daemon
round-trip; a warm Tooling API connection (what `GradleCompiler` uses) is faster.

| Module (task) | s1 | s2 | s3 |
|---|---|---|---|
| `:core` (`:core:compileKotlin`) | 0.67 | 0.56 | 0.62 |
| `:feature` (`:feature:compileDebugKotlin`) | 0.75 | 0.64 | 0.66 |
| `:app` (`:app:compileDebugKotlin`) | 0.91 | 0.82 | 0.82 |

All sub-second; per-module incremental compile is cheap enough for an edit-driven loop.

---

## 7. Where the engine's single-module assumptions live (grep-level, no fixes)

`path:line — what it assumes` (all in `engine/src/main/kotlin/dev/hotreload/engine/` unless noted):

- `WatchSession.kt:22` — `Config.classesDir: Path` — **one** classes dir for the whole session.
- `WatchSession.kt:27` — `fun forProject(projectDir, module, …)` — a **single** `module` string.
- `WatchSession.kt:32-33` — `sourceRoots = [ "$module/src/main/kotlin", "$module/src/main/java" ]`
  — watch roots scoped to **one** module.
- `WatchSession.kt:35-36` — `classesDir = "$module/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"`
  — hardcoded **AGP built-in-kotlinc** layout; wrong for a `kotlin-jvm` module like `:core`
  (`build/classes/kotlin/main`).
- `WatchSession.kt:44` — `compileTask = ":${config.module}:compileDebugKotlin"` — single task,
  and `compileDebugKotlin` specifically (breaks for `:core`, which needs `compileKotlin` — §3).
- `WatchSession.kt:65,97` — `gradle.compile(compileTask)` — compiles the **one** task only.
- `WatchSession.kt:69-70,104` — `ClassSnapshot.scan(config.classesDir)` — snapshots the **single**
  dir; a cross-module edit that changes a second module's classes is invisible.
- `ClassSnapshot.kt:20-24` — `fun scan(classesDir: Path)` walks **one** root; no multi-dir merge.
- `GradleCompiler.kt:17,20` — `GradleCompiler(projectDir)` / `.forProjectDirectory(projectDir)` —
  one connection to one project (fine), but `run(task)` at `:33` `.forTasks(task)` takes a **single**
  task string.
- `SourceWatcher.kt:18,30` — `roots: List<Path>` (already a list — OK), but it's fed only the one
  module's roots from `WatchSession.kt:73-77`.
- `cli/src/main/kotlin/dev/hotreload/cli/Main.kt:15,29,43` — `--module` is a **single** value
  (default `"app"`), passed once to `Config.forProject`.
- `scripts/extract-keys.sh:8-9` — single `CLASSES_DIR` (one module's classes) per invocation.
- `e2e/run.sh:13,25-26,56` — hardwired to `samples/single-module` (`--module` unset → default
  `app`); no multi-module case.

---

## Acceptance (verified)

1. `test -f docs/multi-module-ground-truth.md` — exists; all 7 sections present. ✓
2. Every dir in §2 exists and contains `.class` files (`ls … | head` shown in §2). ✓
3. §5 shows real `extract-keys.sh` keys+offsets for the `:feature` composable
   (`key=2142768522`, offsets 625/974). ✓
4. `git status` clean except this doc — every sample source touched for §2/§3/§6 reverted. ✓
