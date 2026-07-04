# T13: Local sparse checkout of AOSP tools/base deploy/ (reference source)
Status: TODO
Assignee: Jay (network-heavy; one interactive run) or agy -i

## Goal
The resource-edits implementation (and any future agent work) keeps referencing AOSP deployer
sources (`ResourceOverlays.java`, `overlay_swap.cc`, `InstrumentationHooks.java` — see
docs/resource-edits-notes.md §2). Give future sessions a local, pinned, greppable copy so they
never burn tokens on web fetches. No code in this repo changes.

## Spec
Write `scripts/fetch-aosp-deployer.sh` (source `scripts/env.sh` like the others) that:
1. Sparse-clones `https://android.googlesource.com/platform/tools/base` into
   `third_party/tools-base` (gitignored):
   `git clone --depth 1 --filter=blob:none --sparse <url> third_party/tools-base`
   then `git -C third_party/tools-base sparse-checkout set deploy`.
2. Is idempotent: if the dir exists, print the pinned commit and exit 0 (no re-fetch).
3. After first fetch, records the resolved commit hash into the script itself is NOT needed —
   instead append it to `third_party/PINNED.txt` (`tools-base <hash> <date>`), committed to git.
4. Add `third_party/tools-base/` to `.gitignore` (PINNED.txt stays tracked).

## Out of scope
No reading/summarizing the sources (T11 already did); no repo code changes.

## Acceptance
1. `scripts/fetch-aosp-deployer.sh` runs clean twice (second run = no-op).
2. `test -f third_party/tools-base/deploy/agent/runtime/src/main/java/com/android/tools/deploy/instrument/ResourceOverlays.java`
3. `git status` clean except the new script, `.gitignore`, `third_party/PINNED.txt`, this spec's status.
