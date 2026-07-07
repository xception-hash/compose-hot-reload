# T31b (agy): Bundle the CLI into the IntelliJ plugin zip
Status: DONE (Opus-reviewed: unzip layout + interp.dex verified; commit on feat/t31-cli-bundle)
Assignee: agy (mechanical Gradle packaging) — reviewed + committed by Opus
Parent: T31 Part 2 (CLI-availability gap). Approach A (bundle in zip) is DECIDED.

## Goal
Make `cd intellij-plugin && ./gradlew buildPlugin` produce a plugin zip that contains the CLI
distribution, so a Marketplace/from-disk user needs **no repo clone** to run hot reload. The plugin
Kotlin is already done (Opus) and expects the bundled launcher at a fixed path — **your job is only
the Gradle wiring + docs to put it there.** Do NOT touch any `.kt` file.

## The contract you must satisfy (fixed by the already-written plugin Kotlin)
`HotReloadService.bundledLauncher()` resolves the CLI at, relative to the installed plugin dir:

```
<pluginDir>/cli/bin/cli        (Unix)
<pluginDir>/cli/bin/cli.bat    (Windows)
<pluginDir>/cli/lib/*.jar      (engine + protocol + kotlin stdlib; engine.jar carries interp.dex)
```

i.e. the **entire `:cli:installDist` output tree** (`cli/build/install/cli/{bin,lib}`) must land under
a `cli/` subdirectory **inside the plugin's own directory** in the built zip. `installDist` already
produces exactly this tree — you are copying it verbatim into the plugin distribution.

## Steps

### 1. Get the CLI dist built before the plugin is packaged
The plugin is a standalone Gradle build (`intellij-plugin/settings.gradle.kts`,
`rootProject.name = "hotreload-intellij-plugin"`) with NO link to the root build
(`compose-hot-reload`, modules `:engine :cli :protocol`). Wire the two so `buildPlugin` can depend on
the CLI dist. **Recommended: composite build.**

- In `intellij-plugin/settings.gradle.kts` add `includeBuild("..")`.
- Reference the included build's `installDist` output. The root project name is `compose-hot-reload`,
  the task is `:cli:installDist`, its output dir is `<root>/cli/build/install/cli`.
  Use `gradle.includedBuild("compose-hot-reload").task(":cli:installDist")` as an explicit
  `dependsOn` for `prepareSandbox`, and read the dir via a path
  (`rootDir.resolve("../cli/build/install/cli")` — `rootDir` is `intellij-plugin/`).

If composite build fights the IntelliJ Platform plugin classpath (it should not — the root chain
`:cli → :engine → :protocol` is pure JVM), fall back to the **standalone** variant: no `includeBuild`;
`prepareSandbox` reads `../cli/build/install/cli` directly and **fails with a clear message if it is
missing** ("run `./gradlew :cli:installDist` in the repo root first"). Document the two-command build
in that case. Pick whichever gives a clean `buildPlugin` — note which you chose in the commit message.

### 2. Copy the dist into the plugin distribution
Configure the `prepareSandbox` task (IntelliJ Platform Gradle Plugin 2.5.0) to copy the dist tree
into the plugin's sandbox dir under `cli/`. Pattern (adjust the `into` base to the real sandbox
plugin-dir name — it is the plugin name, "Compose Hot Reload"; the platform plugin exposes it, e.g.
via the sandbox `pluginName`/archive base):

```kotlin
tasks {
    prepareSandbox {
        // composite variant:
        dependsOn(gradle.includedBuild("compose-hot-reload").task(":cli:installDist"))
        from(layout.projectDirectory.dir("../cli/build/install/cli")) {
            into("${pluginName.get()}/cli")   // → <sandbox>/plugins/<pluginName>/cli/...
        }
    }
}
```

Verify the exact property for the plugin dir name against the 2.5.0 API (it may be
`intellijPlatform.projectName`, the `prepareSandbox` `pluginName` provider, or the archive base
name). The acceptance below is the ground truth — iterate until the unzip layout matches.

### 3. Docs
- `intellij-plugin/README.md`: change the "No bundled CLI" known-limit and the run/install sections
  to reflect that the CLI is now bundled; leave the Settings CLI-path field documented as an
  **override**. Add the SDK prerequisite note (a user still needs the Android SDK build-tools 36.0.0
  on their machine — the CLI shells out to `d8`/`dexdump`; `ANDROID_HOME` or the Settings "SDK path"
  field must point at it). Keep the manual `runIde` dev instructions (devs still use installDist).

## Constraints
- Do NOT edit any `.kt` file, `plugin.xml`, or anything under `src/`. Gradle + README only.
- Do NOT change `pluginVersion` (stays 0.1.0) or the `sinceBuild`/`untilBuild` range.
- The build must stay runnable with `JAVA_HOME` = Android Studio JBR (jvmToolchain 21).
- `--offline` should work if the CLI dist and IntelliJ platform are already cached; if network is
  required for the composite root build, say so (Jay may run it interactively).

## Acceptance (structural — no device needed)
1. `cd /Users/jay/projects/compose-hot-reload && ./gradlew :cli:installDist` (or via the composite)
   then `cd intellij-plugin && ./gradlew buildPlugin` → BUILD SUCCESSFUL.
2. `unzip -l intellij-plugin/build/distributions/hotreload-intellij-plugin-0.1.0.zip` lists, inside
   the plugin dir:
   - `.../cli/bin/cli` and `.../cli/bin/cli.bat`
   - `.../cli/lib/engine.jar` (and the other CLI jars)
3. `cd intellij-plugin && ./gradlew test` still green (18 CliProtocol tests) and `compileKotlin`
   unaffected.
4. Report the zip size before/after (bundling adds the CLI + engine jars — sanity-check it is
   reasonable, order of a few MB, not hundreds).

## Notes for the reviewer (Opus)
- After agy lands this, Opus verifies the unzip layout matches `<pluginDir>/cli/bin/cli` EXACTLY
  (the Kotlin path is hard-coded to it) and that `engine.jar` inside carries `dev/hotreload/interp.dex`
  (`unzip -l .../cli/lib/engine.jar | grep interp.dex`).
- Device proof (plugin zip + SDK, no clone, hot-reload works) is T31 Part 1/Part 2 acceptance —
  Opus + Jay on the emulator, not part of this agy task.
