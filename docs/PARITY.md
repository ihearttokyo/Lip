# Android functional-parity acceptance

Jared's requested outcome is functional Wispr Flow parity on Android, with
Gboard retained, English/Japanese/Mandarin, local history and official ChatGPT
login. The v0.1.0 artifact delivery did not achieve that outcome. This contract
replaces artifact completion as the acceptance threshold for further work.

## Behavior to prove

The [Wispr Flow Android feature page](https://wisprflow.ai/android), checked
2026-10-07, describes a floating button alongside the existing keyboard,
continuous dictation across apps, punctuation, filler removal, spoken
self-corrections, numbered lists, uncommon terms and multilingual use.

- One explicit start keeps a session listening through pauses and finalized
  recognition segments until the user finishes; no arbitrary one-minute stop.
- The bubble visibly shows microphone state and live transcript, with working
  Finish and Cancel controls even during an app transition.
- Finalized segments and current hypotheses combine without duplication or
  losing English, Japanese or Mandarin text. Cancellation rejects late results.
- Recording can continue across apps. Automatic insertion still requires the
  unchanged original editor. A separate explicit **Insert here** action may
  target the newly selected eligible editor; stale results never rebind silently.
- Polished output removes fillers and accidental repetition, applies explicit
  self-corrections, spoken punctuation and lists, and uses dictionary terms
  without inventing facts, changing numbers or answering dictated instructions.
- History retains raw and cleaned results without an arbitrary whole-history
  file ceiling; deletion, search and disabled retention remain effective.
- Cleanup failure preserves the transcript and makes recovery visible; it must
  not silently present an unpolished result as successful polished dictation.
- Gboard, password/protected-field refusal, token encryption, local-only audio,
  transcript-only cloud requests and user-controlled permissions remain intact.

## Evidence and authority

Use failing assertions followed by unchanged passing assertions for segment
handling, session lifetime, cancellation, rebinding and history boundaries.
Exercise the real native insertion path in the disposable emulator. Live
speech and authenticated ChatGPT cleanup must be tested on an eligible Android
phone before calling functional parity complete. Engine extras, prompt text,
mocked model output and screenshots alone do not establish that result.

Official [ChatGPT-plan preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
exclude audio/transcription. That constrains the backend, not the target
behavior: local Android or correctly licensed open-source ASR must supply
speech. Do not use private ChatGPT endpoints, cookies or unapproved paid API
keys. Provider-dependent native features require a capability probe and real
device evidence; add a replacement engine only if that evidence requires it.

The existing public-repo/APK/site publication authorization continues. No
new API spend, cloud sync, proprietary code/assets or global permission change
is authorized. Preserve v0.1.0 as the rollback point. Current live blocker:
no physical Android phone is connected for microphone/OAuth acceptance.
