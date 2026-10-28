# Obsidian etched — appearance and handoff extension

Authority: Jared's27October2026 request. Previous goal turn35 = PROGRESS. Additive
requirements; existing frozen9.2/no-rounding/dimension-floor contracts remain, not
retroactively passed. English first; Japanese/Mandarin roadmap retained. All
translation remains future-only. Parity release/site precedes major upgrade; code
may advance without exposing unfinished network/media controls in parity release.

## Design plan, reviewed against the brief before code

Subject: Lip's own Android keyboard, familiar Gboard geometry with the tactile
clarity of a small black instrument panel. Not frosted gray wallpaper or a copied
Apple asset. The memorable element is the single45°offscreen light across rounded
rectangular keycaps; typography and controls stay quiet.

Tokens: void backdrop #000000 at100%opacity(default, within requested90–100%);
key body #303033, upper facet #37373A, lower edge #242426, etched labels #FFFFFF,
existing confirm accent #80CBC4. No blur/refraction over text, texture under letters,
noise animation, photo background or cross-app sampling. Subtle bevel reflection
belongs to the rim, not a shiny stripe through the glyph. Fixed world-space upper
left45°light, restrained peak luminance, pressed-state feedback. Opaque black is
intentional, not a generic tinted near-black theme. Keep the existing matte/reduced
effects option and honor system animation scale; no idle animation.

Type: existing Android sans-serif/light key legends; pure white, no shadowed fake
laser engraving, no offwhite glyphs. Separate user controls for key-label scale,
emoji preview scale and sticker preview scale; preserve key hit rectangles, swipe
centroids and output content. Size controls offer System/default plus bounded
steps and honest fit limits on narrow keys/windows/large system font. Emoji/sticker
preview size never promises to alter recipient-side Unicode/image rendering size.

Layout: toolbar, number row, QWERTY, ASDF, shift/ZXCV/delete, bottom row match the
supplied screenshots. Do not shift rows to make room for decoration. Settings uses
readable vertically stacked rows, direct labels and live representative preview.
Media grid adapts column/cell size rather than enlarging glyph inside a clipped
unchanged cell. Preserve scrolling, tone variants, supported-glyph filtering,
accessibility descriptions and reachable48dp targets where layout supports them.

    toolbar [tools … optional agent … mic]
    number row
    Q W E R T Y U I O P
      A S D F G H J K L
    shift Z X C V B N M delete
    ?123 emoji      English       . enter

Self-critique: no broad translucent sheets or glowing gradients; those conflict
with the explicit opaque-black brief. Keep existing geometry instead of a designer
concept that cannot type. Generated art is a MATERIAL benchmark, not a literal UI
bitmap, score, actual screenshot or usability evidence. Compare actual rendered
portrait/landscape/narrow/one-hand settings and media grids to it; independent9.2
still needs real intended-use evidence. Do not grade generated text/layout as app UI.

## Capability scope

Reuse the existing officialSDK-image Gboard APK reference, validate pinned identity;
no unknown mirror download/patch/proprietary code or image copying. Audit current
official Gboard help and observable reference features as an explicit evolving
inventory. An old APK cannot prove all current, server-enabled or locale features.
Keep gaps OPEN; no universal parity claim from decompilation, resource names or tests.

Optional pinnable toolbar action uses the last COMPLETED Lip dictation, never reads
an arbitrary field/clipboard or retranscribes. Short press uses the configured
provider/default effort; long press reveals a user-triggered slide-out sheet with
Instant/Medium/High/Extra High/Pro as requested, provider-capability-aware labels.
Unavailable modes are explicit, never remapped silently. Settings remains reachable;
menu respects reduced motion, focus/TalkBack, cancellation and small windows.
A provider setting is not transmission consent; no text sent on long-press, app
resume, dictation finish or stale callback. Changing account/field/session invalidates
stale operations. Local-only remains fully usable without authentication.

Consumer-app handoff, background API job and local agent bridge are DISTINCT routes.
Verify official app/deep-link/intent contracts and exact destination before coding;
do not manufacture effort URL parameters or consumer chat-history integration.
Background processing must use a supported authenticated route selected by user,
with cost/privacy/retention disclosure, no automatic API or account fallback. No
key, login, network inference, installed-app launch or test message sent in this
block. Keep macOS second-shortcut parity in the major-upgrade plan.


## Fixed sizing/native checks and reference critique
The first implemented sizing choices are85/100/125/150% (Small/System/Large/
Extra large), independent key-label and Unicode-emoji preview fields. v1 legacy
settings load unchanged with System defaults; v2 writes both enum names, not
arbitrary floats. Sticker size is still OPEN until its actual browser exists; no
nonfunctional setting is exposed. Key-label max may saturate actual available
bounds; user settings do not alter hit/swipe geometry or output sequences. Android
SP conversion remains platform-owned, including nonlinear font scaling.

Generated reference-v1 preserves the intended black/charcoal/white material and
upper-left rim, but its ASDF row has a duplicated letter and its toolbar differs
from the supplied Gboard screenshots. Those are image-generation defects, NOT
new layout requirements. Only the material/reflection is selected for comparison.
The macro's bright bevel must be more restrained in the real keycaps; labels win.
No numerical design score is assigned to this concept image.

Native fixed discriminator uses an isolated debug UID, actual settings and actual
IME in an owned synthetic editor: q font changes with exact same rectangle, q
inserts unchanged, emoji picker actual cell/font size changes, selections persist.
Compact landscape at system font2 must fit actual TextView line bounds. A reviewer
found the old minimum growing with scale; selected floor stays8sp independent of
request, preserving geometric fallback rather than clipping. Capture both rejected
and selected native artifacts. Journals/original guest environment must be exclusive,
immutable and durable before test mutations; verify actual restored preferences/
IME/font, not only a success marker. These tests cannot qualify phone/TalkBack/all
sizes/narrow/one-hand/independent visual9.2 or all Gboard features.

### Primary design guidance consulted
Apple's official WWDC25 Meet Liquid Glass describes lensing/adaptivity, but also
foreground legibility and reduced transparency/contrast/motion adaptations. Lip
uses its own Android material and the user's MORE OPAQUE black preference, not
an Apple framework/API or translucent wallpaper blur. A JavaScript-only HIG page
and failed Markdown fetch were not treated as read evidence; the official session
transcript was fetched instead. No requirement to imitate Apple's whole style.
https://developer.apple.com/videos/play/wwdc2025/219/
