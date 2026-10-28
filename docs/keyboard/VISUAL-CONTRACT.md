# Keyboard appearance — Jared's Gboard reference, 26 October 2026

Authority: Jared's explicit black/dark-gray/pure-white/Liquid Glass request and
five supplied keyboard crops (207225,207223,207219,207217,207215). Private originals
and SHA manifest are retained only in ignored validation/references, not shipped.
No claim that these crops prove touch behavior, phone density or current settings.

Mandatory: actual black background #000000; dark-gray rounded rectangular keycaps;
primary characters exactly #FFFFFF (not cream/off-white); unchanged familiar Gboard
English geometry. Number row ON,10-column top rows, half-key home-row stagger,
Shift +7 letters+Backspace third row, ?123/emoji/wide English space/period/action
bottom row. Preserve shown symbol hints and their actual long-press behavior.
Digits, symbols, numeric pad, emoji/search/category and toolbar surfaces need their
own reference-bound checks. Japanese/translation remain later-scope language work;
a reference icon is not permission to advertise a nonexistent feature.

The provided alphabet screenshots use borderless letter keys; the requested
dark-gray glass keycaps are an explicit deliberate material change, not a promise
of pixel-identical borderless art. Layout/hit geometry stays stable. The black,
gray and white instruction controls colors; do not copy a lower-contrast gray
foreground merely because it appears in a JPEG. Key labels stay sharp over glass.

## Material interpretation

Apple's Liquid Glass is a dynamic optical material, not just lower opacity.
Its regular variant favors legible controls; clear glass needs appropriate
background/dimming. Android has no native Apple Liquid Glass API. Lip's Android
material must be honestly described as an Android implementation of that visual
language: restrained lens/rim highlights, layered dark material, coherent pressed/
released response and optional reduced effects. Never blur the characters, use
motion as the only state signal, read another app's backdrop, or capture private
screens to manufacture refraction. No saturated neon, cream, rainbow chrome or
layout changes justified as glass. Black backdrop and white text outrank decoration.
Sources: https://developer.apple.com/design/human-interface-guidelines/materials
and https://developer.apple.com/videos/play/wwdc2025/219/ (checked26October2026).

## Independent visual gate — >=9.2/10 without rounding

Separate from the whole-keyboard quality gate; it cannot hide in a10% aggregate.
Weights fixed before visual iteration: reference geometry/familiarity30%, typography/
contrast25%, glass material craft20%, coherent states/motion10%, accessibility/
real intended-size legibility10%, source/build/reference fidelity5%.
Every dimension>=8.5, weighted total>=9.2, no clipped/covered key, wrong primary
label, gesture hitbox drift or missing primary control. Actual app renders at
intended phone size, light/dark surrounding app content, normal/pressed/disabled,
portrait/landscape, large text and reduced effects. Compare reference-aligned
rectangles/centers and pure color tokens; reviewer owns craft/familiarity ratings.
No author's self-score, image-only gesture proof or rounded9.19. While physical
phone testing remains held, real-device touch/feel remains unqualified.

Initial geometry targets from crop207215 (normalized to keyboard width, excluding
system navigation): top number/letter centers0.05+0.10i; home0.10+0.10i; third-row
Shift0.075, letters0.20..0.80, Backspace0.925; bottom Space centered0.50, width0.50;
?123/emoji/space/period/action widths1.5/1/5/1/1.5 units. Confirm tolerance against
actual raster and user font/height settings rather than claiming crop-derived dp.
