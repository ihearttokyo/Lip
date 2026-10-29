# Source delivery and artifact identity

Source reconciliation PRs **17 and 18 are merged**, at main commit
`13d87e4439d38811a4c82a8bab747f1d7443d88c`. They include the local-first Android
IME/Bark and macOS implementation plus Android 16+/ARM64 automatic Vulkan routing.
This is no longer a pending source-reconciliation PR.

The current **Lip v0.015** version-wiring candidate is reserved source, **not a
published release**. Its audited allocation and cross-platform metadata rules
are in [RELEASE-IDENTITY.md](RELEASE-IDENTITY.md) and the authoritative
`build-iterations.json`. The history audit/tooling is implemented; a reserved
identity is not proof of a signed artifact or completed release qualification.

## What the repository preserves

Android application/IME/native code, instrumentation and unit tests, desktop
Swift sources/configuration, generation/evaluation tools, notices and required
small runtime keyboard data are included. No speech weights, personal histories,
signing credentials, local build caches or validation recordings are included.
A historical instrumentation WAV remains withheld pending origin evidence; see
`app/src/androidTest/assets/README.md`. Replaying that historical audio test from
a clean checkout requires a separately provenanced input.

Starling and its patched GGML dependency are a minimal vendored source snapshot,
with original licenses, upstream commits and per-file size/SHA manifest, including
checked lifecycle/model/device-policy fixes. CMake verifies it read-only rather
than modifying Git patch/index state. The verifier requires a POSIX build host
and fails closed on unsupported hosts. This checks drift, not authenticity of
arbitrary replacement source/manifest pairs. Upstream submodule mode remains
available when no snapshot manifest exists; optional legacy Whisper retains its
pinned submodule. The Vulkan header closure is separately pinned and verified.

Missing historical research and coupled helpers are preserved inertly under
[`research/`](../research/README.md), with exact per-PR/head/blob/SHA identities.
Superseded production is referenced, not restored. This is source-history
preservation, not model adoption, active legacy workflows or permission to run
old research/phone/cloud jobs.

## Published download versus current source

The public download remains **build 50003**,
`0.5.0-preview.3-offline`, with its original URL, bundled weights and compatibility.
The post-release merged source is **not established as an exact reproducible source
match for that historical APK**. GitHub's automatic source archive is the selected
repository revision, not a retroactive source/artifact attestation. No new APK or
Mac installer is produced or published merely by merging source or version wiring.
Future installers must omit speech weights and use separate explicit downloads.

## Evidence and remaining gates

Recorded aggregate/native build checks are scoped to their exact candidates; see
[Android GPU build evidence](ANDROID-GPU-FIRST-2026-10-29.md). They do not prove
physical Adreno inference, accelerator efficiency or the reserved v0.015 package.
Full Gboard parity, measured swipe superiority, physical-phone/editor/microphone
acceptance, macOS intended-use verification, Hexagon qualification, real
ChatGPT/Claude sending, signed small-installer/update tests and the independent
full 9.2 release gate remain open. Third-party language-data qualifications stay
in their notices; source publication does not clear them. No Tencent/WeChat
artwork is included.

## Local sticker source increment

[The ordinary-build workflow](LOCAL-STICKER-WORKFLOW.md) adds explicit local ZIP
import, original export with same-FD readback, recoverable Remove/Restore and
receiver-bound PNG/WebP requests. Scope-specific Android emulator controls and
independent review pass; the remaining race/editor/accessibility/physical and
full9.2 gates remain open. Current runtime receipts precede version-only metadata
reservation; they are not a signed v0.015 release attestation.

## Toolbar source increment

[Toolbar customization](TOOLBAR-CUSTOMIZATION-RESULT.md) adds ordered middle slots,
inert preview, Save/Reset and guarded dispatch. Actual settings/order/consumer/
reset/stale-save controls and independent bounded reviews pass. Layout, a11y,
physical/gesture/failure matrices and full9.2 remain open. Runtime receipts retain
their earlier version metadata; current reservation is not a release attestation.
