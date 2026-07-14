# T33e: external project profiles + `configure` / `config show` (T33 phase 4)
Status: DONE (2026-07-14, agy headless Gemini 3.5 Flash High + coordinator review)
Assignee: agy

## Outcome (2026-07-14)
Implemented by headless agy (`scripts/delegate.sh`, Gemini 3.5 Flash (High) — first
delegation on this model) faithfully to spec: every load-bearing diff reviewed, nothing
out of scope touched, no improvised design decisions. Coordinator re-verified everything
independently (not trusted from the agent):
- Fresh `--rerun-tasks`: engine+protocol suites green incl. the 22 new tests
  (TomlTest 12, ProfileTest 5, ProfileStoreTest 5); existing suites unmodified.
- All 12 host acceptance markers re-run green by the coordinator.
- Device gate on emulator-5554: `./e2e/run.sh` all PASS (272s, literals SKIP
  local-by-design) + `./e2e/run-multi.sh` 4/4 (50s), both UNMODIFIED. Live profile
  session: `configure --project samples/multi-module --save-as t33e-live` (discovery ran,
  profile file byte-exact to the schema) → `watch --profile t33e-live` → `profile:` line
  first, NO `discovered:` lines (profile pins modules), T33d auto-launch fired through
  the profile path (`launched: … (pid …)` via monkey), cross-module CoreLabel edit
  `hot-swapped: 1 redefined … 827ms` + revert swap, UI verified both ways.

