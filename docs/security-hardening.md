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
| `PatchServer` — `LocalServerSocket("hotreload-<pkg>")`, abstract namespace | **No peer check.** Abstract-namespace sockets need no filesystem permission; any co-installed app (zero permissions) can connect and send `InjectDex`/`Redefine`/`LiveEditClasses` → arbitrary code exec in the app's process, plus `LoadResources`/`Reset`. | **P0 — fixed, device-verified 2026-07-10** |
| Server startup (`HotReloadInitializer.kt:18`) | Started unconditionally — no `FLAG_DEBUGGABLE` check. Plugin wires the AAR as `debugImplementation`, but a hand-added release dependency would run the server in production. (JVMTI attach fails on non-debuggable, but the server + resource loading still run.) | **P0 — fixed, device-verified 2026-07-10** |
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

## P0 — close the on-device injection hole (do before any wider release) — DONE ✅ (code 2026-07-07, device-verified 2026-07-10)

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

> **P0 DONE — device-verified 2026-07-10 on the pinned emulator (API 36), all three checks:**
>
> - **(a) Positive path:** full `./e2e/run.sh` GREEN, 15/15 cases in 275s (live-literals =
>   standard opt-in skip), on the reinstalled P0 AAR — shell-uid (2000) traffic authorized,
>   engine path unbroken.
> - **(b) Negative path — rejected one layer EARLIER than expected:** the toy app
>   (`untrusted_app`, own uid) probing `hotreload-dev.hotreload.sample` gets
>   `IOException: Permission denied` at *connect*: SELinux per-app MLS categories deny it in
>   the kernel (`avc: denied { connectto } … scontext=u:r:untrusted_app:s0:c214,… tcontext=
>   u:r:untrusted_app:s0:c221,… permissive=0`, path hex = `\0hotreload-dev.hotreload.sample`).
>   So on API 30+ the co-installed-app hole is already platform-blocked, and `authorize()`s
>   uid gate is defense-in-depth behind it — its reject branch is unreachable from
>   `untrusted_app` on this image (low-targetSdk attackers land in `untrusted_app_27` with
>   `levelFrom=user` categories, which still lack the target's per-app categories → same
>   denial). The gate still covers non-app domains and any future/vendor policy laxity; the
>   allow path is exercised by every e2e run. Probe vehicle: `--es probe <socket>` branch in
>   the spike toy app's `PatchReceiver` (committed — reusable for T32 guard tests).
> - **(c) debuggable=false bail:** sample built with `isDebuggable=false` on the debug build
>   type (runtime-client still on the classpath — the exact misconfig scenario, since the
>   plugin wires by configuration name): logcat shows
>   `W HotReload: not a debuggable build — hot reload disabled, no server started` and
>   `/proc/net/unix` has no `hotreload` entry. Debuggable reinstall restores the socket.
>
> **Regression found by CI (run 29097024635) and fixed same day:** the original P0 armed
> `soTimeout = 30s` for the WHOLE session, but the engine holds one connection per watch and
> legitimately idles between saves — any >30s pause (CI's cold first compile; a developer
> thinking) made the server's blocking read throw, killing the session (`session error` at
> connect+31s on device; engine's next request → `device closed connection mid-request`).
> Local e2e never idles 30s, so it passed while CI failed on case 1. Fix: the timeout now
> guards only the FIRST frame (the connect-and-stall wedge window) and is cleared once a
> valid frame arrives. Device-verified all three ways: (1) 45s idle → save →
> `hot-swapped … in 2077ms` (the repro failed identically pre-fix); (2) a silent peer is
> still dropped at exactly +31s (`EAGAIN` → session closed, peer reads EOF); (3) the next
> client after a stall-drop handshakes fine (`device: … protocol=8`). Full e2e re-run green.

> **Related open finding (2026-07-13, portability session):** the inverse wedge exists
> POST-handshake — once a valid frame clears the first-frame timeout, session reads block
> forever, and when a watcher is killed (pkill) its dead peer's EOF did not propagate
> through the stale adb forward: the serial accept loop stayed blocked in read, so every
> subsequent client's ping() hung in SILENCE (CLI prints nothing before the device line —
> looks like a hang). Observed live on emulator API 36 after repeated e2e watcher kills;
> recovery = force-stop + relaunch the app. Not a security hole (requires shell-authorized
> peers anyway) but a real dev-loop trap. Candidate fixes for a robustness pass: re-arm a
> generous soTimeout BETWEEN requests (not mid-idle-session — see the CI regression above
> for why a blanket timeout is wrong), or a keepalive/health probe in the accept loop.

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

## External review triage (Gemini/Antigravity, 2026-07-07)

Gemini produced 4 "hardened" file rewrites
(`~/.gemini/antigravity-cli/brain/88968d60-e25f-4b28-985f-506997262921/`). Triage verdict
(Claude, verified by diff against HEAD + `gh api` SHA checks): **nothing new found** — every
valid point was already in this doc/T32 — and 2 of 4 files would break working functionality.

| File | Verdict |
|---|---|
| `hardened_e2e.yml` | Concept = P2 item 7 (already planned). **4 of its 6 pinned action SHAs are fabricated** (don't exist upstream; the setup-java one shares a 21-char prefix with the real v4.7.0 SHA then diverges). Verified-correct SHAs are now baked into T32 item 4 — use those, never a model-supplied SHA unverified. |
| `hardened_HotReloadPlugin.kt` | Concept = P1 item 3 (already planned). Weaker than the T32 item 1 spec (name-based config match, `forEach` not `configureEach`, strips all doc comments). Reference only. |
| `hardened_ResourceSwapper.kt` | **Rejected — breaks resource swap twice**: `chmod 700` on the shell-owned staging dir denies the app-uid `run-as cp`; `adb push` into the pre-created dir nests the payload a level deeper. Its Zip Slip guard already exists in `extractOverlay`. Threat premise moot (app's own public resources; SELinux blocks untrusted_app on shell_data_file). |
| `hardened_PatchReceiver.kt` | **Rejected — dev-only spike, breaks it**: the exported receiver IS the adb-broadcast transport; `cacheDir` reads kill `adb push` delivery and its handshake token is never written by anything → all spike scripts fail. Shipped path (PatchServer) is P0-hardened. Salvage: SECURITY.md scopes `spike/` as dev scaffolding (T32 item 5). |

Related: docs/PLAN.md Phase 4 (robustness hardening — separate track),
tasks/T30-robustness-leftovers.md.
