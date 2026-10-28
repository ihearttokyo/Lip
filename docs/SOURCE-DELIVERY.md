# Development-source publication

This source PR reconciles the previously unpublished Android and macOS work. It
is not a claim that every feature is complete or that this post-release source
exactly reproduces the historical build 50003 APK. In particular, future speech
weight bundling is now prohibited; the new version ledger is still pending.

Android application/IME/native code, instrumentation and unit tests, desktop
Swift sources/configuration, generation/evaluation tools, notices and required
small runtime keyboard data are included. No speech model weights, personal
histories, signing credentials, local build caches or validation recordings are
included. A historical instrumentation WAV is withheld pending origin evidence;
see app/src/androidTest/assets/README.md. This prevents replaying that historical
audio test from a clean checkout until a properly provenanced input is supplied.

Starling and its already-patched GGML native dependency are vendored as a minimal
source snapshot, with original licenses, base upstream commits and a per-file
size/SHA manifest. This includes local lifecycle/model/device-policy fixes that
were previously stranded inside dirty submodules. CMake verifies the snapshot
read-only rather than running Git patch/index operations in the enclosing Lip
checkout. Verification requires a POSIX build host; unsupported hosts fail closed.
This protects integrity against drift, not authenticity of arbitrary manifest and
source pairs. The ordinary upstream submodule mode is retained when no snapshot
manifest exists. Optional legacy Whisper still uses its pinned submodule.

Prior local evidence covers individual features; clean-checkout build/test and
review of the reconciled aggregate remain separate merge gates. Full Gboard
parity, measured swipe superiority, physical-phone/editor/microphone acceptance,
macOS user verification, accelerator qualification, real ChatGPT/Claude sending
and the full independent 9.2 release gate remain open. Third-party language-data
provenance qualifications are preserved in their notices, not declared cleared
by publication. No Tencent/WeChat artwork is included.
