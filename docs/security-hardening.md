# Security hardening plan

Drafted 2026-07-07 (verified against code at HEAD 04a40f5). Scope: the shipped v0.1.x
surface — runtime-client AAR, engine/CLI, gradle-plugin, IDE plugin, CI/distribution.

## Threat model

What this tool is: a **debug-only** dev tool that, by design, lets the host inject and
execute arbitrary code in the app process. "The developer's machine can run code in the
developer's debug app" is the product, not a vulnerability. The boundaries we actually
defend are:

1. **Other apps on the same device** must not be able to reach the injection surface.
2. **Release / non-debuggable builds** must never expose the injection surface, even if
   the dependency is misconfigured onto a release classpath.
3. **The artifacts we ship** (AAR, committed interp.dex, plugin zip) must be what the
   source says they are.

Trust boundaries, as built today:

| Surface | Today | Verdict |
|---|---|---|
| `PatchServer` — `LocalServerSocket("hotreload-<pkg>")`, abstract namespace | **No peer check.** Abstract-namespace sockets need no filesystem permission; any co-installed app (zero permissions) can connect and send `InjectDex`/`Redefine`/`LiveEditClasses` → arbitrary code exec in the app's process, plus `LoadResources`/`Reset`. | **P0 gap** |
| Server startup (`HotReloadInitializer.kt:18`) | Started unconditionally — no `FLAG_DEBUGGABLE` check. Plugin wires the AAR as `debugImplementation`, but a hand-added release dependency would run the server in production. (JVMTI attach fails on non-debuggable, but the server + resource loading still run.) | **P0 gap** |
| Wire framing (`Wire.kt`) | Frame length and counts validated against `Protocol.MAX_FRAME_BYTES` before allocation. | OK |
| `InjectDex` / `LoadResources` paths (`PatchServer.kt`) | Filename regex `[A-Za-z0-9._-]+` + explicit `..` reject; dex written read-only in `codeCacheDir`. | OK — lock with tests |
| Host `adb forward tcp:0` | Any local process on the dev machine can hit the forwarded port. Same-user local attacker already has adb itself, so adb is the real boundary. | Accepted (P2 option) |
| Committed `engine/src/main/resources/dev/hotreload/interp.dex` (495 KB binary) | `.PROVENANCE` sibling exists, but nothing verifies the blob matches `scripts/build-interp-dex.sh` output. Unauditable binary in git. | **P2 gap** |
| CI (`.github/workflows/e2e.yml`) | Actions pinned by tag, default token permissions. | P2 |
| Distribution (JitPack tag builds, IDE plugin zip w/ bundled CLI) | JitPack builds from source (tag = integrity anchor). Maven Central signing arrives with the planned Phase 4 publish. | P2 / deferred |

Explicitly **not** threats we defend: payload contents (arbitrary code is the feature —
authentication is the control, not bytecode validation), the engine running the user's
own Gradle build, and anyone who already has adb access to the device (they can inject
via adb regardless of us).

## P0 — close the on-device injection hole (do before any wider release)

**1. Peer-credential check in `PatchServer`.** On accept, read
`LocalSocket.getPeerCredentials().uid` and require it to be one of: the app's own uid
(`Process.myUid()`), shell (2000 — adb-forwarded connections arrive from adbd as
shell), or root (0). Reject anything else before reading a single frame, with a loud
log line naming the rejected uid. This is the standard fix for the abstract-socket
class of bug (the old Stetho/WebView-devtools pattern).

> **Verified 2026-07-07 on the pinned emulator (API 36):** a `LocalServerSocket` in the
> abstract namespace, reached via `adb forward tcp:0 localabstract:...` exactly as the
> engine does, reports `getPeerCredentials().uid == 2000` (shell). So the engine/CLI
> path — the only legitimate client — lands squarely inside the allowlist, and the
> proposed check does **not** break it. adbd forwards as shell regardless of the uid the
> server process runs as, so this holds for the real app-uid server too.

**2. Debuggable-only guard in `HotReloadInitializer`.** Bail (loud log, server never
constructed) unless `applicationInfo.flags` has `FLAG_DEBUGGABLE`. Belt-and-braces on
top of `debugImplementation` wiring — protects against release-classpath mistakes and
whitelabel builds that flip `debuggable`.

Verification: full `e2e/run.sh` still green (adb path = shell uid, allowed); manual
negative check — from the toy app, attempt a `LocalSocket` connect to the sample app's
`hotreload-dev.hotreload.sample` socket and assert rejection; confirm a
`debuggable=false` build logs the bail line and opens no socket.

## P1 — defense in depth (next session after P0)

**3. Release-variant tripwire in `gradle-plugin`.** Fail (or at minimum warn loudly at)
configuration time if `dev.hotreload:runtime-client` resolves on a non-debuggable
variant's runtime classpath. Cheap `afterEvaluate` walk, same fail-loud philosophy as
T18.

**4. Regression tests for the existing guards.** Unit tests pinning: frame-length
rejection, `InjectDex` filename regex, `..` rejection in overlay dir names, dex file
written read-only. These guards exist but nothing stops a refactor from dropping them.

**5. (Optional) per-session handshake token.** Engine writes a random token into
`code_cache` via the existing `run-as` machinery at watch start; first request frame
must carry it. Only worth doing if we ever loosen the uid allowlist (e.g. wireless
debugging edge cases where the peer uid differs); otherwise the uid check subsumes it.

## P2 — supply chain + CI

**6. interp.dex reproducibility gate.** CI job: run `scripts/build-interp-dex.sh`,
byte-compare output against the committed resource, fail on drift. Turns the
`.PROVENANCE` claim into an enforced invariant and makes the committed binary auditable.

**7. Workflow hygiene.** Pin GitHub Actions by commit SHA; add top-level
`permissions: contents: read` to `e2e.yml`; never introduce `pull_request_target` with
secrets.

**8. `SECURITY.md`.** One page: the threat model statement above (what's defended,
what's by-design), supported versions, private report channel. Cheap, expected for OSS.

**9. Distribution signing.** Rides on the already-planned Maven Central publish (GPG
mandatory there) and JetBrains Marketplace signing for the IDE plugin. No separate work
now; note it so it isn't forgotten when Phase 4 publishing lands.

## Accepted risks (documented, not fixed)

- Localhost forwarded port reachable by other same-user processes on the dev machine —
  adb itself is the boundary; item 5 is the mitigation if this ever matters.
- No validation of injected bytecode/dex contents — authentication is the control.
- Interpreter executes unverified method bodies in-process — inherent to LiveEdit.

## Execution / delegation

- **P0 (items 1–2): Claude.** Small diff but touches PatchServer semantics and needs
  on-device verification against the e2e gate; peer-cred subtleties (adbd uid, wireless
  debugging) need judgment. Protocol unchanged (rejection is a connection drop, not a
  new message) — no version bump.
- **P1 item 3 + P2 items 6–8: delegable to agy** as one spec (`tasks/T32-security-hardening.md`,
  to be written) — mechanical Gradle/CI/docs work with exact acceptance commands.
- **P1 item 4:** delegable once P0 lands (tests describe fixed behavior).

Related: docs/PLAN.md Phase 4 (robustness hardening — separate track),
tasks/T30-robustness-leftovers.md.
