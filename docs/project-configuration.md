# Project configuration and compatibility

Compose Hot Reload supports two ways to integrate with an Android project:

- **Configured mode**: the target project applies the `dev.hotreload` Gradle plugin.
- **Zero-touch mode**: the CLI supplies its bundled bootstrap plugin and runtime AAR for
  each Gradle invocation. It does not change the target project's settings, build files,
  sources, or local properties.

Both modes require a debuggable Android variant and one API 30+ device selected through
`adb`. The [README](../README.md) contains installation and quickstart instructions; this
page covers non-default project layouts and the compatibility coverage maintained in CI.

## Configure once, then start

`configure` resolves Gradle discovery and writes a profile outside the target repository.
Explicit command-line options always override profile values.

```bash
hotreload configure \
  --project /workspace/mobile-client \
  --app-module :androidApp \
  --app-module-dir apps/android-client \
  --app-id com.example.client.stage \
  --variant partnerStage \
  --project-java-home /opt/jdks/temurin-17 \
  --module :androidApp=apps/android-client,:feature=features/payments,:shared=shared/domain \
  --module-variant :feature=partnerStage \
  --gradle-arg -PfeatureToggle=true \
  --gradle-arg --parallel \
  --device emulator-5554 \
  --save-as mobile-client-stage
```

The profile is stored at
`~/.config/compose-hot-reload/projects/mobile-client-stage.toml`. Inspect the resolved
configuration and its reproducible expanded command with:

```bash
hotreload config show --profile mobile-client-stage
```

Use the profile in configured mode when the target project already applies
`dev.hotreload`:

```bash
hotreload start --profile mobile-client-stage
```

For a project that has no persistent integration, create a separate zero-touch profile and
start it the same way:

```bash
hotreload configure \
  --zero-touch \
  --project /workspace/mobile-client \
  --app-module :androidApp \
  --app-module-dir apps/android-client \
  --app-id com.example.client.stage \
  --variant partnerStage \
  --project-java-home /opt/jdks/temurin-17 \
  --module :androidApp=apps/android-client,:feature=features/payments \
  --module-variant :feature=partnerStage \
  --gradle-arg -PfeatureToggle=true \
  --device emulator-5554 \
  --save-as mobile-client-zero-touch

hotreload start --profile mobile-client-zero-touch
```

`--project-java-home` controls the target project's Gradle JVM; it is independent from
the JVM that runs the CLI. A profile records the resolved integration mode, variant,
module-directory mappings, Gradle arguments, target JDK, and device selection. Re-run
`configure` after changing project structure or variants so its discovery sidecar is
refreshed.

## Compatibility matrix

The matrix describes exercised coverage, not every combination that may happen to work.
The zero-touch fixtures are copied to a temporary Git repository and assert that no target
project inputs change during inspect, configure, prepare, or watch.

| Area | CI coverage | Fixture or gate |
|---|---|---|
| AGP 9.2.1, built-in Kotlin 2.4.0, JDK 21 | Custom `qa` debuggable app variant; inspect, configure, prepare, live-literal, compiled-code, resource, and watch paths | [`e2e/fixtures/zero-touch/agp9`](../e2e/fixtures/zero-touch/agp9), `e2e/run-zero-touch.sh` |
| AGP 8.12.3, standalone Kotlin Gradle Plugin 2.3.20, JDK 17 | `internalStage` flavored/custom debuggable variant; inspect, configure, prepare, and multi-module watch | [`e2e/fixtures/zero-touch/agp8`](../e2e/fixtures/zero-touch/agp8), `e2e/run-zero-touch.sh` |
| Single and multi-module projects | AGP 9 is single-app-module; AGP 8 includes Android app/library modules plus a Kotlin/JVM dependency | `e2e/run-zero-touch.sh`, `e2e/run.sh`, `e2e/run-multi.sh` |
| Gradle path differs from physical directory | `:mobile` is mapped to `applications/mobile` or `applications/phone`; AGP 8 also uses feature and shared directories | both zero-touch fixtures |
| Composite build | AGP 9 includes and applies a minimal `build-logic` plugin; the included build receives no bootstrap instrumentation while the root `:mobile` app is instrumented | `e2e/fixtures/zero-touch/agp9`, `e2e/run-zero-touch.sh` |
| Flavors, custom debuggable variants, and differing library variants | `qa` and `internalStage` fixture variants, including AGP 8 module variant selection | `e2e/run-zero-touch.sh` |
| Configured integration | Single-module sample begins through `hotreload start`; multi-module sample is a configured watcher regression | `e2e/run.sh`, `e2e/run-multi.sh` |
| Zero-touch integration | AGP 9 fixture begins from an absent app through `hotreload start`; the offline API 36 suite also covers AGP 8 with explicit JDK 17 | `ZERO_TOUCH_OFFLINE=1 JAVA17_HOME=… e2e/run-zero-touch.sh` |

The GitHub Actions `compatibility` job runs the zero-touch suite on an API 36 emulator
after building the CLI distribution. It installs JDK 17 and JDK 21, exports
`JAVA17_HOME`, and fails if the AGP 8/JDK 17 leg is skipped. The existing `e2e` job
continues to cover the configured default sample.

## Validation findings

- `hotreload start` prepares an absent app and then enters the normal watch loop. The configured
  fixture deliberately captures its process ID **after** readiness because preparation force-stops
  and relaunches the application; later state-preservation assertions must use that post-start PID.
- The zero-touch fixture starts an unmodified AGP 9 target from an absent install and preserves the
  same compiled-code, live-literal, resource, and state assertions as the explicit prepare/watch
  path. Its AGP 8 leg remains a separate JDK 17 multi-module regression.
- A literal update is a complete save operation when it succeeds. It advances the source baseline
  rather than compiling the same edit again; this avoids a redundant keyless swap that can disturb
  Compose state.
- Gradle evaluates init scripts for each composite participant. Zero-touch uses Gradle's public
  `gradle.parent` API to leave included builds inert before loading or validating root-only
  bootstrap properties. The root build still validates every required bootstrap property and
  instruments only its selected module closure.

## Current boundaries

- Only one device is supported for a watch session. Multiple-device orchestration is not
  covered by a fixture.
- Kotlin Gradle scripts are covered. Groovy build scripts are not a claimed compatibility
  target because no Groovy fixture exists.
- Included builds are intentionally not instrumented. Select app and dependency modules from the
  invoked root build; code that belongs to an included `build-logic` build is outside the
  zero-touch watched-module closure.
- Non-debuggable/release variants and devices below API 30 are unsupported.
- Variant source-set handling is exercised for the fixture variants above. A general
  multi-dimension flavor-to-source-set mapping is not yet covered by a dedicated fixture;
  use explicit module and variant options when discovery needs help.
