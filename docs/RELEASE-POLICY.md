# Future Lip release policy — 2026-10-28

Direct user instruction supersedes the previous all-in-one delivery requirement.

## Small installer, separately downloaded models
- Do not bundle Clean or Pro weights in future APKs or macOS app installers.
- Offer both Clean and Pro as explicit in-app downloads; show size before consent.
- Do not automatically download either model during install/start/update.
- Retain installed verified models across app updates; downloading one must not require downloading the other. Keep pinned hash verification and safe cancellation/publication.
- The Gradle bundling switch now fails closed. Already published Preview3 and older rollback artifacts/URLs remain unchanged.
- Measure actual installer sizes before publishing; no unmeasured size guarantee.

## Version identity: follow NULL CHOIR — Main
Reference: NULL CHOIR docs/MAJOR-UPGRADE-PLAN-2026-10-26.md, “One release identity”, and its docs/build-iterations.json approach (read-only reference; no other-chat edits).
- New releases named Lip v0.xxx, no Alpha/Major Features/Preview suffixes.
- One authoritative production-iteration ledger drives app display, Android/macOS metadata, artifact names, release notes and marketing/download page.
- Audit Lip production-source history to establish the initial ordinal honestly BEFORE issuing the next identity. Do not copy NULL CHOIR’s ordinal, invent a lifetime count, or equate existing preview number with production count.
- New distinct production-source iterations consume ordinals; failed issued ordinals stay used. Test-only changes, PRs, duplicate tags and source-identical packaging retries do not inflate it.
- Preserve existing release names/URLs/rollback, bundle/application IDs, user data and downloaded models.
- Keep Android versionCode strictly above published 50003; map monotonically from the ledger rather than reducing it to the displayed ordinal. macOS build metadata must remain valid/monotonic.

Implemented in the current source candidate: the history audit and authoritative ledger/tooling reserve **Lip v0.010** (Android versionCode50007, macOS build14), without claiming a lifetime iteration count. See [RELEASE-IDENTITY.md](RELEASE-IDENTITY.md) for allocation, metadata enforcement and post-commit source/artifact binding. This is reserved/unreleased source, not a new download. Small-installer signing, packaging, update and model-download end-to-end qualification remain separate gates. Published50003 is unchanged.

## Updated target hardware and delivery priorities
- Future Android builds: Android 16/API36+, ARM64 only. No x86_64 release artifacts.
- Future Mac builds: macOS26+, Apple Silicon only. Do not mutate installed apps or physical phones without retained authorization.
- GPU/NPU (including supported device TPUs) efficient automatic routing is a high-priority feature/release target, not a user-facing backend choice. Actual device/model compatibility must be proved; an OS/architecture filter alone does not qualify acceleration or justify a no-issues claim. Source PR18 now compiles Vulkan with CPU safety fallback and automatic provenance-gated selection; this is not physical GPU qualification. CPU-only behavior is not accepted as the accelerator target.
- Plan new work approximately90% toward features/rapid iteration and10% toward fixes. This is a prioritization target, not measured historical token allocation or permission to omit security, privacy or necessary release checks. Freeze accepted components; avoid redundant polishing.
- Previously published50003 retains its original broader compatibility and bundled models. Do not relabel the existing binary as satisfying these new requirements.

Primary Android device confirmed by Jared: Xiaomi15Ultra, targeting its Snapdragon8Elite AdrenoGPU and HexagonNPU (not GoogleTPU). Hardware/model acceleration is an implementation priority; actual installed OS and runtime compatibility still require permitted device evidence. No CPU-only build is to be labelled GPU/NPU-qualified.

Source reconciliation PRs17/18 are merged at `13d87e4439d38811a4c82a8bab747f1d7443d88c`. Source merge, reserved version identity, physical qualification and publication are separate states; the full independent9.2+ release gate remains open.
