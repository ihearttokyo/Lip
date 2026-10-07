# Validation receipt

## Build and deterministic behavior

Validated locally on 2026-10-07 (Asia/Tokyo) with the pinned JDK 17 / SDK 36
toolchain. Eleven JVM tests pass: PKCE, OAuth callback/state/client rejection,
signed JWT identity, complete/failing/truncated SSE, verified file deletion,
Unicode normalization, editor guards/selection replacement and history codec.
The first eight assertions and the added deletion assertion were observed
failing before their implementations; the unchanged assertions then passed.

`testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleRelease` and the
Android test APK build passed. Lint has no errors; its remaining warnings only
note newer compatible tool/dependency versions. Versions remain pinned for
the verified build rather than upgraded solely to remove informational flags.
The release APK's v2 signature verifies, and dependency notices are packaged.

Independent static correctness review found and resolved main-thread session
locking, hint-text insertion, draft restoration, asynchronous dictionary
overwrites, review target preservation, consent UI and sign-out error claims.
Ponytail review found no remaining over-engineering cuts.

## GUI and native evidence

The actual app home screen and floating bubble were installed, launched and
visually inspected on an isolated Android 16/API 36 headless emulator. The
native smoke runner passes Keystore round-trip/empty-value/deletion, UI/draft
assertions, real cross-app selection replacement and stale-target refusal.
Its first attempts failed because instrumentation restarted the service and
the fixture requested keyboard input before its window was served. Requesting
input after window focus fixed the fixture; the insertion assertions were not
weakened. Test text is supplied directly: this proves native insertion, not
speech recognition or live ChatGPT cleanup.

Computer-use browser checks passed for the desktop landing page, Japanese and
Mandarin example/style controls, verbatim output, copy feedback, mobile menu,
privacy page and 390px/320px widths without horizontal page overflow. A mobile
overflow defect and Light examples that removed words were repaired and
rechecked. Prepared examples are fixtures, not live speech/model results.

## Remaining gates

- NOT RUN: real-phone microphone capture and English/Japanese/Mandarin model quality.
- NOT RUN: authenticated Android ChatGPT OAuth, refresh, model discovery and live cleanup.
- PASS: emulator cross-app selection replacement and stale-target refusal with supplied test text.
- NOT RUN: physical-phone computer-use GUI validation or the editor compatibility matrix.
- NOT RUN: Play Store policy/review, Bluetooth/call interruption and long-session hardware tests.

Use an eligible Android phone to verify the bubble, unchanged-editor insertion,
selection/composition, protected-field refusal, missing models, cancellation,
offline fallback and browser OAuth. No private account credentials or audio
were used during validation. The APK remains a prerelease, not a Wispr Flow
feature-parity or production-readiness claim.

## Writing and artifacts

README, notices, privacy, implementation contract and visual brief passed the
installed prose checker. The landing page's FAQ triggers its question-volley
heuristic; this is retained under the writing guide's A10 exception because
the questions are a genuine user-reference FAQ, not rhetorical framing.
Protected URLs, numbers and research limits were checked editorially.

Source, generated notice assets and packaged APK notices are parity-checked.
Signing material stays in local owner-only storage, never GitHub. The original
research export stays local; only its source identity/hash is recorded.
