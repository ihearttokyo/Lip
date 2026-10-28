# Lip — next major upgrade series

26 October 2026. Owner: this chat. Canonical checkout `/Users/jared/Developer/Lip`,
branch `codex/lip-local-bark`. Existing dirty local-only work is the baseline,
not a reason to reset or rebuild successful components. Pre-keyboard source and
diff: `validation/keyboard-upgrade-20261026/`. No Git publication is authorized.

## Product direction and authority

One local-first dictation product on macOS and Android, plus an independent
Android input-method keyboard that can replace Gboard for agreed English tasks.
This is behavioral compatibility with an original Lip design—not a Google APK
patch, proprietary asset/model extraction, or claim to be Gboard. Jared confirmed:
English first; Japanese/Mandarin follow later; physical-phone testing remains held;
an independent hidden-score review subagent is authorized. No cloud inference,
OpenAI login, paid API/model/training fallback, telemetry or autonomous sends.

The current phone hold limits final qualification, not source/build/emulator work.
It does not authorize switching Jared's default keyboard, microphone/Accessibility
permissions or other security preferences. Human authorizes those separately.

## Preserve the established baseline

- macOS 0.6.0 and Android 0.4.0-local have Clean standard / optional Pro selectors,
  pinned verified model downloads and local English ASR. Mac Core ML allows
  automatic ANE/GPU/CPU placement; Android presently has a portable CPU baseline.
- Clean and Pro file-fed Mac and production JNI host tests pass. These are not
  representative human-speech, Android-device latency or energy qualification.
- Fn gestures, model-native output, optional legacy app rules, transcript review,
  no-send protections and existing encrypted Android history remain. No extra LLM.
- Mac native selected-text and Unicode-event insertion fixtures pass without
  clipboard writes. A real chat attempt was **unconfirmed/possibly partial**;
  actual chat insertion is NOT accepted. Never retry an ambiguous write blindly.
- Android currently supplies an overlay, not an IME or a swipe keyboard. Do not
  rebrand the overlay or a compiled prototype as Gboard parity.
- Android and macOS settings/history/insertion/review capabilities differ.
  `docs/keyboard/PARITY-MATRIX.md` is the explicit closure queue, not an assertion.

## Delivery blocks (coherent increments; no perpetual polish)

### A. Freeze scope, oracle and safety baseline

Register the English Gboard comparison surface, parity matrix, immutable rubric,
paired benchmark protocol and model/license inventory before tuning. Establish
verifier negative controls: missing evidence, stale build, tiny samples, unsafe
editor mutation, score 9.19 and any critical failure must refuse acceptance.
Keep all old source/history and accepted native ASR behavior. Plan completion is
not product completion, and no numerical quality rating is currently earned.

### B. A real Android keyboard with safe editor ownership

Use Android InputMethodService/InputConnection with a distinct Lip IME component.
Enable/select only in a disposable emulator until physical authority changes.
Tap keys, Shift/Caps, symbols/numbers, language/IME switch, backspace, Unicode-safe
editing and deliberate Enter/editor actions must work before gesture prediction.
Dictation mic uses existing Bark/CaptureSession lifetimes and original editor/caret
ownership. Focus/selection/package/session changes cancel or refuse writes. Never
submit automatically after dictation. Password fields allow explicit manual taps
but disable dictation, predictions, learning and trace capture. Honor incognito
and no-personalized-learning hints; default persistence of typing context is off.

### C. License-cleared neural swipe, not marketing-first model adoption

Evaluate a small immutable shortlist using the same admitted trace set. Current
lead candidate for evaluation: Destin Moss/youcoded's MIT native CTC/GRU model
and MIT engine portions of `itsdestin/swipe-keyboard` (not GPL KDE front ends).
Retain source/license/data attribution and exact weights digest. A model card's
90.4% is the publisher's result—not Lip's result or a comparison against Gboard.
CleverKeys is a GPL reference candidate, not code to copy into an MIT binary;
its separately hosted ML artifacts need explicit weight-license verification.
FUTO's model weights have separate terms; do not assume MIT data licenses weights.
Do not silently relicense Lip or import nonredistributable/proprietary binaries.

