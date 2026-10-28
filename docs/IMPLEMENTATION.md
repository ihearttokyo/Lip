# Current Lip implementation contract

This replaces the original Android 13/cloud-OAuth/bubble-only contract. Source
PRs 17 and 18 are merged at `13d87e4439d38811a4c82a8bab747f1d7443d88c`.
The next **Lip v0.013** identity is reserved source, not a published installer;
see [release identity](RELEASE-IDENTITY.md). Historical validation/research receipts
describe their original candidates, not automatic acceptance of current main.

## Product and platforms

- Local-first English dictation using Bark 3.0 Clean (standard) or Pro (optional).
  Local speech requires no OpenAI login or cloud inference. Japanese/Mandarin
  speech is not currently supported; existing multilingual history is preserved.
- Android includes its own IME: dark rounded keys, typing/swipe candidates, emoji,
  a small local GIF catalog and opt-in clipboard history. It is not a patched
  Gboard, does not require retaining Gboard as the selected keyboard, and is not
  yet accepted as full Gboard parity or superior swipe accuracy.
- New Android builds require **Android 16/API 36+ and ARM64**. No x86_64 release.
  The historical build 50003 retains its original Android 13+/two-ABI contract.
- macOS source targets **macOS 26+ and Apple Silicon**. Fn hold/release supports
  push-to-talk; double-tap latches recording and a tap ends it. There is no Mac
  keyboard replacement or matching public Mac installer on the download page.

## Recognition, placement and model lifetime

Android production builds compile the vendored Starling/GGML Bark backend with
Vulkan and a CPU safety backend. Checked sessions request automatic selection:
raw physical-device/driver provenance must qualify before accelerator creation.
Software/virtual/unknown candidates are not treated as hardware qualification.
The intended first physical target is Xiaomi 15 Ultra's Snapdragon 8 Elite Adreno.
Its actual installed OS, driver, GPU inference and efficiency remain unqualified.
Hexagon NPU requires a separate compatible model/runtime route; the Pixel TPU
`STARLING_FAST` route remains off. There is no backend chooser or CPU-free claim.

Fixed Clean/Pro profiles use verified, sealed model snapshots before checked FD
loading. Cancellation, admission and retirement govern native ownership; uncertain
work is retained/fenced rather than freed speculatively. A fresh CPU retry is
limited to one eligible non-CPU native failure after confirmed retirement, never
an unknown or canceled operation. See [GPU implementation and build evidence](ANDROID-GPU-FIRST-2026-10-29.md).

macOS uses Core ML `.all`: system placement may use Neural Engine, GPU and CPU per
supported operation. This is not a promise of Neural-Engine-only execution or
measured optimal placement. No user-facing accelerator setting is required.

Future installers omit speech weights. Clean and Pro are separate explicit
in-app downloads with size disclosure, pinned integrity checks and safe
publication/cancellation. No installation/start/update auto-download. Preserve
already verified models across updates; one choice must not require the other.

## Text, permissions and privacy

Preserve native transcript output and raw recovery text. Android's optional local
formatting recognizes explicit format commands; it is not a cloud rewrite or a
guarantee of filler removal, punctuation, semantic equivalence or perfect numbers.
Changed local formatting stays for review rather than automatic insertion.

IME/accessibility insertion must validate the editor, session, selection and
protected-field state; a stale/unverified target must not receive text. macOS
insertion uses separately granted Accessibility access and must not replace the
clipboard or press Send/Enter. If insertion cannot be verified, keep text for
review. Microphone, keyboard and optional accessibility access require their own
disclosures/grants; no validation step silently changes the default keyboard.

Transcript history and clipboard retention are opt-in, with current consent and
protected-context checks. Preserve data on ordinary recovery; never recommend
uninstalling/clearing app data as a routine fix. Do not publish histories,
credentials, signing material, model weights or private recordings. No unrestricted
typing archive or cloud history sync is enabled.

Mac source adds an optional Option+Fn finish-only intent and desired provider/effort defaults, retaining local output in Review and excluding cursor insertion for that intent. No actual provider route is admitted. See [source and scoped CLI evidence](MAC-AGENT-SHORTCUT.md).

Android source also adds a tap-only [Text editing panel](TEXT-EDITING-PANEL.md). Eight native editor groups pass, but the complete driver remains FAIL due to unresolved selected-password caret noninterference and incognito setup. This is partial source, not privacy/full-feature acceptance.

Agent shortcut/effort UI does not establish provider capability. Direct/background
ChatGPT/Claude delivery remains unverified and disabled without an admitted route;
no simulated send, hidden endpoint, paid fallback or draft-sharing substitute.
Translation, including a future optional ChatGPT backend, remains future work.

## Acceptance and delivery boundaries

Source/build/synthetic/emulator/physical evidence are distinct. The recorded
ARM64/API36 CPU+Vulkan build and aggregate checks are scoped implementation
evidence, not GPU execution or acceptance of the reserved v0.013 package.
Still open: physical microphone/editor matrix, matched GPU/CPU quality/latency/
memory/power, Hexagon support, macOS intended-use verification, full Gboard parity,
measured swipe superiority, real provider delivery, signed small-installer/update
qualification and the independent full **9.2+** product/release gate.

Build/test/review, signing, source/artifact binding and publication remain separate
gates. Keep historical downloads and rollback identities unchanged. Disable an
optional service or preserve a prior installer for recovery; do not delete user
history/models/credentials or overwrite an installed app as a rollback shortcut.
