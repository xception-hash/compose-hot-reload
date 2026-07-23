# T43: Document the published JetBrains Marketplace plugin

Status: IN PROGRESS
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

- Verified the published plugin through JetBrains Marketplace's public API: plugin XML ID
  `dev.hotreload.ide`, Marketplace ID `32850`, direct page
  `https://plugins.jetbrains.com/plugin/32850-compose-hot-reload`, and currently published
  version 0.1.8. Its current description is only the former two-sentence summary.
- Started the source/documentation half. The next plugin build now carries a full Marketplace-safe
  onboarding description, and public repository documentation links to the published 0.1.8 page
  while accurately describing 0.2.0 as under release validation.
- The Marketplace page itself cannot change until a maintainer-authorized signed plugin upload.
  After that upload, verify the rendered description and replace the temporary published-version
  wording in these docs with the actual released version.

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
- [ ] Before any authorized upload: inspect the built ZIP's `META-INF/plugin.xml` and confirm its
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

- The root README and plugin README currently say that the Marketplace plugin exists, but neither
  gives users a verified direct Marketplace link or a Marketplace-quality getting-started flow.
- Marketplace compatibility is a standing gate: introduce no `@ApiStatus.Internal` APIs; run
  `verifyPlugin` against every pinned IDE before release.
