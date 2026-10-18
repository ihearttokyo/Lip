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
overlay and refuses protected fields. In development source, AudioRecord supplies
16 kHz mono PCM to the bundled CPU-only whisper.cpp engine. A downloaded,
verified multilingual model supplies English, Japanese and Mandarin; Android's
speech-recognition service and vendor language models are no longer required.
Missing or invalid model files are actionable errors, never a silent cloud
fallback. The published v0.2.0 APK predates this engine and still depends on
Android's on-device external-audio segmented recognizer. Cleanup sends the
transcript, style instructions and applicable dictionary terms to the
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
checks precede publication. The [evidence matrix](PARITY.md#current-evidence) separates actual recorded-human
decoding and file-fed Android JNI timing from deterministic tests, microphone
routing, physical-phone behavior and authenticated cleanup. Do not relabel
fixtures or a correct single decode as functional parity.

Rollback: disable Lip's accessibility service or revert source/site changes. Do not uninstall or clear app data to downgrade: the v0.1 APK cannot read migrated per-record history. Reinstalling the newer version without clearing data preserves access. Never delete history or
credentials during ordinary failure recovery. Release signing material stays
local and out of Git.

## Local speech backend

- Engine: [whisper.cpp 1.9.2](https://github.com/ggml-org/whisper.cpp/tree/306c88f4d1286aec1bf96e544632897886af5501), pinned submodule commit `306c88f4d1286aec1bf96e544632897886af5501`, with vendored GGML. The build applies a [hash-bound cancellation patch](../app/src/main/cpp/patches/whisper-scheduler-abort.json) to a generated source copy, leaving the submodule pristine. It forwards abort callbacks into scheduled CPU backends; model, decoding and thread policy are unchanged.
- Model: `ggml-large-v3-turbo-q5_0.bin`, [publisher revision 5359861c](https://huggingface.co/ggerganov/whisper.cpp/blob/5359861c739e955e79d9a303bcbc70fb988958b1/README.md), exactly 574,041,195 bytes. SHA-256: `394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2`.
- Installation: user-requested anonymous HTTPS download, bounded transfer, checksum and disk-readback verification, then same-directory atomic publication. Failure/cancellation preserves an existing verified model; no automatic retry or cloud audio fallback.
- Capture: microphone PCM remains transient and local. Worker-side inference coalesces bounded windows; Finish drains accepted PCM, while Cancel retires a capture-owned engine lease before aborting native work. Delayed work cannot reset abort after that lease is retired, and a stale capture cannot cancel a new one. New leases require the previous inference to have returned. Late callback fences remain separate. The selected language and local dictionary prompt condition transcription, not translation. A dictionary prompt does not retrain the model or guarantee spelling.
- Native inference: greedy decoding, CPU only, at most four threads, independent-window text context, no transcript/native diagnostic logging. Android builds target arm64-v8a and x86_64.

The downloadable v0.2.0 APK does not include these native components or this
model installer. Development performance and speech accuracy remain limited;
measured results and outstanding acceptance gates belong only in
[PARITY.md](PARITY.md#current-evidence). The host-side Silero VAD experiment is
observation-only: it is not a shipped speech-cropping or silence-discard policy.
See [NOTICE.md](../NOTICE.md) for engine and model licenses.

## Primary references

The official ChatGPT-plan preview excludes audio transcription; the local
engine supplies speech. Android's accessibility input connection avoids the
legacy full-field SET_TEXT fallback.

- [ChatGPT registration](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
- [Accessibility input method](https://developer.android.com/reference/android/accessibilityservice/InputMethod)
- [AudioRecord capture](https://developer.android.com/reference/android/media/AudioRecord)
- [Whisper source and model weights](https://github.com/openai/whisper)

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

## Model-free cloud cleanup check

Production cleanup reuses the current account generation's previously offered
model choices in memory. The model picker still refreshes the catalog; a new
account generation or model rejection requires fresh choices. Empty catalogs
remain recoverable. Consent, plan permission, operation cancellation and completed
SSE checks still govern every cleanup; failed requests preserve raw text.

After Finish and pipe EOF, the local engine may reuse its last completed decode
only for the same active capture lease, sample range, copied PCM, language and
dictionary prompt. Changed input takes the normal decode path. Cancellation,
failure and close discard the retained result. This retains at most one extra
30-second PCM snapshot (1,920,000 bytes); phone latency and memory remain runtime
qualification gates. Final timestamp validation and delivery fences are unchanged.

The test-only `LipCloudCleanupRunner` uses the production ChatGPT client with
three fixed public English, Japanese and Mandarin text samples. It needs an
existing eligible guest login and Lip's normal text-cleanup consent. The login
bridge alone does not enable that consent. The runner never signs in, changes
settings, reads history or dictionary, inserts text, records audio, or loads a
speech model. Normal token refresh may occur through the production client.

On a parent-verified isolated emulator, with a matching app/test APK pair:

```sh
adb -s emulator-5558 shell am instrument -w -r -e isolated_emulator true \
  dev.lip.android.test/dev.lip.LipCloudCleanupRunner
```

The bounded `lip_cloud_cleanup` report includes the selected account-visible
model, completed public-text outputs, per-request elapsed time, production
output-safety checks and sentence-ending punctuation. Revoked consent discards
an in-flight result and stops further calls; it cannot recall a request already
started. Partial public results survive a later failure. Unit tests use a real
loopback HTTP server with synthetic credentials and responses, not OpenAI.
Neither those fixtures nor a successful live probe prove speech recognition,
physical-phone latency, formatting quality beyond these samples, or parity.

## Hosted final-window JNI probe

The existing isolated hosted collector accepts only `cancel_active` (its unchanged
default) or `final_reuse`. A public push with `[android-final-reuse]` selects the
latter; ordinary pushes and pull requests do not start a guest. Both modes keep
the pinned SDK, model, public PCM, two-CPU guest and teardown/timeout guards.

`final_reuse` decodes the nonzero public fixture with the packaged JNI engine,
then requests the identical final window from the same lease. Segment object
identity proves the second request did not decode again; separate timings are
reported. Changed zero PCM must not return old speech, and cancellation must
retire the lease. This is a file-fed Android check, not microphone, real-user
latency, language-quality, ChatGPT login, or functional-parity acceptance.
