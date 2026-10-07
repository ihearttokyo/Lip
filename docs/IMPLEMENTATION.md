# Lip implementation contract

## Accepted scope

Native Android dictation for modern Android, with a floating bubble alongside
Gboard; English, Japanese and Mandarin; official ChatGPT OAuth for transcript
cleanup; encrypted internal transcript history; dictionary and cleanup styles.
Deliver a public repository, installable APK, README and GitHub Pages landing
page. No proprietary Wispr code or assets, paid API keys, private ChatGPT
endpoints, cloud audio, automatic sends, or simulated UI navigation.

## Architecture and evidence

Use Android 13's accessibility input connection for cursor insertion while
Gboard stays selected. The service exposes a user-triggered accessibility
overlay and refuses protected fields. Recognition supplies continuous local AudioRecord PCM through a pipe to Android's explicit on-device segmented recognizer. Native support is probed; a provider ending early or ignoring segmentation is an actionable error, not a restart loop. Missing speech models are an actionable error,
never a silent network fallback. Cleanup sends the transcript only to the
public Responses API, with `store:false` and `stream:true`; only a completed
stream is usable. Preserve raw text on failure. Check editor/session/selection
before inserting, and never inject a stale result after focus changes.

Store tokens and history with Android Keystore AES-GCM encryption outside
backup. Request microphone and accessibility permissions separately with
disclosures. History is enabled by the owner's request and can be disabled or
cleared in the app. No analytics or transcript logging.

Deterministic acceptance: normalization preserves Unicode/identifiers and
newlines; cursor replacement handles reversed selections and rejects stale or
protected targets; OAuth rejects mismatched state/client/nonce/identity; SSE
rejects partial failures; history parsing preserves records. Unit tests must
demonstrate RED then GREEN. Build/lint, independent correctness and Ponytail
review, APK signature checks and landing-page desktop/mobile interaction
checks precede publication. Hardware speech/OAuth remain unverified without
an eligible signed-in Android device; do not relabel fixture evidence as live.

Rollback: disable Lip's accessibility service or revert source/site changes. Do not uninstall or clear app data to downgrade: the v0.1 APK cannot read migrated per-record history. Reinstalling the newer version without clearing data preserves access. Never delete history or
credentials during ordinary failure recovery. Release signing material stays
local and out of Git.

## Research provenance

The owner's `Lip Concept.html` export was read locally, not published.
SHA-256: `476f88ddcca67d3d3d175844a7aba7a7090a98e56c0497a87e3947ba01ef9e4d`.
Current official docs confirm its audio/plan boundary. Android 13's newer
accessibility input connection improves on its legacy SET_TEXT fallback.

- [ChatGPT registration](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
- [Accessibility input method](https://developer.android.com/reference/android/accessibilityservice/InputMethod)
- [On-device recognition](https://developer.android.com/reference/android/speech/SpeechRecognizer)

Compile/target API 36 uses the resident SDK; newer Android compatibility is a
device-validation gate, not an unsupported claim. No swipe keyboard is included:
the owner explicitly wants to retain Gboard for this release.

Functional parity, including real-device speech and authenticated cleanup, is
tracked in [PARITY.md](PARITY.md). Artifact delivery alone is not acceptance.
Live cleanup operates on stable segments after disclosed opt-in, never on
every hypothesis; final text is recomputed before insertion. Failed cleanup
keeps raw text and blocks automatic insertion. New-target insertion requires
explicit confirmation before the first commit attempt.

Independent encrypted history records use verified atomic writes; migration
keeps the aggregate until every copy verifies. Page/search decrypt one record
at a time, render 20 rows and preserve full raw/clean text. There is no aggregate
4 MiB limit; linear file scanning remains a measured-performance follow-up.