Model processing: actual key geometry and time-aware trace normalization → neural
key probabilities → bounded lexicon-constrained CTC decoding → ranked candidates.
Use owned composing text for corrections; no blind character-count replacement.
Inference off UI thread; stale generations cannot publish. Disable/identify missing
or failed neural inference; don't secretly label a geometric fallback as neural.
Collect no user traces without opt-in. Device policy is internal and measured:
a small model need not benefit from GPU/NPU dispatch overhead. No hardware menu.

### D. English Gboard everyday-function coverage

Deliver tap/swipe, next-word suggestions, conservative autocorrection with undo,
user dictionary, sentence case/punctuation, numeric/phone/email/URL layouts,
emoji search, punctuation alternatives, repeat-delete and gesture-delete,
spacebar cursor control, selectable correction candidates, theme/height/haptics,
one-handed layout, accessibility/touch exploration, password-manager/autofill
compatibility, editor actions, rotation and recovery. Each is a separate tested
matrix row, not a single keyboard screenshot. Opt-in clipboard tools must never
change clipboard contents as a side effect of dictation or insertion.

Local-first divergence register: no Google Search, cloud Translate, remote GIF
search, account sync, biometric/handwriting claims or all-language support will be
silently advertised as equivalent. Local substitutes or explicit user-adopted
scope decisions are needed to close those rows; a smaller passing tranche may
ship honestly without completing this whole goal. Japanese/Mandarin are later.

### E. macOS/Android shared-feature parity

Match selected Clean/Pro semantics, native-output policy, install/cancel/integrity
handling, model readiness/error reporting, original/output/model-details review,
safe direct insertion, cancellation and recovery, settings/dictionary contracts,
and optional history/privacy behavior. Platform mechanisms differ (Fn vs IME mic,
Core ML vs Android runtime); matched user behavior does not require a desktop
swipe keyboard or identical model bytes. Any deliberate difference is explicit.
No secret extra cleanup model to manufacture formatting parity.

### F. Qualify the actual experience, then freeze and deliver

Use the versioned quality gate in `loop/RUBRIC.md` and machine acceptance in
`loop/acceptance.json`. Independently review each coherent intended-use block;
implementer receives defects/reproductions or PASS, not a numerical target to game.
Pass at **9.2/10 without rounding**, with no critical privacy/license/data-loss,
crash, stale insertion or primary-task failure. Freeze exact accepted artifact
identities. Reopen only on a material regression or requirement change.

Evidence layers: unit → real native model → emulator IME/editor → authorized
physical device and paired Gboard study. Compilation, synthetic gestures and
host JNI never stand in for later layers. Record cold/warm latency, p50/p95,
memory and energy separately. Expose honest model/loading/error states, not an
optimistic progress meter. Deliver source-matched APK/app, concise setup, rollback
and bounded validation receipt. Publishing/signing/default-keyboard changes remain
separate authority gates. No user data is deleted to install, downgrade or test.

## Iteration discipline (from today's three main chats)

Define intended use + fixed baseline + failure threshold before each hypothesis.
Make the smallest coherent reversible change; show RED then GREEN; retain rejected
variants and stronger counter-evidence. Prioritize visible major capability gaps,
not endless UI polish or test counts. Graphics explain; short task copy; details
hold technical context. Accepted identities stay frozen. Keep PASS, FAIL, NOT RUN,
HELD and UNCONFIRMED distinct. A model downloaded, a PR merged or a plan written
is not consumer acceptance. Never move the benchmark/rubric after seeing results.

Reference identities: `JRB BACKTESTER - Main`, `NULL CHOIR — Main`,
`THE CHAIR - Main`, their 26 October major-upgrade plans and product review gates.
Their trading/game/publication authority does not transfer to Lip.


## Authoritative visual addition — Jared,26October2026

Use Jared's five supplied Gboard crops as the English layout reference. Black
background, dark-gray rounded rectangular keycaps, pure-white characters and a
Liquid Glass material theme are mandatory. The reference's borderless letters
become the requested dark-gray glass caps, without moving the familiar keys.
`docs/keyboard/VISUAL-CONTRACT.md` fixes the independent visual9.2 gate. No cream
prototype or generic Gboard picture may substitute. This adds a criterion; it
waives none of the functionality/privacy/benchmark requirements. Original photos
stay private/ignored, not app assets or public documentation.
