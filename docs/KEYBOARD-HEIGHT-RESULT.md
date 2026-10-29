# Fine-grained docked keyboard height — scoped evidence

29October2026. [The v1 contract](KEYBOARD-HEIGHT-CONTRACT.md) remains unchanged.
This is adjustable docked height, not floating/repositioning or a product score.

## Implemented

Keyboard preferences offers80–140% in one-percent steps, exact Applied/Preview
labels, an inert keyboard geometry preview, a slider, explicit +/- buttons and
Apply. Slider and step changes remain drafts; presets90/100/115 and Reset100 save
explicitly. Actual height adapts to existing device/window/cap safeguards. The
preview uses its own panel width and the same row-height/layout methods; it is not
an interactive editor or a promise of identical physical preview pixels.

Preferences v3 retains legacy v1/v2 migration and exact legacy Float/truncation
preset geometry. Settings and the one-hand rail now transform the latest confirmed
field rather than overwrite unrelated preferences from an old screen. Failed
writes retain confirmed authority. No text/audio/provider/clipboard data is stored.

## Actual source/build controls

Offline unit tests, lint, ARM64 app and test APK builds pass:785 JUnit cases,
767 executed,18 explicit skips, zero failures/errors. Nine new fine-height tests
execute. Independent bounded source and intended-use/evidence reviews pass.
The initial coupled schema assertion failed because it expected seven old keys;
the repair explicitly tests both the new eight-key v3 and a constructed seven-key
v2 migration/channel-isolation case. No prior isolation check was removed.

## Actual portrait emulator workflow

Normal Settings → Keyboard preferences → slider80 shows Applied100/Preview80
without changing the absent preference file. Apply creates confirmed80. The
ordinary five-row keyboard is actually smaller, not merely relabelled.

Reopening Settings shows Applied80. Slider140 and the real -1/+1 buttons produce
139 then140, disabling increment at the upper bound; the confirmed80 file remains
byte-identical until Apply. Actual Reset100 restores default geometry and preserves
all unrelated preference values. Draft101 followed by leaving without Apply keeps
confirmed100 bytes and actual standard keyboard geometry unchanged.

| Confirmed setting | Observed row pitch |
|---|---:|
|80%|96px|
|140%|169px|
|Reset100%|121px|
|Leave draft101 without Apply|121px|

A read-only pixel method was fixed before candidate observations and calibrated
on the prior default121px image plus an editor-only negative. It detects exactly
five uniform dark keycap bands or refuses. Decoder and SHA use the same immutable
input bytes. This measures portrait row spacing, **not** page semantics, touch
rectangles, latency, energy, caret state or physical-device correctness. The caller
independently inspected the actual Letters-page captures.

Returning to the existing owned receiver task used the explicit Android task
reorder command, which reported that no new Activity was started. The receiver
observes unchanged baseline text and zero attachment, Enter or editor-action calls.
It does not provide caret/protected-field-read telemetry; none is claimed.

## Evidence limits

These are exact frozen development APK/source observations on the owned API36
ARM64 emulator with guest network radios off and synthetic editor data. Runtime
version metadata precedes this feature's next reservation; no new signed release
or matching public download is established. Actual narrow/landscape/one-hand
caps, large fonts, screen-reader/hardware-key behavior, gesture/lifecycle matrix,
legacy on-disk upgrade, true write-failure runtime, caret and physical qualification
remain open. No installed Mac, phone, account/provider, microphone recording or
speech-model retrieval was used. Original full9.2 and parity gates remain intact.
