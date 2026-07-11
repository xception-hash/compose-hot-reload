# T32: Security hardening — P1/P2 (defense-in-depth + supply chain)
Status: DONE (merged to main via PR #5, 2026-07-10 — commit 55fb4ca; both judgment flags resolved: tripwire fail-hard, STRICT_REPRO=true kept after Temurin repro passed)
Assignee: agy (all items) — with the two noted judgment flags surfaced back to Opus/the maintainer

## Goal
Land the delegable P1/P2 items from `docs/security-hardening.md`. Each item is INDEPENDENTLY
droppable — do them in order, stop anytime. **None of these is the P0 fix** — the P0 injection
holes (peer-cred check in PatchServer, debuggable guard in HotReloadInitializer, socket read
timeout) are done by Opus and land separately; item 2 below DEPENDS on that P0 change being on
`main` first (see its precondition). Everything here is mechanical: read the plan doc, follow the
pointers, do not re-research the threat model.

Authoritative source for WHY each item exists: `docs/security-hardening.md` (threat model + P0–P2).
Do not restate the threat model in code — item 5 (SECURITY.md) quotes it, nothing else needs it.

## Spec

### 1 (agy) — Release-variant tripwire in `gradle-plugin` (plan P1 item 3)
Defense-in-depth on top of the app module's `debugImplementation` wiring: fail (or warn loudly)
at configuration time if `com.github.xception-hash.compose-hot-reload:runtime-client` ends up on a
**non-debuggable** variant's runtime classpath. The AAR's own runtime debuggable-guard (P0 item 2)
is the last line; this catches the mistake at build time with a clear message.

- File: `gradle-plugin/src/main/kotlin/dev/hotreload/gradle/HotReloadPlugin.kt`. Same fail-loud
  philosophy and `afterEvaluate` placement as the existing T18 guards at the bottom of `apply()`
  (lines ~87–113 — mimic their `GradleException` message style: name the project path + say what to
  do). The plugin only adds the dependency inside the `com.android.application` block, so the walk
  belongs there.
- Mechanism: in `afterEvaluate` (application module only), for each release / non-debuggable
  `ApplicationVariant`, inspect its runtime classpath configuration for a `runtime-client`
  dependency. Cheapest reliable form: resolve the variant's `runtimeConfiguration` (AGP variant API)
  and match on group `com.github.xception-hash.compose-hot-reload` + name `runtime-client`. If the
  app never adds it to release (the plugin doesn't), a hand-added `implementation`/`releaseImplementation`
  is exactly what this must catch → `GradleException`.
- Reference only (do NOT copy verbatim): Gemini's `hardened_HotReloadPlugin.kt` (path in
  docs/security-hardening.md triage section) sketches the same tripwire, but uses name-based
  `RuntimeClasspath && !debug` matching (false-positives on custom debuggable build types — use the
  variant-API `debuggable` check per this spec), `configurations.forEach` at afterEvaluate (misses
  late-registered configs — `configureEach`), and strips every doc comment. Its
  `resolutionResult.allComponents` group/name match is the one usable snippet.
- **Judgment flag (surface, don't guess):** fail-hard vs warn is a UX call. Default to
  `GradleException` (fail) — the plan says "fail (or at minimum warn loudly)". If the variant-API
  walk proves flaky across AGP 9.2.1 configuration timing, downgrade to a loud `project.logger.error`
  and note it here; do NOT ship a silently-passing walk.
- Acceptance: add a Gradle TestKit or a manual repro under the plugin's tests: a throwaway app
  module that adds `runtime-client` as plain `implementation` (release classpath) → configuration
  fails with the new message; the samples (`debugImplementation` only) still configure clean
  (`./gradlew -p samples/single-module help` and `-p samples/multi-module help` green, or the
  existing sample assemble). Verify with `source scripts/env.sh` first (REPO_ROOT/JAVA_HOME).

### 2 (agy — PRECONDITION: P0 peer-cred change is on `main` — **SATISFIED 2026-07-10**: code committed `a74dc27`, device-verified all 3 checks, see plan doc P0 section) — guard-regression tests + InjectDex `..` parity (plan P1 item 4)
The existing input guards work but nothing stops a refactor from dropping them. Pin them, and close
one real inconsistency Opus found in the security review.

**2a — code fix (do first, tiny):** `InjectDex` validates only `safeName.matches(request.name)`
(`runtime-client/lib/src/main/kotlin/dev/hotreload/client/PatchServer.kt` `handle()` → `is
Request.InjectDex`), but `safeName = [A-Za-z0-9._-]+` lets `".."` through — whereas `loadResources`
adds `&& ".." !in overlayDir`. Not currently exploitable (no `/` in the regex, so `File(codeCacheDir,
"..")` resolves to a directory and the `writeBytes` fails), but add the same explicit `".." !in
request.name` reject to `InjectDex` for parity. One line.

**2b — tests.** Two homes, because the guards live in two modules:
- Pure-protocol guards → **`:protocol`** (extend `WireTest`, JVM unit test, no Android): frame-length
  rejection and bad-count rejection both throw `IOException` — `Wire.readFrame`/`readCount` check
  against `Protocol.MAX_FRAME_BYTES` (`protocol/src/main/kotlin/dev/hotreload/protocol/Wire.kt`
  ~lines 213, 233). Craft a frame whose length prefix > MAX_FRAME_BYTES and assert it throws before
  allocating; same for a negative/oversized count inside a REDEFINE/LIVE_EDIT body.
- PatchServer path guards → **`:runtime-client`** (separate gradle build: `cd runtime-client &&
  source ../scripts/env.sh && ./gradlew :runtime-client:testDebugUnitTest`). These need `Context`,
  so DON'T try to stand up the whole server. Instead **extract the validation into pure functions**
  — e.g. `internal fun validateDexName(name: String)` and `internal fun validateOverlayDir(dir:
  String)` that throw on bad input — and unit-test those directly (JVM, no Robolectric). Pin:
  InjectDex name regex accepts `a.dex`/`x_1-2` and rejects `../x`, `a/b`, `..`, `` (empty); overlay
  dir accepts a single segment and rejects `..`, `a/b`. (The peer-cred allowlist itself needs a
  `LocalSocket` and is device-verified by Opus's negative test — don't unit-test it here.)
- Optional: a test asserting the injected dex is written read-only. `dex.setReadOnly()` needs a real
  file; if it can't be reached without a `Context`, skip it and note that the negative-permission
  intent is covered by the device e2e — don't add Robolectric just for this.
- Acceptance: `:protocol:test` green (existing 12 + new) and `:runtime-client:testDebugUnitTest`
  green. Both guards demonstrably fail the build if the corresponding check is deleted (sanity: try
  it locally, then restore).

### 3 (agy) — interp.dex reproducibility gate (plan P2 item 6)
Turn the `interp.dex.PROVENANCE` claim into an enforced invariant: a CI job that rebuilds the dex
and compares against the committed resource, so the 831 KB binary in git is auditable.

- Inputs already exist: `scripts/build-interp-dex.sh` (deterministic, pinned tools-base + committed
  `third_party/generated/liveedit/Proxies.java`) → `engine/src/main/resources/dev/hotreload/interp.dex`
  (831448 B). Provenance/toolchain pins are in `interp.dex.PROVENANCE`.
- New workflow (or job) that: installs the pinned SDK (build-tools 36.0.0 for d8) + the tools-base
  clone (`scripts/fetch-aosp-deployer.sh` fetches it — check it's the pinned SHA
  `14c65dad2cbdc26198c66375d4e0cef5269436e0`), runs `scripts/build-interp-dex.sh`, then
  `cmp` the output against the committed `interp.dex`. Fail on drift.
- **Judgment flag (surface, likely bites on first run):** byte-for-byte reproducibility depends on
  the EXACT `javac` — the committed dex was built with the **Android Studio JBR** (`--release 8`),
  and CI runs Temurin 21. Different javac builds can emit different (still-valid) bytecode → `cmp`
  fails without any tampering. Two acceptable outcomes, pick based on the first CI run:
  (a) if it reproduces byte-identical on Temurin, ship the strict `cmp` gate;
  (b) if it drifts, DON'T force it — downgrade to a smoke invariant (dex builds successfully +
  `dexdump` class count matches the provenance, e.g. 147 `Proxies$*` present, zero `kotlin/*`
  defined) and record here that strict repro needs JBR on the runner (deferred). Do not merge a gate
  that's red for a non-security reason.
- Acceptance: the job runs green on a PR; deleting a byte from the committed dex (local experiment)
  makes it red.

### 4 (agy) — Workflow hygiene (plan P2 item 7)
Cheap CI supply-chain hardening on `.github/workflows/e2e.yml` (and any new workflow from item 3).
- Add a top-level `permissions: contents: read` block (currently none → the default token is broader
  than needed; the job only checks out + uploads a log artifact — `contents: read` + the artifact
  step's implicit needs suffice; if `upload-artifact` complains, scope `actions: write` on that job
  only, don't widen the top level).
- Pin every third-party action by full commit SHA (not the floating tag), with the tag in a trailing
  comment. **Use EXACTLY these SHAs — each verified against upstream tags via `gh api` on 2026-07-07
  (= what the current floating tag resolves to, so zero behavior change). Do NOT generate or "recall"
  a SHA: Gemini's review fabricated 4 of 6 (see docs/security-hardening.md triage section). If you
  must change one, re-verify with `gh api repos/<action>/tags` first.**
  - `actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683` # v4.2.2
  - `actions/setup-java@c1e323688fd81a25caa38c78aa6df2d33d3e20d9` # v4.8.0
  - `android-actions/setup-android@9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407` # v3.2.2
  - `gradle/actions/setup-gradle@748248ddd2a24f49513d8f472f81c3a07d4d50e1` # v4.4.4
  - `reactivecircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d` # v2.38.0
  - `actions/upload-artifact@65c4c4a1ddee5b72f698fdd19549f0f0fb45cf08` # v4.6.0
- Never introduce `pull_request_target` with secrets (none exists today — just don't add one).
- Also fix the stale header comment: e2e.yml line 3 says "5 cases"; the suite is now 16
  (`e2e/run.sh`). One-line doc fix while you're in the file.
- Acceptance: `e2e.yml` still triggers and runs green on a PR (the pin doesn't change behavior);
  `actionlint` clean if available.

### 5 (agy) — `SECURITY.md` (plan P2 item 8)
One page at repo root. Content, all already written — assemble, don't invent:
- The threat-model statement from `docs/security-hardening.md` "Threat model" section: what IS
  defended (other apps on device, release builds, artifact integrity) and what is BY DESIGN (arbitrary
  code injection into your own debug app is the product; authentication is the control, not bytecode
  validation). Condense the doc's prose — link to `docs/security-hardening.md` for the full model.
- Supported versions: 0.1.x (current).
- One sentence scoping `spike/` (incl. the toy app's exported PatchReceiver) as dev-only
  scaffolding — never shipped, never install the toy app on a non-dev device.
- Private report channel: GitHub private vulnerability reporting (Security tab → "Report a
  vulnerability") — the standard OSS default; name it explicitly.
- Acceptance: `SECURITY.md` renders on GitHub and the "Report a vulnerability" link/text is correct
  for this repo. Doc-only — no e2e.

## Out of scope
- P0 (peer-cred check, debuggable guard, first-frame read timeout — session-long timeout was a
  regression, see plan doc P0 section) — Opus, separate change, landed; item 2 unblocked.
- Distribution signing (plan P2 item 9) — rides the planned Phase 4 Maven Central / Marketplace
  publish (GPG mandatory there); no separate work now.
- Any change to protocol wire format or the injection semantics themselves.

## Acceptance (per item — any subset may ship)
1. Release-classpath repro fails config with the new message; samples configure clean.
2. `:protocol:test` + `:runtime-client:testDebugUnitTest` green with the new guard tests; InjectDex
   rejects `..`. (Requires P0 on `main`.)
3. Reproducibility (or smoke) job green on a PR; drift makes it red.
4. `e2e.yml` pinned by SHA + `permissions: contents: read`; still green on a PR.
5. `SECURITY.md` present, correct report channel.
Standing rule: `source scripts/env.sh` (absolute path) before any gradle; check artifact mtimes,
not just exit code (a JAVA_HOME-less gradlew no-ops exit 0).
