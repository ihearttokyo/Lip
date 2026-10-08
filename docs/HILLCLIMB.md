# Speech and formatting hillclimb

Jared requested continued iteration and autonomous testing, not another artifact
milestone. The active outcome is the Android parity contract in PARITY.md.
Perfect recognition for every possible speaker/noise condition cannot be
certified by a finite benchmark; this work measures errors and closes concrete
failures rather than asserting perfection.

## Frozen first-stage gates

- English word error rate and Japanese/Mandarin character error rate: at most
  5% per admitted human clip. Preserve numeric and negation anchors exactly.
- No invented speech in silence, lost/duplicated segments or stale insertion.
- CPU warm recognition real-time factor below 1; Android Stop-to-final target
  below five seconds. Cold startup and memory are recorded separately.
- Formatting: ordered lists and spoken punctuation, clear corrections and
  dictionary terms; preserve facts, polarity, code/URLs/literals. Verbatim is
  identity. Light retains lexical content and order. Unsafe cloud output must
  not qualify for automatic insertion.
- No audio upload, private endpoint, cross-app credential reuse or weakened
  TLS/authentication. No paid API key. Protected-field/permission/cancel guards
  and encrypted data remain acceptance requirements.

Freeze inputs and references before engine runs. Compare installed pinned
whisper.cpp1.9.2 multilingual base Q5_1, then change only the model to small
Q5_1 if necessary. New candidates require measured failures and a new pinned
artifact/license/capacity receipt. No invented score or post-result reference
change may turn failure into a pass.

## Evidence lanes

1. Independent human recordings: admitted eighteen-clip English/Japanese/
   Mandarin FLEURS subset, exact source identity/CC-BY attribution, complete
   retrieval and SHA256. This is read-speech coverage, not spontaneous dictation.
2. Synthetic speech: installed macOS voices for commands/corrections plus
   deterministic pause/noise/silence transformations. Label separately; no
   human-voice quality claim.
3. Actual Android path: authenticated emulator microphone injection, verify a
   known tone in guest AudioRecord, then speech through real native/JNI ASR,
   session controller, bubble, editor and encrypted history. Text-injected
   fixtures alone do not close this lane.
4. Actual cleanup: Lip's real Android OAuth client via verified loopback
   forwarding to a managed host browser, then authorized Responses inference.
   User credentials/MFA/new-client consent remain protected boundaries; no
   credential borrowing. Pure HTTP fixtures cover errors but not live quality.
5. Physical hardware remains a distinct latency/audio-routing/editor-coverage
   lane. Its absence does not justify stopping independent engine, formatting,
   HTTP or Android-injection work.

Use bounded serial inference/native builds initially. Six useful children are
currently available to this session; requested larger ceilings do not override
platform slots or abnormal memory/swap guards. All children are explicitly
pinned to GPT6.1-Sol/xhigh for this task. Model/effort/tier telemetry limitations
are not acceptance blockers. Parent owns integration, reviews, evidence and
publication. Existing releases are retained; PR merge authority is separate.

## Current baseline

v0.2 at cf311b3 has packaging, deterministic and native cursor/storage tests,
not measured human-speech/cleanup parity. The current hillclimb is active.
Runtime models, signed URLs, private keys/tokens and disposable validation
outputs stay out of Git. Public speech-corpus/source/model attribution is
preserved when artifacts are distributed.
