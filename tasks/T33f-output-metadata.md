# T33f: build/APK/resource paths from Gradle metadata (T33 phase 5)
Status: IN-REVIEW
Assignee: agy

**Precondition:** T33e (PR #15) merged — this spec builds on its `ProfileStore`,
the shared `resolveConfig` in `cli/Main.kt`, and the T33d trigger rule. Do not start
from a tree without it.

## Goal
Finish T33 phase 5: compile output dirs, APK paths, task names, and source/resource
directories come from Gradle task/output metadata (the T33c discovery report + AGP's
`output-metadata.json` built-artifacts file) instead of hardcoded directory
conventions. The current `ModuleSpec` convention probes stay as compatibility
fallbacks, and the default-variant explicit-flag path (what e2e runs) stays
byte-identical.

Three independent consumption paths, all landing here:
1. **Discovery path** (zero-config `watch`/`doctor`): the `DiscoveryReport` that
   `resolveConfig` already produces in-memory stops being discarded — its per-variant
   metadata flows into `ProjectConfig` and drives `ModuleSpec`.
2. **Profile path** (`watch|doctor --profile`): `configure` persists the discovery
   report as a JSON sidecar next to the profile; profile starts get the same metadata
   without re-running discovery (profiles stay fast + pinned, per T33e).
3. **APK selection** (all paths, always on): `ResourceSwapper` picks the APK via
   AGP's `output-metadata.json` (variant + applicationId checked) instead of
   newest-mtime-recursive — this fixes the known stale-other-variant-APK gotcha.

All design decisions below are FIXED — do not improvise alternatives; a genuinely
needed new decision = stop and surface it.

## Spec

### 1. `inspect.init.gradle` — one additive change, schema stays v1
Schema v1 already reports everything phase 5 needs (classOutputDirs from
`compileTask.outputs.files`, sourceDirs/resDirs from source sets, apkOutputDir from
the `SingleArtifact.APK` provider). Exactly ONE change:

- `sourceSetTiers`: when `flavors.size > 1`, additionally include the combined
  flavor source-set name (first flavor as-is + subsequent flavors capitalized:
  `[vendor, production]` → `vendorProduction`), inserted after the individual
  flavors and before the build type. Multi-dimension variants have a real combined
  source set that the current tiers miss. `collectDirs` existence-filters, so this
  is purely additive.

`schemaVersion` stays `1` (no shape change). The T33c fixture JSONs and
`GradleDiscoveryTest` parsing tests are NOT modified.

### 2. `GradleDiscovery.kt` — `ModuleMetadata` + extraction
New types next to the report model:

```kotlin
/** Per-module build metadata resolved from a DiscoveryReport for one watch plan. */
data class ModuleMetadata(
    val compileTask: String? = null,   // task PATHS, e.g. ":app:compileDebugKotlin"
    val assembleTask: String? = null,
    val installTask: String? = null,
    val classOutputDirs: List<String> = emptyList(), // rootDir-relative, as reported
    val sourceDirs: List<String> = emptyList(),
    val resDirs: List<String> = emptyList(),
    val apkOutputDir: String? = null,  // app module only
)

fun DiscoveryReport.moduleMetadata(
    modules: List<ModuleSpec.Request>,
    defaultVariant: String,
): Map<String, ModuleMetadata>
```

Extraction rules (per request, keyed by `gradlePath`; a miss at any step = no map
entry for that module, never an error):
- Project looked up by `gradlePath`. Type `androidApp`/`androidLib`: pick the
  variant entry whose `name` == (`request.variant ?: defaultVariant`) — EXACT name
  match only (library selected-variant resolution is out of scope; a lib without a
  same-named variant simply gets no metadata and falls back to conventions).
  Build `ModuleMetadata` from the entry's `tasks` + dirs; `apkOutputDir` only when
  type == `androidApp`.
- Type `kotlinJvm`: from `jvm` (classOutputDirs + sourceDirs only; tasks/res/apk
  stay null/empty — conventions cover them).
- Empty lists and null strings are preserved as-is; consumers treat empty-as-absent
  (section 4).

### 3. `ProjectConfig.kt` — carry the metadata
New field, default keeps every existing call site and test unchanged:

```kotlin
val moduleMetadata: Map<String, ModuleMetadata> = emptyMap(),
```

No new `init` validation (extraction only produces keys for configured modules).
The existing sacred guards (applicationId/launchActivity regexes) are untouched.

### 4. `ModuleSpec.kt` — metadata-first, convention fallback (per field)
`ModuleSpec` gains a `metadata: ModuleMetadata?` (private ctor param, stored);
`probe(projectDir, request, variant, metadata: ModuleMetadata? = null)`.
`Layout` enum is UNCHANGED — no new values, no `when` churn.

Precedence is PER FIELD and independent: metadata value when present and non-empty,
else the existing convention expression verbatim. With `metadata == null` every
produced string/path is byte-identical to today — that is the freeze contract, and
the existing `ModuleSpecTest` assertions prove it by passing unmodified.

- **classesDir (in `probe`):** first try `metadata?.classOutputDirs` — resolve each
  against `projectDir`, pick the first that is an existing directory containing at
  least one `*.class` file (recursive walk; compile-task outputs can include
  non-class sibling dirs). If metadata was present but none qualified, print
  `metadata: <gradlePath> classes dirs missing on disk — using convention probe`
  and fall through. Then the existing three convention candidates, unchanged. Both
  exhausted → the existing error, with the metadata dirs appended to the listed
  candidates when they were tried.
- **layout when a metadata dir wins:** the layout of the convention candidate equal
  to the chosen dir if any; else by discovered type — `androidApp`/`androidLib` →
  `AGP_BUILT_IN`, `kotlinJvm` → `JVM`. (Layout then only feeds convention
  *fallbacks* for task names — the type-derived value picks the right naming
  family; document this in a comment.)
- **compileTask / assembleTask / installTask:**
  `metadata?.compileTask ?: <existing expression>` (same for assemble/install).
- **sourceRoots:** `metadata?.sourceDirs?.takeIf { it.isNotEmpty() }`
  `?.map { projectDir.resolve(it) } ?: <existing expression>`. Same for
  **resDirs** (metadata resDirs replace the source-set-name convention; the
  `layout == JVM → emptyList()` branch stays for the convention path).
- **apkOutputDirs:** `listOfNotNull(metadata?.apkOutputDir?.let(projectDir::resolve))`
  PREPENDED to the existing convention list, `.distinct()`.

Note: `probe` needs `projectDir` for resolution — it already has it. Metadata paths
are rootDir-relative as reported; resolve against `projectDir` (the discovery build
ran `forProjectDirectory(projectDir)`, so they coincide).

### 5. `ApkLocator.kt` (new, engine) — the stale-APK fix, always on
```kotlin
object ApkLocator {
    data class Located(val apk: Path, val source: String) // "output-metadata.json" | "newest-apk fallback"
    fun locate(candidateDirs: List<Path>, variant: String, applicationId: String?): Located?
}
```
Algorithm, in candidate order:
1. If `<dir>/output-metadata.json` exists, gson-parse a minimal model
   (`applicationId: String?`, `variantName: String?`, `elements: List<{outputFile: String?}>`
   — ignore everything else). Malformed/unreadable JSON → print
   `apk: unreadable output-metadata.json in <dir> — skipping` and treat the dir as
   json-less (step 3 fallback may still use it).
2. Checks against a parsed json: `variantName` present and != `variant` → print
   `apk: skipping <dir> — output-metadata.json is variant '<found>' (want '<variant>')`
   and continue to the next dir. `applicationId` present in BOTH the json and the
   argument and different → same skip shape naming the applicationId. Otherwise
   resolve `elements[].outputFile` against the dir, keep the ones that exist, and
   return the newest-mtime one (multi-split builds) with source
   `"output-metadata.json"`. All elements missing on disk → skip line + continue.
3. If NO candidate dir produced a json match: today's fallback verbatim — recursive
   newest-mtime `*.apk` walk over the candidate dirs in order — returned with source
   `"newest-apk fallback"`, after printing (once per call)
   `apk: no matching output-metadata.json — newest-APK fallback may pick a stale variant`.
4. Nothing found → null.

`ResourceSwapper.findApk()` is REPLACED by
`ApkLocator.locate(appModule.apkOutputDirs, appModule.variant, applicationId)`; the
"no APK under …" failure message is unchanged. On the FIRST successful locate of a
session print `apk: <path> (<source>)` (once — subsequent swaps stay quiet unless
the source changes). AGP has written `output-metadata.json` next to APKs since 4.x,
so the sacred e2e path lands on the json branch — its greps assert presence of
lines, not absence, and `apk: ` never collides with the `watching ` contract prefix.

### 6. `Profile.kt` / `ProfileStore` — discovery sidecar (profile TOML untouched)
The T33e TOML schema does NOT change (no new keys, no schema bump — T33e's
acceptance stays green verbatim). Metadata rides a machine-managed sidecar:

```
<baseDir>/projects/<name>.discovery.json    // GradleDiscovery.toJson(report), verbatim
```

`ProfileStore` gains (name guard reused on all three):
- `fun discoveryPath(name): Path`
- `fun saveDiscovery(name: String, json: String): Path` (createDirectories, overwrite)
- `fun loadDiscovery(name: String): String?` (null when absent — NOT an error)

The sidecar is a CACHE: absent → conventions, silently (today's behavior). Present
but unusable (gson parse failure, or `rootDir` != the profile's `project` value) →
print `metadata: ignoring discovery cache (<reason>) — using convention fallbacks`
and continue with no metadata. Never fail a watch/doctor run over the cache.

### 7. `cli/Main.kt` — wiring
- `resolveConfig(...)` return type becomes a small private
  `class Resolved(val config: ProjectConfig, val discoveryReport: DiscoveryReport?)`
  (report non-null iff the discovery path ran). `watch`/`doctor` use `.config`;
  every current output line stays as-is.
- **Discovery path:** after the existing `discovered:`/`resolved:` prints, build
  `report.moduleMetadata(modules, plan.variantName)` and pass it into
  `ProjectConfig(moduleMetadata = ...)`. When the map is non-empty print
  `metadata: <n> module(s) from discovery` (after `resolved:`).
- **Profile path (`--profile`, modules pinned → legacy branch):** after the profile
  loads, `ProfileStore.loadDiscovery(name)`; when present, parse with gson into
  `DiscoveryReport` (same class, same lenient nullability), apply the staleness
  rules of section 6, then `report.moduleMetadata(modules, effectiveVariant)` →
  `ProjectConfig(moduleMetadata = ...)`. Non-empty map prints
  `metadata: <n> module(s) from discovery cache`.
- **Explicit-flag path (no profile, no discovery):** `moduleMetadata` stays empty;
  ZERO new output — byte-identical to today (e2e regression contract).
- **`configure`:** when its resolution ran discovery, write the sidecar via
  `saveDiscovery(name, GradleDiscovery.toJson(report))` BEFORE the `saved:` line;
  when it did NOT (fully explicit flags), `Files.deleteIfExists(discoveryPath(name))`
  so a stale sidecar from an earlier configure can't linger. Output lines
  (`saved:`/`run:`) unchanged.
- **`config show`:** after the `expanded:` line, when a sidecar exists print
  `metadata: discovery cache present (<path>)`. No other change.
- **`WatchSession`:** the pre-probe app `compileTask` (built from the Request before
  probing) becomes
  `config.project.moduleMetadata[app.gradlePath]?.compileTask ?: <existing expression>`;
  the probe call site passes `config.project.moduleMetadata[it.gradlePath]`.

Contract check (unchanged from T33e): no new output line may start with the bare
`watching ` prefix; `metadata: ` and `apk: ` are safe.

### 8. Tests (host-only; engine test source set)
- `GradleDiscoveryTest` additions (reuse the T33c fixture JSONs, which already carry
  classOutputDirs/sourceDirs/resDirs/apkOutputDir): `moduleMetadata()` exact-variant
  match; `request.variant` override changes the lookup; lib without a matching
  variant name → no entry; kotlinJvm mapping (dirs only); app-only apkOutputDir.
- `ModuleSpecTest` additions (@TempDir): probe picks an existing metadata classes
  dir containing a `.class` over conventions; metadata dir existing but EMPTY (no
  `.class`) → convention fallback; metadata present but all dirs missing → fallback
  (and the loud line, capture stdout or assert message); task-name/resDirs/
  sourceRoots/apkOutputDirs overrides; **existing assertions pass unmodified** (the
  no-metadata freeze proof).
- New `ApkLocatorTest` (@TempDir): json pick beats a NEWER stale `.apk` in a later
  candidate dir (THE regression test for the stale-APK gotcha — name it so);
  variant mismatch skips to next dir; applicationId mismatch skips; two elements →
  newest existing; elements missing on disk → skip; malformed json → fallback;
  no json anywhere → newest-apk fallback returns the newest.
- `ProfileStoreTest` additions: saveDiscovery/loadDiscovery round-trip; absent →
  null; name guard on `discoveryPath("../evil")`.
- Existing suites (`ProjectConfigTest`, `TomlTest`, `ProfileTest`, `AdbTest`,
  `WireTest`, `ClassifierTest`, …) pass UNMODIFIED.

### 9. Docs
README: in the Profiles subsection add two sentences: `configure` also caches
Gradle discovery metadata next to the profile (`<name>.discovery.json`) and watch
uses it for build/APK/resource paths; delete or re-run `configure` to refresh. In
the troubleshooting section, note `apk: … (output-metadata.json)` as the normal
line and the fallback warning as the stale-risk signal. No quickstart restructure.

## Out of scope
Phase 6 (`prepare`/`start`, build fingerprints, refuse-to-watch-on-mismatch);
library selected-variant resolution (exact-name match only); migrating the init
script to the AGP `variant.sources` API; schema v2 / any fixture-pinned shape
change; profile TOML schema changes; forcing metadata capture when `configure` is
given fully-explicit flags (workaround: let discovery run, filter with
include/exclude); IDE-plugin consumption (phase 9); the multi-dimension
`sourceSetNames` convention splitter in ModuleSpec (metadata supersedes it when
available; the convention path keeps today's single-dimension behavior); any
protocol/device-side code; Doctor checks on metadata dirs.

## Acceptance
Run from repo root. Env per the standing gotcha — source by absolute path, then
export:
```bash
source /ABS/PATH/TO/repo/scripts/env.sh && export REPO_ROOT="$PWD"
./gradlew :engine:test :protocol:test :cli:compileKotlin :cli:installDist && echo TESTS-OK
CLI=cli/build/install/cli/bin/cli
export HOTRELOAD_CONFIG_DIR="$(mktemp -d)"

# configure (discovery runs) writes the sidecar next to the profile:
$CLI configure --project samples/multi-module --save-as t33f 2>&1 | grep -q "saved: t33f" && \
  test -f "$HOTRELOAD_CONFIG_DIR/projects/t33f.discovery.json" && echo SIDECAR-OK
grep -q '"classOutputDirs"' "$HOTRELOAD_CONFIG_DIR/projects/t33f.discovery.json" && echo SIDECAR-JSON-OK

# profile-driven doctor consumes the cache (device checks may fail — grep lines):
OUT=$($CLI doctor --profile t33f 2>&1) || true
echo "$OUT" | grep -q "metadata: .* from discovery cache" && echo PROFILE-METADATA-OK

# corrupt sidecar → loud ignore, no crash, doctor proceeds on conventions:
echo garbage > "$HOTRELOAD_CONFIG_DIR/projects/t33f.discovery.json"
OUT=$($CLI doctor --profile t33f 2>&1) || true
echo "$OUT" | grep -q "metadata: ignoring discovery cache" && \
  ! echo "$OUT" | grep -q "Exception in thread" && echo STALE-GUARD-OK

# sidecar absent → cache is optional, zero metadata output:
rm "$HOTRELOAD_CONFIG_DIR/projects/t33f.discovery.json"
OUT=$($CLI doctor --profile t33f 2>&1) || true
echo "$OUT" | grep -q "metadata:" && echo FAIL-NOCACHE || echo NOCACHE-OK

# zero-config discovery path carries metadata in the same run:
OUT=$($CLI doctor --project samples/single-module 2>&1) || true
echo "$OUT" | grep -q "metadata: .* from discovery" && echo DISCOVERY-METADATA-OK

# behavior freeze — explicit flags (the e2e shape) produce ZERO new output:
OUT=$($CLI doctor --project samples/single-module --app-id dev.hotreload.sample 2>&1) || true
echo "$OUT" | grep -Eq "metadata:|apk:" && echo FAIL-FREEZE || echo FREEZE-OK

# e2e scripts untouched (they ARE the regression contract):
git diff --exit-code e2e/ >/dev/null && echo E2E-FROZEN-OK
```
All 9 markers (TESTS-OK, SIDECAR-OK, SIDECAR-JSON-OK, PROFILE-METADATA-OK,
STALE-GUARD-OK, NOCACHE-OK, DISCOVERY-METADATA-OK, FREEZE-OK, E2E-FROZEN-OK) must
print; capture the output. These are HOST-ONLY — do not touch a device, do not run
e2e. Agent note (standing, from T33d/T33e): if any doctor/watch invocation hangs
printing nothing against a live device, that is the known PatchServer accept-loop
wedge — leave device work to the coordinator; never "fix" it by editing engine code.

Device gate (coordinator runs, emulator required — NOT the agent):
- **Force-stop both sample apps FIRST** (`adb shell am force-stop …`): the
  PatchServer wedge AND the T33e priming gotcha (`FAILURE TO REDEFINE …
  '$liveEditBytecode' missing` when a previous session primed classes) both present
  as confusing smoke failures; also never trust on-screen text that could be the
  previous session's swap.
- `./e2e/run.sh` (16 cases) + `./e2e/run-multi.sh` (4 cases) green UNMODIFIED.
- Zero-config live smoke: `watch --project samples/multi-module` → `discovered:` +
  `metadata: … from discovery` → ready → cross-module edit hot-swaps → a values
  resource edit prints `apk: … (output-metadata.json)` and `resource-swapped:`.
- Profile smoke: `configure --project samples/multi-module --save-as t33f-live` →
  `watch --profile t33f-live` → `profile:` + `metadata: … from discovery cache` →
  ready → edit hot-swaps.

Per the T33 rules: when acceptance passes, flip this file's `Status: TODO` →
`IN-REVIEW` and STOP — the coordinator reviews, runs the device gate, and commits.
