# Security policy

## Supported versions

| Version | Supported |
|---------|-----------|
| 0.1.x   | ✅ (current) |

## Threat model

Compose Hot Reload is a **debug-only developer tool** that, by design, lets the developer's
machine inject and execute arbitrary code in the developer's own debug app. "The developer's
machine can run code in the developer's debug app" is the product, not a vulnerability.
Authentication of the caller — not validation of the injected bytecode — is the control.

The boundaries we actually defend are:

1. **Other apps on the same device** must not be able to reach the on-device injection
   surface. The runtime's `PatchServer` gates every connection on a peer-credential (uid)
   allowlist before reading a single frame.
2. **Release / non-debuggable builds** must never expose the injection surface, even if the
   runtime dependency is misconfigured onto a release classpath. The runtime bails unless the
   app is debuggable, the Gradle plugin wires it as `debugImplementation` only, and a
   configuration-time tripwire fails the build if `runtime-client` lands on a non-debuggable
   variant's runtime classpath.
3. **The artifacts we ship** (the runtime AAR, the committed `interp.dex`, the plugin) must be
   what the source says they are. A CI job rebuilds `interp.dex` from its pinned inputs and
   compares it against the committed binary.

Explicitly **not** threats we defend against: the contents of an injected payload (arbitrary
code is the feature), the engine running the developer's own Gradle build, and anyone who
already has `adb` access to the device (they can inject via `adb` regardless of this tool).

For the full model, trust-boundary table, and the hardening history, see
[`docs/security-hardening.md`](docs/security-hardening.md).

## Development-only scaffolding

Everything under `spike/` — including the toy app and its exported `PatchReceiver` — is
development-only scaffolding: it is never shipped, and its toy app must never be installed on a
non-development device.

## Reporting a vulnerability

Please report security issues privately through GitHub's private vulnerability reporting:
open the repository's **Security** tab and choose **"Report a vulnerability"**
(<https://github.com/xception-hash/compose-hot-reload/security/advisories/new>). Do not open a
public issue for a suspected vulnerability.
