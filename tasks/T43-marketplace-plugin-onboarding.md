# T43: Document the published JetBrains Marketplace plugin

Status: DONE
Assignee: unassigned
Recommended model: lower-cost GPT/Flash-tier model for documentation and metadata; coordinator reviews and verifies

## Problem

The IntelliJ / Android Studio plugin is published on JetBrains Marketplace, but a new user cannot
reliably discover that from the root README or learn the supported setup path from the Marketplace
listing itself. The plugin descriptor currently gives only a short technical summary. That leaves
the Marketplace page without the install, configuration, and first-success guidance needed to use
the bundled CLI safely.

## Goal

Make the Marketplace listing and repository documentation a coherent onboarding surface for the
published IDE plugin. A user arriving from either place must be able to install the plugin, find
its settings, use the stable configured-profile workflow, recognize a successful reload, and know
where to get help without needing a source checkout.

## Progress — 2026-07-23

- Before the 0.2.0 update, public API inspection verified plugin XML ID `dev.hotreload.ide`,
  Marketplace ID `32850`, direct page
  `https://plugins.jetbrains.com/plugin/32850-compose-hot-reload`, and the former 0.1.8 listing.
- The 0.2.0 plugin build carries the Marketplace-safe onboarding description now published with
  the matching GitHub/JitPack release.
- Marketplace update `1114979` for 0.2.0 was approved. The official Marketplace download reports
  the reviewed 0.2.0 descriptor and is byte-identical to the installed plugin JAR.
- Local ZIP smoke PASS: rebuilt `hotreload-intellij-plugin-0.2.0.zip`, inspected its generated
  descriptor/description, and installed it into Android Studio. Against a matching prepared,
  configured multi-module target, the bundled CLI reached Ready, completed a visible app-body edit
  and reversion without a process restart, then Stop removed the watcher. The temporary target
  wiring, profile, baseline, and emulator app were restored/removed afterward.
- Usability finding: the settings page's default watched-modules value (`app`) is an explicit
  override, so selecting a multi-module CLI profile alone can mismatch a baseline prepared for a
  wider module list. For this smoke, pinning the exact prepared module mappings in the UI resolved
  the mismatch without `--ignore-fingerprint`. This is a follow-up UX concern; it does not replace
  the required Marketplace-artifact smoke.
- **Marketplace-artifact smoke PASS:** an isolated public multi-module target resolved the 0.2.0
  JitPack Gradle plugin, prepared and passed Doctor with the Marketplace-bundled CLI, reached
  watcher readiness, visibly applied and reverted a Compose body edit on one stable app process,
  and stopped cleanly. The target clone and temporary profile were isolated from the maintainer's
  working project.

## Release dependency

T43's Marketplace upload followed T41's clean-clone, production configured smoke, PR merge,
release tag/GitHub Release, and artifact validation. The approved 0.2.0 Marketplace archive and
the required Marketplace-artifact smoke are verified; the local ZIP result is not used as a
substitute for that evidence.

During the authorized 0.2.0 release, complete T43 in this order:

1. Build, sign, and upload the reviewed 0.2.0 plugin ZIP containing this description. **Done.**
2. Marketplace approved update `1114979`; the public 0.2.0 archive and descriptor were verified.
3. Install the Marketplace artifact and run the configured Start → Ready → visible edit/revert →
   Stop smoke. **Done.**
4. Replace every temporary “published 0.1.8” / “0.2.0 under validation” statement in the README,
   IDE-plugin README, IDE settings guide, project configuration guide, Marketplace change notes,
   task progress, and roadmap with the actual 0.2.0 publication/provenance information. **Done.**

## Scope

1. **Marketplace listing metadata**
   - Replace the terse plugin description configured by `intellij-plugin/build.gradle.kts` with
     Marketplace-safe HTML that explains what the plugin does and does not do.
   - Include: supported IDE context (Android Studio / IntelliJ), bundled CLI, Settings location,
     Refresh discovery as a suggestion, reviewed explicit profile as the stable path, matching
     Doctor/prepare before Start, status-bar Start/Stop, one watcher/device, and the expected
     `Ready` / hot-swap completion signal.
   - Link to the public README, `docs/ide-plugin-settings.md`, and the public AI setup guide using
     stable public URLs only. Verify the final Marketplace URL/slug from the published plugin;
     do not guess or add a dead link.
   - Keep the description concise enough for Marketplace presentation. Do not expose maintainer
     paths, internal scripts, credentials, private handoff material, or unsupported guarantees.

2. **Repository documentation**
   - In README's IDE-plugin section, add an explicit link to the verified Marketplace page and
     a compact install-and-first-use path. Preserve the signed ZIP/GitHub Release option.
   - Update `intellij-plugin/README.md` with a prominent Marketplace install path and a short
     post-install checklist. It must state that the Marketplace build includes the CLI and that
     the stable workflow is configured mode with a reviewed profile and matching prepare.
   - Update `docs/ide-plugin-settings.md` only where needed to keep the UI labels, tier wording,
     and links consistent. Do not duplicate the whole README.

3. **Release handling**
   - Marketplace listing changes ship only in a newly built, signed, version-bumped plugin update.
     Do not mutate the current published listing out-of-band or claim it has changed before the
     upload is approved.
   - Coordinate the version and change notes with the next authorized release. T43 must not block
     T41's existing local validation gates; it may be bundled only if the maintainer authorizes it.

## Out of scope

- New IDE-plugin features, settings-schema changes, watcher behavior changes, or protocol changes.
- Reworking the main CLI/Gradle-plugin onboarding contract established by T41.
- Publishing, signing, uploading, tagging, or changing Marketplace state without explicit
  maintainer authorization.

## Acceptance

- [x] `README.md` links to the verified published JetBrains Marketplace plugin page and explains
      the install + stable first-use path without requiring a repository clone.
- [x] The generated plugin descriptor/Marketplace description provides the same essential
      onboarding: bundled CLI, Settings location, configured profile, matching prepare, Start/Stop,
      and safe boundaries.
- [x] `intellij-plugin/README.md` and `docs/ide-plugin-settings.md` agree with the README and use
      only public links/instructions.
- [x] A focused deterministic docs check (new or extended) asserts the public Marketplace link and
      required IDE onboarding concepts, so a future release cannot silently remove them.
- [x] `cd intellij-plugin && unset JAVA_HOME && source ../scripts/env.sh && ./gradlew test buildPlugin verifyPlugin`
      passes against the pinned IDE set, with no new internal-API findings.
- [x] Before any authorized upload: inspect the built ZIP's `META-INF/plugin.xml` and confirm its
      description and version match the reviewed release material. After approval: install the
      actual Marketplace artifact and complete the documented minimal configured Start → Ready →
      Stop smoke.

## Likely files

- `README.md`
- `intellij-plugin/build.gradle.kts`
- `intellij-plugin/src/main/resources/META-INF/plugin.xml` (keep descriptor text consistent if it
  remains user-facing)
- `intellij-plugin/README.md`
- `docs/ide-plugin-settings.md`
- `e2e/check-docs-contract.sh` or a focused sibling contract check

## Notes

- The root README and plugin README provide the verified direct Marketplace link and a
  Marketplace-quality getting-started flow.
- Marketplace compatibility is a standing gate: introduce no `@ApiStatus.Internal` APIs; run
  `verifyPlugin` against every pinned IDE before release.
