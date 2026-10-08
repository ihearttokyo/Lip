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
keys. The selected bundled engine requires verified source/model pins and
actual accuracy, capture-routing and latency evidence. Removing the Android
recognition-provider dependency does not waive real-device acceptance.

The existing public-repo/APK/site publication authorization continues. No
new API spend, cloud sync, proprietary code/assets or global permission change
is authorized. Preserve v0.1.0 as the rollback point. Physical-phone microphone, routing and latency acceptance remain unverified.
No phone is currently connected. Build validation now runs on isolated
GitHub-hosted Linux runners. Hosted Android runtime diagnostics remain gated;
successful compilation does not close the runtime lane. Authenticated cleanup
still requires the user's OpenAI sign-in and authorization.

## Current evidence

As of October 9, 2026, the native engine is development work, not part of the
published v0.2.0 APK. This table is the authoritative summary of measured
speech evidence. Functional parity remains **unverified**; existing accuracy
and latency failures are not waived by successful packaging or unit tests.

| Lane | Actual evidence | Limit or remaining gate |
| --- | --- | --- |
| Hosted build and current test runner | At source `186a6cb`, [push](https://github.com/ihearttokyo/Lip/actions/runs/37798699163) and [PR](https://github.com/ihearttokyo/Lip/actions/runs/37798706308) checks passed unit tests, lint, ARM/x86 native compilation, main/test APK builds and landing-page checks. Both APKs were retrieved; ZIP/DEX inspection confirms both native ABIs and the compiled `cancel_active` runner. | Build and static artifact evidence only. Actual active-cancellation RED/GREEN, microphone, phone and authenticated cleanup remain open. The prepared vendor callback patch is inactive; no new release or Pages deployment. |
| Hosted cancellation preflight | [First probe](https://github.com/ihearttokyo/Lip/actions/runs/37805583257) refused KVM access before inference. The [same-UID/group retry](https://github.com/ihearttokyo/Lip/actions/runs/37809462334) verified the device as `root:kvm`, mode `660`, then stopped because sudo required a password. Normal build jobs passed at both heads. | No guest or model inference ran; neither failure is cancellation RED. A scoped root bootstrap or connected physical phone is pending owner input. Mac heavy tests remain held and the vendor patch remains inactive. |
| Published artifact | v0.2.0 targets `cf311b38da3dcf6983008f6236e29eb255b7db7b`; that source has no bundled whisper.cpp/JNI engine or model installer. | The download still depends on Android's on-device segmented recognition provider. Development results below do not describe that APK. |
| Recorded-human host ASR | Frozen FLEURS subset: 18 read-speech recordings, six each in English, Japanese and Mandarin. Scorer v2 applies the pre-run 5% per-clip error ceiling and required fact anchors. Base Q5_1 passed 2/18; small Q5_1 passed 5/18; large-v3-turbo Q5_0 passed 15/18. | Three turbo cases still fail on uncommon names/terms (`fleurs-en-003`, `fleurs-zh-020`, `fleurs-zh-034`). A pass is not perfect transcription. This small read-speech subset does not establish spontaneous dictation, spoken correction, or formatting quality. |
| Host aggregate errors | Turbo beam-5: English WER 6/109 = 5.50%; Japanese CER 2/207 = 0.97%; Mandarin CER 12/187 = 6.42%. Greedy/best-of-5 also passed 15/18: English/Japanese totals unchanged, Mandarin CER 13/187 = 6.95%. | These are pooled errors/reference units, not average per-clip rates. JNI uses greedy decoding; do not substitute beam results for Android evidence. References, anchors and thresholds were unchanged; scorer v2 only fixes narrative whitespace matching and preserves the original receipts. |
| Rejected SenseVoice candidate | SenseVoiceSmall int8 host evaluation completed: 8/18 human cases and 4/6 controls passed. English silence/noise both invented “the”. Maximum sampled single-process RSS was 596,115,456 bytes. Its initial English cold-CLI canary took 0.516 seconds, including observation overhead. | Rejected for quality despite the fast canary; not an Android backend or production promotion. The initial Chinese anchor labelled “negation” failed because `无法长途跋涉` became `无法长途跋射` (misspelled verb), not proven loss of negation. Sampled RSS is not a kernel allocation ceiling, and cold host CLI timing is not warm phone inference. |
| Private Qwen conversion | Third-party Qwen3-ASR-0.6B int8 conversion: 878,702,423-byte Sherpa repack, SHA-256 `393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96`. Six selected model/tokenizer files match pinned publisher hashes; the ModelScope conversion declares Apache-2.0. Shared waitid preflight is repaired; 15 Qwen pure tests passed on explicit Python 3.14.6. | Private research only, not an official Qwen conversion. Original weight/exporter ancestry remains unresolved. No production integration or distribution. |
| Rejected Qwen host candidate | Diagnostic full-human evaluation completed: 9/18 strict passes, English 3/6, Japanese 2/6, Mandarin 4/6. Pooled errors: English WER 9/109, Japanese CER 14/207, Mandarin CER 11/187. Frozen corpus, scorer v2, 5% per-clip thresholds and anchors were unchanged. All 18 native jobs exited 0 and were reaped without error/warning evidence. Cold CLI plus observer took 1.8007–3.3386 seconds; maximum sampled RSS was 1,750,237,184 bytes, below 2 GiB. | Reject this 0.6B candidate for quality; full-set errors include actual mistranscriptions, not only orthography. Diagnostic timing is not warm inference or phone speed. Sampled RSS is not a kernel allocation ceiling. Natural EOS, segment timestamps and Android/phone behavior remain unproven. No promotion. |
| Official Qwen 1.7B hosted canary | [Unprivileged CPU run](https://github.com/ihearttokyo/Lip/actions/runs/37823211837) passed original EN13 with 0/17 word errors and both count/negation checks, natural EOS and unchanged 5% gate. Peak RSS was 3,295,793,152 bytes; cold startup/request/whole-case times were 1.018/5.086/8.039 seconds. The parent verified artifact digest, exact response, source/weight pins and recomputed the frozen score. [Sanitized receipt](../eval/qwen17-canary-receipt.json). | One English clip only, not full-set quality, warm latency, Android Stop, memory fitness or microphone/ChatGPT evidence. Full 18-case research is the next qualified stage. Exact converter revision/command remain unpublished; no Android integration, redistribution or promotion. |
| Qwen canary/control limits | Original failed nine-case canary is retained. Its six language-labelled controls reused two unique WAVs and all returned exactly empty raw text; controls were not rerun in the full-human evaluation. | The Japanese first canary rendered `3` versus reference `３` and `けが人` versus `怪我人`. `いませんでした` remained present: no demonstrated count or negation loss in that clip. The strict failed score remains unchanged; this does not waive other quality failures. |
| Quiet held-out speech | Q5_0 full-context host ASR passed 7/24 quiet/pause variants; 17 failed, including lost negation and unrelated hallucinated sentences. | Separate from the frozen 18-case result. Q4 quiet coverage remains untested. No VAD, cropping or silence-discard policy is promoted. |
| Packaged Android JNI | File-fed API 36 ARM emulator, 7.54-second English clip, two sequential decodes per model. Debug-unoptimized base took 73.813/68.236 seconds. Optimized base took 1.390/1.307 seconds but misrecognized “three” as “free”. Optimized selected turbo loaded in 0.591 seconds and decoded correctly in 25.075/25.600 seconds. | Turbo warm real-time factor is 3.40, slower than the audio and outside the latency gate. Emulator timing is not physical-phone speed. File-fed JNI proves actual decoding, not microphone transport or a usable complete dictation session. |
| Experimental Q4 native | In the same feature-qualified ARM dot-product APK, Q4 warm decoding took 9.137 seconds versus Q5's 20.811 seconds: a 56.1% time reduction on the 7.54-second English clip, with its words/facts preserved. Host Q4 still passed 15/18. | Warm real-time factor 1.212 exceeds 1; the 5-second gate still fails. This candidate is not promoted or distributed. The single native clip and unchanged host pass count do not establish general quality or phone speed. |
| Rejected context experiment | Host turbo with reduced encoder context, `min(1500, ceil(samples/320) + 50)`, passed 8/18 rather than the full-context baseline's 15/18. | New count/negation failures and Japanese/Mandarin repetition reject this candidate. Android source retains full context because the faster candidate degraded accuracy. |
| VAD research | Actual host Silero 6.2.0 probability observation on 18 human cases and six language/control cases. | Controls reuse two generated silence/noise recordings. This is neither an ASR accuracy result nor a shipped cropping/discard policy; no model or observer result authorizes dropping captured speech. |
| Deterministic safeguards | The reviewed exact-zero guard APK passed the full Gradle unit suite and lint: 106 tests, zero failures/errors/skips. The focused Raw/Cleaned output suite has 4 JVM tests; capture has 12, including 2 late-Finish regressions. Those 16 reached first GREEN; independent late-Finish correctness/Ponytail review was clean. | PCM/timestamp and model-string fixtures are not ASR quality proof. Late-Finish admission blocks stable-word commits and zero-prefix consumption from a late preview, retaining audio for the final decode. Human-speech end-to-end acceptance remains open. |
| Supplied-text native UI | LIVE GREEN: `toggle-gui-frozen-live.log` accepts Home/bubble Raw/Cleaned Unicode round trips, Copy/insertion authority, cross-app selection insertion, stale-target refusal and explicit rebinding. Final Home Cleaned and bubble Raw PNGs were hash-verified and visually inspected; selected-state feedback was visible. | Supplied-text UI evidence only: not ASR, authenticated cloud cleanup or physical-phone speech. Screen-off retention in this test is simulated. Successful native controls do not waive the failed microphone/accuracy/latency gates. |
| Exact-zero engine guard | The guarded engine returned empty segments for synthetic signed-zero PCM; the packaged runner accepted exact-zero, invalid-input and fresh-after-cancel checks with result code -1. Its two one-second zero-window calls took 4/1 ms after a 1,871 ms model load. Independent correctness/Ponytail review and four focused JVM assertions passed. | The original default closed-engine assertion could swallow its own failure, so that part of the old receipt is not validated. Its repaired assertion awaits an actual rerun. Literal +0/-0 only, not a VAD or low-energy cutoff. Nonzero/NaN/infinity still use unchanged JNI. No live-microphone-zero, in-flight cancellation, speech-quality or general speed claim. |
| Priority-dose diagnostic | A field-only trial requested nice 10 but observed nice 0 on the inference caller and attributed native workers across 40 samples, with capture threads unchanged and owned cleanup confirmed. The setter candidate's first A0 control then verified actual nice 0 across 40 samples, but failed the original 15-second Finish gate and overall cleanup gate. | The comparison stopped before B10 or the returning A0. Both experimental patches were reversed, exact original source pins restored, and the prior nonexperimental APK pair rebuilt/reinstalled. No capture, priority, latency or backend improvement is promoted. Overall cleanup still failed after the worker and engine eventually closed. The receipt did not identify the failed cleanup step. |
| Current JNI human observation | A separate English file-fed attempt exceeded its original 150-second host observation deadline. Native lifecycle logs later showed instrumentation completion and target termination after roughly 174 seconds; the final transcript report was not received. | The original timeout remains FAIL. The selected RelWithDebInfo compile commands contain `-O2`, and the APK's native library matches the corresponding merged/stripped output. An old Debug cache is not evidence that this APK was unoptimized. Current human accuracy and latency are not established by the successful zero guard checks. |
| Provider-owned public microphone probe | A test-only distinct-Main-task repair reached genuine `onReadyForSpeech`, verified installed English, and exposed a current unsilenced provider recorder. A native event-log header initially caused a controller rejection before input; the repaired parser passed 22 unchanged/new contract checks and independent review. | The subsequent English canary still failed the native-clock validation before audio injection. All three failed canaries and cleanup receipts remain intact; no human waveform was delivered or accuracy scored. Japanese/Mandarin microphone cases remain unrun. This is not the published pipe route, bubble, phone or cloud acceptance. |
| Production microphone PCM | LIVE GREEN: `audio-probe-756s5e74` exercised actual `PcmSource` → `PcmWindow` with synthetic tone. Accepted/received/windowed counts were 384,000 bytes (192,000 samples) over 12,016 ms; pipe/window SHA-256 both `1c32ca12082d0d12c00d4b050f2d501abff863be29066363932b61eb23c01969`. EOF arrived, reader/writer joined, source errors were zero and cleanup passed. | This proves the measured tone capture/retention/Stop path, not full human-waveform delivery, ASR or the Main dictation/editor flow. Physical-phone routing, interruptions, pauses, preview latency and third-party editors remain unverified. |
| Human microphone Main | FAIL: `audio-flow-120xldj1` exceeded the original 15-second Finish gate; eventual READY at 46.746 seconds ended with “none of the”. The 309,760-byte retained PCM witness's last sample with absolute PCM16 amplitude ≥8 was at 5,743.9375 ms versus 7,529.125 ms in the source. Fresh file-fed JNI on that witness reproduced the same truncation in 25.139/27.541 seconds. | Original failure/result code 0 is preserved. READY alone does not establish successful recognition. The amplitude endpoint is a diagnostic, not a VAD rule or proof of which transport stage shortened the input. Complete human microphone delivery and speech-driven preview/manual insertion/history acceptance remain open. |
| Capture-only speech diagnostic | `speech-capture-q_km9_ft` retained 384,000 bytes; pipe/window SHA-256 both `ade37bb3b83e7f6bc1c0ce0f64dba3d140456f59c9521e68faef0ce37b327ba5`. Its strict tone gate remained FAIL/result code 0. Packaged JNI later decoded that speech PCM twice to all reference words in 26.098/25.111 seconds, unlike concurrent-ASR Main capture. | Diagnostic comparison only, not a retroactive tone/UI pass or exact waveform-delivery proof. Capture-only held 12 seconds versus Main's 9.54; the common interval is not a perfect one-variable experiment. This supports a load/scheduling hypothesis, not an established SDK cause or backend promotion. |
| Rejected injector-tail candidate | A tail-only 1.25-second injector candidate, `audio-flow-vwv2ytwj`, failed again: its retained signal endpoint was only 2,383.9375 ms and raw output was “Although free people”. | Worse input coverage; not a production ASR or UI pass, and not promoted. This transport experiment does not change production recognition or waive the failed gates. |
| Platform model installation | Owned-emulator standard UI confirmed one Japanese download (advertised 51.53 MB) and one Mandarin download (advertised 52.97 MB). Success callbacks arrived at 135,720/206,046 ms; fresh public support lists `en-US`, `ja-JP` and `cmn-Hans-CN` installed. Both installations passed cleanup/result code -1. English's already-installed metadata check used zero download requests and returned -1. | The original English API listener timeout at 300,001 ms without callbacks remains a separate FAIL. Installation metadata is not speech-quality or microphone evidence. Provider models are managed by AiAi, with no production backend change. |
| Initial platform English canary | Separate file-fed `fleurs-en-013` canary completed in 304 ms: all 17 raw reference words matched, and the formatted candidate matched reference punctuation exactly. | One unpaced file-fed clip, not physical-phone or real-microphone latency, controller acceptance or authenticated cleanup. It does not supersede the corpus result below. |
| Unpaced platform file-fed ASR | Frozen English and JA/Mandarin runs (`platform-eight/scored.json`, `platform-ja-zh/scored.json`) completed 18 human clips: 6/18 passed, two per language. Six control cases passed using only two unique WAVs. All 24 protocol/workflow checks passed; raw scorer-v2 references, fact anchors and the 5% per-clip threshold were unchanged. | Quality gate rejects this measured unpaced path, with many missing words. Full writer-byte counts do not prove engine input consumption. Intrinsic engine quality is not established by this delivery path. Paced and continuous file-fed results below remain test-only. Phone, real-microphone, controller and cloud gates remain open; no backend promotion. |
| Platform continuous file-fed diagnostic | LIVE GREEN: two English synthetic concatenations of complete human recordings with exactly 20 seconds of generated zeros. Both had WER 0, passing anchors/intentional repeat counts and two stable segments each. Measured gaps were 20.417/20.475 seconds, maximum packet lateness 735.299/804.056 ms, writer duration 36.436/35.885 seconds and EOF-to-completion 17/18 ms. | Earlier sleep/park runs failed the producer's one-second lateness gate before source 2 or EOF; retained failures are not ASR quality verdicts. The hybrid 3 ms clock tail is test-only. CPU samples cover the whole emulator; nominal 7.5% of one core is not a measured producer CPU cap. Synthetic file-fed evidence does not establish production controller, microphone, phone or cloud parity. |
| Matched platform pacing A/B | Same APK/provider/ja-JP/source PCM for `fleurs-ja-025`: unpaced CER 13/35 = 37.14%; paced CER 7/35 = 20.00%. Both protocol/workflow checks passed. | Both still FAIL the unchanged 5% threshold. Only producer delivery changed; references and normalization were unchanged. One improved clip does not establish a quality fix or authorize promotion. |
| Paced platform file-fed ASR | Representative set completed: 6/18 human passes, 2/6 per language; 6/6 controls using two unique WAVs; 24/24 protocol/workflow passes. EOF-to-completion was 3–47 ms. Pooled errors: English WER 12/109, Japanese CER 38/207, Mandarin CER 28/187. Original data, scorer v2, 5% per-clip thresholds and anchors were unchanged. | Pacing alone did not fix quality. Reject this measured path without promotion. Errors include `fleurs-ja-030` rendering 21:25 versus reference 21:20, not only orthography. These public-API file-fed diagnostics do not validate the production Whisper controller or microphone/phone/cloud gates. Known production failures remain. |
| Authenticated cleanup | Official per-install OAuth/PKCE and transcript-only Responses integration is implemented; protocol/error tests are separate evidence. | User OpenAI sign-in/authorization and live cleanup have not completed. No simulated response or host ASR result closes this gate. |

Reproducibility: [corpus manifest](../eval/corpus.json), [scorer and runner](../eval/README.md),
[engine/model pins](IMPLEMENTATION.md#local-speech-backend), and
[experiment contract](HILLCLIMB.md). Local receipts are named
`base-human-scorer-v2.json`, `small-human-scorer-v2.json`,
`turbo-human-scorer-v2.json`, `turbo-greedy-human-scorer-v2.json`,
`native-base-canary.log`, `native-base-optimized.json`,
`native-turbo-canary.log`, `turbo-dynamic-context-human.json`, and
`vad-observation-green.json`. New local receipts include
`turbo-q4-human.json`, `native-turbo-q4-dot-canary.json`,
`turbo-quiet-heldout.json`, `audio-probe-756s5e74/result.json`,
`audio-flow-120xldj1/guest.log`, `witness-whole-jni.log`,
`audio-flow-vwv2ytwj/witness-tail-stats.json`, and
`toggle-late-finish-red.log` / `toggle-late-finish-first-green.log`,
`toggle-gui-frozen-live.log`, `output-home-cleaned-final.png`,
`output-bubble-raw-final.png`, `parity-unit-lint-full.log`,
`platform-speech-probe-live.log`, `platform-model-download-live.log`,
`platform-speech-after-ui-download.log`,
`platform-download-ja-live.log`, `platform-download-cmn-live.log`,
`platform-download-en-already-live.log`, `platform-pipe-en13-scored.json`,
`platform-eight/scored.json`, `platform-ja-zh/scored.json`,
`platform-continuous-live/scored.json`, `platform-continuous-park-live/scored.json`,
`platform-continuous-hybrid-live/scored.json`, `platform-ja025-ab/scored.json`,
`platform-paced24-live/scored.json`,
`qwen-model-files.json`, `qwen3/preparation/initial-nine-314.json`,
`qwen3/preparation/full-human18.json`,
`speech-capture-q_km9_ft/diagnostic.json`, `capture-without-asr-jni.log`,
and `validation/hillclimb/sensevoice/sensevoice-full-human18-controls6.json`.
Raw audio, runtime models, disposable logs, signing material and auth state
are not release assets. A candidate change needs a new measured comparison;
the frozen references and failed gates must remain intact.
