# Gboard feature inventory and Lip gaps

Jared now asks for Gboard features missing in Lip, independent surface sizing and
an optional agent toolbar action. This inventory preserves that ambition; rows are
not a waiver or a claim of exhaustive current Gboard discovery. Every newly
observed feature must be added with version/device/locale/source and test evidence.
Existing English-first and translation-future decisions remain until changed.

Existing legitimate SDK-image reference APK is reused, not downloaded twice:
com.google.android.inputmethod.latin15.1.08.726012951-preload-arm64-v8a,
81,462,534bytes; SHA25671c4cb3976e83fdf6241a432bef1f60706808f82a7ae645231b0005b3758a5c6.
It is not claimed latest or Jared's phone build. Original signature/static reports
remain in ignored validation/gboard-reference-20261026. No patch/code/art copying,
proprietary service call, private provider query, login or physical-phone access.
An APK cannot reveal all server-gated/region/locale/OEM functionality. Resource
names are leads, not behavior proof. Current PARITY-MATRIX has historical rows;
use current source/evidence, not old “missing” labels for now-implemented code.

| Family | Current Lip source | Missing or unqualified work |
|---|---|---|
| Tap/edit/action, Shift/Caps, symbols, numeric/private fields | Implemented; bounded fixtures | Broad editors/OEM/physical/TalkBack, corrections and host-owned composition edge cases |
| Neural glide, suggestions, autocorrect/dictionary, next words | Implemented; pinned licensed data/model and fixture checks | Human paired Gboard superiority, quality/energy, broad app next-word behavior, multilingual repertoire |
| Space-cursor/delete gestures | Implemented; bounded native fixtures | Full device/accessibility acceptance; extended text-edit/select-all/cut/selection tool surface |
| Layout/height/one-hand/material/haptics | Implemented | Independent key-label and emoji sizing in this increment; sticker size follows sticker runtime; continuous resize/floating/split/reposition/borders/photo/dynamic-theme options require explicit discovery and implementation |
| Toolbar | Fixed familiar seven positions | Pin/reorder/custom slots, last-completed-dictation→chosen-agent action, long-press provider/effort sheet |
| Unicode emoji/search/tones/recents | CLDR/OS-font implementation, bounded native checks | Fully qualified variants/gender navigation/recents policy, broader media parity; Emoji Kitchen/combinations distinct from Unicode; no copied Google artwork |
| GIFs and stickers |12licensed local GIFs; safe ZIP ingress backend; selected WeChat source local only | Broad discovery/catalog/search, sticker manifest/image/transaction/picker/import/manage/export/receiver; independent image sizing; WeChat rights/high-resolution qualification |
| Clipboard | Explicit copy/paste plus opted-in encrypted bounded text archive | Gboard current/full clipboard behavior, media/screenshots, batch operations,100MB enhanced archive/backup (major upgrade); no silent background collection |
| Dictation | Local Bark model/session/caret integration | Full Gboard voice surface/continuous dictation/voice edit commands/emoji/next-field commands; Pixel/device/locale-gated advanced functions need separate baseline evidence; never implicit Send |
| Suggestions/writing tools | Local English corrections/next words | Smart Compose/proofreading/rewrite breadth; local-first alternatives or explicitly opted-in supported routes, not silent cloud replication |
| Languages/handwriting/accessibility/physical keyboard | English-first; limited keyboard presentation | Japanese/Mandarin planned; Gboard broader language/layout/handwriting repertoire and shortcut surface remain inventory gaps, not claimed parity |
| Translation/search/services | Deferred by Jared | ALL translation future-only: on-device ENG→JA/ZH/ES plus optional ChatGPT backend. Server search/GIF/sticker services need separate opt-in/account/rights/privacy route |
| Sound/vibration/long-press/key popups/settings | Haptics respects OS; some long-press controls | Sound/volume/vibration strength/duration, popup/long-press delay and correction toggles to audit against reference; no claim from a setting name |

## Evidence and priority

First requested increment: independent key/emoji sizes, live preview, safe bounds,
legacy settings and failure fences, actual settings→IME geometry/font and emoji
reflow tests, compact-landscape/font2 discriminator. Sticker sizing stays OPEN until
actual sticker browser exists; do not add a nonfunctional control.
Next coherent blocks: media pack runtime and image sizing; configurable toolbar+
last-dictation/effort UI; provider transport only after supported route/auth/cost/
privacy admission. Other observed Gboard gaps enter the same acceptance register,
not a blanket “compatible” or “all done” claim. Current9.2 independent acceptance
and held intended-use gates remain unchanged.

Official sources inspected27October2026:
- https://support.google.com/gboard/answer/6102154?hl=en — theme/feedback and independent emoji slider; OS default follows font scale.
- https://support.google.com/gboard/answer/6380730?hl=en-GB — setup/languages/layout/settings.
- https://support.google.com/gboard/answer/7068415 — suggestions, corrections, personal dictionary and writing feature eligibility.
- https://support.google.com/gboard/answer/2811346 — glide typing.
- https://support.google.com/gboard/answer/11197787 — advanced voice availability and commands.
- https://support.google.com/gboard/answer/7421372?hl=en-sg — translation; not authority to undo future-only release decision.

A low-cost Luna research child performed bounded read-only source/help reconnaissance.
These are official feature descriptions plus source status, NOT tests of all listed
features on the pinned APK or held phone. Further baseline exploration remains OPEN.

## 28 October native counterexample (not accepted parity)

Owned sizing fixture, compact landscape at system font2, accidentally navigated to
GIF page when an obsolete test tap hit toolbar rather than the occluded host editor.
The retained screenshot `validation/keyboard-sizing-driver-20261028/cases/sizing-landscape-font2-v5/driver-38221844-4437-46a4-b82b-88765fd97bb2-failure.png`
shows clipped explanatory text and GIF captions. This is a genuine visual defect
outside the corrected test navigation: large-font GIF layout/reflow remains OPEN.
A passing key/emoji sizing task does not qualify GIF usability or general Gboard
parity. Preserve and repair it in the actual GIF feature lane.

### Bounded reflow repair,28October2026

The retained font2 caption/status clipping now has source-matched actual isolated
portrait and compact-landscape/font2 browse/layout passes: full intrinsic text,
physical scrolling4/5steps reveals all12local titles and attribution, ABC restores
letters and exactlyq insertion. Source and pixels independently reviewed. See
`docs/GIF-LAYOUT-RESULT-2026-10-28.md` and frozen GIF reflow evidence. This closes
ONLY that reproduced text/layout defect in those tested configurations, not general
GIF/Gboard parity, remote catalog search, attachment/receiver/TalkBack/physical/
visual9.2 or constrained-window qualification. Intermediate zero-scroll false passes
remain explicitly unqualified after a visibility-verifier repair.