Coordinator gotcha worth keeping (bit this validation, NOT a T33e bug): a live smoke
started against an app process left over from a previous watch session can hit
`FAILURE TO REDEFINE … Field '$liveEditBytecode' is missing` — T27/T28 priming is
per-session state, so a class primed by an EARLIER session (e.g. run-multi's T28 case)
rejects a fresh session's unprimed redefine. Always force-stop the target app between
watch sessions before judging a smoke; on-screen text can also be stale from the
previous session's swaps.

**Precondition:** T33d (PR #14, branch `t33d/device-modules-launch`) merged — this spec
builds directly on its `ProjectConfig.launchActivity`, `resolveWatchPlan`, and the
discovery trigger rule in `cli/Main.kt`. Do not start from a tree without it.

## Goal
Finish T33 phase 4: per-project configuration that lives OUTSIDE the target repository,
so a project needs zero flags after a one-time `configure`:

```text
~/.config/compose-hot-reload/projects/<name>.toml
```

1. `hotreload configure --project <dir> --save-as <name>` — resolve the full watch
   plan (same resolution as `watch`, discovery included) and persist it as a profile.
2. `hotreload watch|doctor --profile <name>` — load the profile as defaults; any CLI
   flag overrides it (precedence: CLI flag > profile > discovery > default).
3. `hotreload config show --profile <name>` — print the profile and its equivalent
   explicit command line.

All design decisions below are FIXED — do not improvise alternatives; a genuinely
needed new decision = stop and surface it.

## Spec

### 1. `engine/src/main/kotlin/dev/hotreload/engine/Toml.kt` — strict-subset TOML
No TOML library exists in the pinned offline Gradle caches (checked: gson,
jackson-core, moshi only), so profiles use a HAND-ROLLED strict subset. Everything the
writer emits is valid TOML (readable by any conforming tool); everything outside the
subset is a LOUD parse error — never a silent skip. Flat schema only, so the parser
stays trivial and reviewable.

`object Toml`:
- `fun parse(text: String): Map<String, Any>` — values are `String`, `Boolean`, `Long`,
  or `List<String>`.
- `fun writeString(s: String): String` — quotes + escapes for the writer (used by
  `Profile.toToml()`).

Grammar (line-based; anything else is an error naming the 1-based line number, shape
`"line <n>: <reason>"` wrapped by the caller):
- Blank lines and FULL-LINE comments (first non-whitespace char `#`) are skipped.
  NO inline/trailing comments: any content after a parsed value → `"line <n>: unexpected
  trailing content"`.
- `key = value` with bare keys `[A-Za-z0-9_-]+`. `[table]` headers →
  `"line <n>: tables are not supported"`. Duplicate key → `"line <n>: duplicate key '<k>'"`.
- Values (entirely on one line):
  - basic string `"..."` — escapes `\"`, `\\`, `\n`, `\t` ONLY; any other `\x` →
    error; raw control chars → error.
  - `true` / `false`.
  - non-negative integer `[0-9]+` → `Long`.
  - array of basic strings `["a", "b"]` (optional trailing comma allowed; `[]` allowed;
    multi-line arrays → error `"arrays must be on one line"`; non-string elements →
    error).
- Writer escaping: `\` and `"` (plus `\n`/`\t` if ever present in a value).

### 2. `engine/src/main/kotlin/dev/hotreload/engine/Profile.kt` — model + store

```kotlin
data class Profile(
    val project: String,                        // absolute path, REQUIRED
    val appId: String? = null,
    val variant: String? = null,
    val modules: List<String> = emptyList(),    // "gradlePath=relativeDir" entries, app FIRST
    val moduleVariants: List<String> = emptyList(), // "gradlePath=variant" (--module-variant form)
    val gradleArgs: List<String> = emptyList(),
    val projectJavaHome: String? = null,
    val launchActivity: String? = null,
    val literals: Boolean = false,
)
```

Serialization decision: `modules` / `moduleVariants` entries are EXACTLY the CLI flag
string forms (`ModuleSpec.Request.parse` input / `--module-variant` input). The profile
is a pure front-end over the existing flag parsing — no second config representation.

TOML schema (fixed key order as written; `Profile.toToml(name: String)` emits it):

```toml
# Compose Hot Reload profile "<name>" — written by `hotreload configure`.
# CLI flags override these values. Docs: README "Profiles".
schema = 1
project = "/abs/path/to/project"
app-id = "dev.hotreload.sample"
variant = "debug"
modules = [":app=app", ":feature=feature", ":core=core"]
# optional keys, written only when set:
module-variants = [":feature=stageDebug"]
gradle-args = ["--parallel"]
project-java-home = "/abs/path/to/jdk"
launch-activity = ".MainActivity"
literals = true
```

- `schema`: optional on read (default 1); value != 1 →
  `"unsupported profile schema <n> (this hotreload understands schema 1)"`. Always written.
- `project`: REQUIRED on read (`"profile is missing required key 'project'"`).
- All other keys optional. UNKNOWN key → `"unknown key '<k>' (allowed: schema, project,
  app-id, variant, modules, module-variants, gradle-args, project-java-home,
  launch-activity, literals)"` — catches typos in hand-edited files.
- Wrong value type for a key → loud error naming the key and expected type.
- NOT in the schema, deliberately: `device` (session/machine-specific, `--device` stays
  CLI-only), `--sdk` / `--build-tools` (machine toolchain, env-driven), include/exclude
  (discovery filters — `configure` CONSUMES them and persists the filtered result).
  Never store secrets/tokens in profiles.

`class ProfileStore(baseDir: Path)`:
- Default base dir (companion `default()`): `$HOTRELOAD_CONFIG_DIR` if set (test/CI
  hook), else `$XDG_CONFIG_HOME/compose-hot-reload` if XDG_CONFIG_HOME set, else
  `~/.config/compose-hot-reload`. Profiles live at `<baseDir>/projects/<name>.toml`.
- Name guard (SECURITY — the name becomes a filename): reject unless
  `name.matches(Regex("^[A-Za-z0-9._-]+\$")) && ".." !in name` — the regex allows `.`
  so `..` needs the explicit reject (same pattern as PatchServer's InjectDex guard).
  Message: `"invalid profile name '<name>' (allowed: [A-Za-z0-9._-], no '..')"`.
- `fun load(name): Profile` — name guard → missing file →
  `"profile '<name>' not found: <path>"` → `Toml.parse` + schema mapping, all errors
  prefixed `"profile '<name>': "`.
- `fun save(name, profile): Path` — name guard, `Files.createDirectories` on the
  projects dir, overwrite silently (overwrite = refresh), return the path.
- `fun path(name): Path` (guarded) for messages.

Injection-guard note (per the standing security checklist): profile values flow into
the SAME `ProjectConfig` init as CLI flags, so the applicationId/launchActivity device-
shell regex guards from T33d apply to hand-edited profiles automatically. Paths and
gradle-args are host-side ProcessBuilder args (no shell). Nothing new to validate
beyond the name guard.

### 3. `cli/Main.kt` — `--profile` merge + trigger-rule interaction
New option (both `watch` and `doctor`; USAGE updated):
```text
  --profile <name>    Load defaults from ~/.config/compose-hot-reload/projects/<name>.toml
                      (written by 'hotreload configure'); any explicit flag overrides it
```

Merge is at the FLAG-STRING level, BEFORE the trigger rule, so precedence
(CLI flag > profile > discovery > default) falls out of the existing code path
untouched. With `profile` loaded (else all merges are just the CLI value):
- `project` = `--project` ?: `profile.project`; neither →
  `fail("--project is required (or provide it via --profile)")`.
- `appIdOpt` = `--app-id` ?: `profile.appId`.
- `moduleOpt` = `--module` ?: (`profile.modules` joined with `","` when non-empty).
- `variant` (both modes) = `--variant` ?: `profile.variant` (?: `"debug"` in legacy
  mode; discovery mode passes the merged nullable into `resolveWatchPlan` as today).
- `--module-variant` list = CLI list if non-empty else `profile.moduleVariants`.
- `--gradle-arg` list = CLI list if non-empty else `profile.gradleArgs` (REPLACE, not
  append — consistent with "flag overrides profile").
- `projectJavaHome`, `launchActivity` = CLI ?: profile.
- `literals` = `--literals` flag OR `profile.literals` (a profile's `literals = true`
  cannot be disabled from the CLI — edit the profile; note this in README).
- `--device`, `--include-module`, `--exclude-module`: CLI-only, never merged.

Trigger rule: UNCHANGED T33d rule, evaluated on the MERGED values — discovery runs iff
effective modules absent AND (effective appId absent OR filters given). Consequences
(intended): a `configure`-written profile always pins modules + app-id → `watch
--profile <name>` NEVER discovers (fast start, deterministic); a hand-trimmed profile
without `modules` may still discover — by design. Filters + effective modules → the
existing guard fires; reword it to
`"--include-module/--exclude-module filter the discovered module set; with --module or a
profile that pins modules, edit the list directly"` (KEEP the "edit the list directly"
substring — T33d's acceptance greps it).

- On successful profile load print EXACTLY `profile: <name> (<path>)` as the FIRST
  output line. (Contract check: no new output may contain the bare `watching ` prefix —
  that is the session-ready line e2e/IDE grep for; `profile: `/`saved: `/`expanded: `
  are safe.)
- Invocations WITHOUT `--profile` must produce byte-identical output to today — e2e
  passes no `--profile`; that is the regression contract.
- Wrap the `ProjectConfig(...)` constructions (both modes) in
  `try { ... } catch (e: IllegalArgumentException) { fail(e.message ?: "invalid configuration") }`
  so the T33d init guards surface as clean `hotreload: <message>` failures instead of
  stack traces (error-path polish only; valid inputs unchanged).

### 4. `hotreload configure` — resolve + persist
Synopsis: `hotreload configure --project <dir> --save-as <name> [options]`.
- Accepted options: exactly the watch resolution set — `--app-id`, `--module`,
  `--module-variant`, `--app-module`, `--app-module-dir`, `--variant`,
  `--include-module`, `--exclude-module`, `--gradle-arg`, `--project-java-home`,
  `--launch-activity`, `--literals`. NOT accepted: `--profile` (configure never chains
  from a profile — v1 keeps one direction), `--device`, `--sdk`, `--build-tools`.
- `--save-as` REQUIRED (`fail("--save-as <name> is required")`); name guard applies.
- Resolution: REFACTOR the watch/doctor resolution block (trigger rule + discovery path
  + legacy path, section 3's merged form with no profile input) into a shared private
  function in Main.kt returning `ProjectConfig`, and call it from `watch`, `doctor`,
  and `configure` — one code path, no drift. `configure` prints the same
  `discovered:`/`resolved:` lines when discovery runs. No SDK/adb/device access, ever.
- PERSISTED = the RESOLVED plan (decision: resolved, not raw flags — deterministic
  re-runs, profile doubles as documentation; refresh = re-run configure):
  ```kotlin
  Profile(
      project = config.projectDir.toString(),
      appId = config.applicationId,
      variant = config.variant,                 // always concrete, always written
      modules = config.modules.map { "${it.gradlePath}=${it.relativeDir}" },
      moduleVariants = config.modules.filter { it.variant != null }
                           .map { "${it.gradlePath}=${it.variant}" },
      gradleArgs = config.gradleArgs,
      projectJavaHome = config.projectJavaHome?.toString(),
      launchActivity = config.launchActivity,
      literals = config.literals,
  )
  ```
  (include/exclude are consumed by resolution and vanish; the filtered module list is
  what persists.)
- Output on success, exactly two lines:
  ```
  saved: <name> -> <path>
  run: hotreload watch --profile <name>
  ```

### 5. `hotreload config show --profile <name>`
- `config` requires subcommand `show` (anything else → usage fail); `--profile`
  REQUIRED. Loads via `ProfileStore` (so parse/validation errors surface here) and
  prints:
  1. `profile: <name> (<path>)`
  2. the file contents VERBATIM (trailing newline as-is)
  3. `expanded: hotreload watch --project <project> [--app-id <id>] [--module <m1,m2>]
     [--variant <v> only when != "debug"] [--module-variant <spec>]...
     [--gradle-arg <a>]... [--project-java-home <p>] [--launch-activity <a>]
     [--literals]` — flags in exactly this order, each emitted only when the profile
     has the value. (Informational line; values printed raw, same caveat as
     `watchCommandFor`.)

### 6. Tests (host-only; engine test source set)
- New `TomlTest`: string/escape round-trips; booleans; integer; array (incl. trailing
  comma + empty); full-line comment skipped; blank lines; ERRORS: inline comment after
  value, `[table]`, duplicate key, unknown escape, multi-line array, non-string array
  element, garbage line — each asserts the message shape.
- New `ProfileTest`: `toToml` → `Toml.parse` → `Profile` round-trip (all fields set,
  and minimal project-only); optional keys omitted when unset; unknown key error lists
  allowed keys; `schema = 2` rejected; missing `project` rejected.
- New `ProfileStoreTest` (JUnit `@TempDir` as baseDir): save→load round-trip; overwrite
  updates; `load("nope")` → not-found message with path; name guard rejects `../evil`,
  `a/b`, `a;b`, empty; accepts `my-app.v2`.
- Existing suites (`GradleDiscoveryTest`, `ProjectConfigTest`, `AdbTest`, `WireTest`)
  pass UNMODIFIED.

### 7. Docs
- README: add a short "Profiles" subsection (after the watch options table): the three
  commands, the profile path, precedence one-liner, the literals-cannot-be-disabled
  note. Options table: add `--profile`. Do NOT restructure the quickstart.

## Out of scope
`config list`/`edit`/`delete`, `configure --profile` (profile-in), profiles for
`inspect`, `--device`/SDK/build-tools in profiles, IDE-plugin profile consumption
(phase 9), output-dir/APK metadata (phase 5), `prepare`/`start` (phase 6), any
protocol/device-side code, TOML features beyond the subset (tables, inline comments,
multi-line), remote/shared profile locations.

## Acceptance
Run from repo root. Env per the standing gotcha — source by absolute path, then export:
```bash
source /ABS/PATH/TO/repo/scripts/env.sh && export REPO_ROOT="$PWD"
./gradlew :engine:test :protocol:test :cli:compileKotlin :cli:installDist
CLI=cli/build/install/cli/bin/cli
export HOTRELOAD_CONFIG_DIR="$(mktemp -d)"

# configure via discovery (single-module):
$CLI configure --project samples/single-module --save-as t33e-single 2>&1 | grep -q "saved: t33e-single" && echo CONFIGURE-OK
grep -q 'app-id = "dev.hotreload.sample"' "$HOTRELOAD_CONFIG_DIR/projects/t33e-single.toml" && echo TOML-OK

# configure consumes filters — the FILTERED module list persists:
$CLI configure --project samples/multi-module --exclude-module :core --save-as t33e-multi >/dev/null 2>&1
grep -q 'modules = \[":app=app", ":feature=feature"\]' "$HOTRELOAD_CONFIG_DIR/projects/t33e-multi.toml" && echo FILTER-PERSIST-OK

# show:
$CLI config show --profile t33e-single 2>&1 | grep -Eq "expanded: hotreload watch --project .* --app-id dev.hotreload.sample --module :app=app" && echo SHOW-OK

# profile-driven doctor: profile line prints, discovery does NOT run
# (doctor may FAIL on device checks without an emulator — grep lines, ignore exit):
OUT=$($CLI doctor --profile t33e-single 2>&1) || true
echo "$OUT" | grep -q "profile: t33e-single" && echo PROFILE-LINE-OK
echo "$OUT" | grep -q "discovered:" && echo FAIL-PROFILE-DISCOVERED || echo PROFILE-NODISCOVER-OK

# precedence: an (invalid) CLI --app-id must override the profile's valid one and hit
# the ProjectConfig injection guard — proving flag > profile:
$CLI doctor --profile t33e-single --app-id 'bad;id' 2>&1 | grep -q "applicationId contains characters outside" && echo PRECEDENCE-OK

# guards:
$CLI doctor --profile no-such 2>&1 | grep -q "profile 'no-such' not found" && echo MISSING-GUARD-OK
$CLI doctor --profile '../evil' 2>&1 | grep -q "invalid profile name" && echo NAME-GUARD-OK
printf 'schema = 1\nproject = "/tmp/x"\nbogus-key = "x"\n' > "$HOTRELOAD_CONFIG_DIR/projects/t33e-broken.toml"
$CLI doctor --profile t33e-broken 2>&1 | grep -q "unknown key 'bogus-key'" && echo UNKNOWN-KEY-OK
$CLI doctor --profile t33e-multi --include-module :core 2>&1 | grep -q "edit the list directly" && echo FILTER-GUARD-OK

# behavior freeze — no --profile => ZERO new output (e2e regression contract):
OUT=$($CLI doctor --project samples/single-module --app-id dev.hotreload.sample 2>&1) || true
echo "$OUT" | grep -q "profile:" && echo FAIL-FREEZE || echo FREEZE-OK
```
All 12 markers (CONFIGURE-OK, TOML-OK, FILTER-PERSIST-OK, SHOW-OK, PROFILE-LINE-OK,
PROFILE-NODISCOVER-OK, PRECEDENCE-OK, MISSING-GUARD-OK, NAME-GUARD-OK, UNKNOWN-KEY-OK,
FILTER-GUARD-OK, FREEZE-OK) must print; capture the output. These are HOST-ONLY —
do not touch a device. Agent note from T33d: if any doctor/watch invocation hangs
printing nothing against a live device, that is the known PatchServer accept-loop
wedge — leave device work to the coordinator; never "fix" it by editing engine code.

Device gate (coordinator runs, emulator required — NOT the agent):
- `./e2e/run.sh` (16 cases) + `./e2e/run-multi.sh` (4 cases) green UNMODIFIED — their
  explicit invocations must stay byte-identical (no `--profile` anywhere in them).
- Live profile session: force-stop the sample apps first (wedge), then
  `configure --project samples/multi-module --save-as t33e-live` →
  `watch --profile t33e-live` → `profile:` line → ready → cross-module edit hot-swaps.

Per the T33 rules: when acceptance passes, flip this file's `Status: TODO` →
`IN-REVIEW` and STOP — the coordinator reviews, runs the device gate, and commits.
