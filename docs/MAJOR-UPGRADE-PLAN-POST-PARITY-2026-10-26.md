# Lip major upgrade after the parity release

Scope version: lip-next-major-v2-translation-20261027. Authority: Jared's26October request
and explicit answer: **ship the current parity release and marketing site first,
then this major upgrade**. This extends, not replaces or weakens, the frozen
current-release plan and acceptance inventory. All gates below remain OPEN.

## Delivery order and authority

1. Finish the current local speech/English keyboard/screenshot parity inventory,
   independent9.2 gates, actual supported-editor/device evidence and rollback.
   Physical-phone testing is still held; unavailable evidence is unqualified.
2. Ship that qualified release, then publish its screenshot-led ChatGPT Site.
   No placeholder download buttons, private transcripts or unsupported superiority.
3. Implement the following major upgrade in coherent PRs. Batch related source,
   tests, docs and license changes; inspect CI scope/cost and current-head review
   findings before each push/merge. Preserve required checks and protections.
4. No additional full release until every feature below is accepted or has a
   concrete evidence-backed unworkable/deferred disposition. Then merge accepted
   PRs to origin/main and produce the marketing-ready major release/site refresh.

The user now authorizes scoped PR publication, merging qualified work to origin/main
and the described release/site publication at these gates. This does not authorize
billing changes, account/permission changes, weakened protections, speculative paid
API fallback, uploading private histories, or changing the phone/default keyboard.
A failed route does not make an entire feature unworkable: attempt a documented
alternative, preserve the requested outcome, and record what is lost. A major
concession goes to Jared rather than quietly disappearing into the back burner.

## Baseline release remains the first lane

The current app still lacks production neural glide/dictionary/composing integration
and several screenshot-visible reference surfaces; current checks do not prove full
parity or9.2. Latest authority,27October2026: **all translation is deferred to a
future release**, superseding the earlier request for basic translation in the
parity release. Basic GIF and clipboard functions remain required in the parity
release. Future translation must offer on-device processing and an optional
ChatGPT backend for English to Japanese, Chinese and Spanish. Neither backend is
a first-release blocker. No disabled control may be advertised as working parity;
the release/site must state the adopted translation deferral honestly.
Japanese/Mandarin keyboard entry remains a separate later language task; translation
into these languages is not the same as a Japanese/Mandarin keyboard.

## Feature blocks, PRs and acceptance

| Block | Outcome and implementation | Evidence needed before acceptance |
|---|---|---|
| Portable vocabulary | Versioned JSON accepted by Android and Mac; import preview/merge, explicit exclusions, canonical dedup, phrases/proper names, export/restore and migration. Local candidate extraction from explicitly selected Gmail/Outlook exports and WhatsApp SQLite snapshots; remove base-dictionary entries, never store message bodies/IDs in vocabulary. No cloud NLP. | Cross-platform golden files, unicode/case/phrase round trip, corrupt/oversize/version/rollback tests, words recognized by real spell/suggestion paths, reinstall/upgrade and selected Drive document-provider backup/restore. No private histories in Git or site. |
| Owned undo/redo | Tap toolbar Undo to expose adjacent Undo/Redo. Journal Lip-owned editing transactions and keep native host undo where available; coalesce meaningful typing/swipe/dictation operations; redo invalidated by new edit. Never replay into a different editor/caret/context. | Real-touch/button, long session, branching, selection/emoji/IME composition, process death/recovery and storage-full tests; zero password/incognito persistence or stale edits. No fixed entry-count cap, not literally infinite storage or arbitrary cross-app undo. |
| Translation (future release only) | English to Japanese, Chinese and Spanish; on-device after explicit model download, plus optional clearly labelled ChatGPT online backend. Live preview, bounded debounce, latest-result ownership and deliberate insertion. | Each language/backend separately must meet9.2. Local route needs offline proof and warmed p95 preview<=500ms for<=120characters on the physical target. Both need native-speaker quality/entity fidelity, stale-cursor/password/cancel tests and no UI stalls. ChatGPT also needs explicit cloud consent, verified supported API/auth/budget, network-failure handling and no silent fallback. Failures retain English and never send a message. |
| ChatGPT handoff | Android hold-mic then slide up into a ChatGPT-logo bubble, cancel by leaving/releasing outside; stop capture, local formatting, preview effort chooser with requested Instant/Medium/High/Extra High/Pro labels where actually supported. Configurable default. Mac distinct second hotkey routes completed dictation the same way, without keyboard UI or clipboard overwrite. | Official supported intent/URL/extension or verified exported component; user-account/session reuse in ChatGPT, exact payload/new-chat/manual-send evidence, absence/cancel/error/offline behavior and no accidental trigger. Never pretend effort was applied when receiver ignores it. Pro is a model/mode, not assumed interchangeable with API reasoning effort. |
| Clipboard archive (lower priority) | Opt-in local trailing archive, total stored payload<=100,000,000bytes; oldest captured items evicted first. Count UTF-8 text and copied media bytes, not characters. Oversize single item refused clearly; pinning must not exceed total cap. Background capture only when OS/default-IME access allows it. | FIFO/exact-boundary/multibyte/media/storage-full/corruption/restart tests; protected/incognito/sensitive-item exclusions, explicit clear/export controls and no hidden cloud sync. Automatic transcription insertion still never changes the clipboard. |
| GIF panel (lower priority) | Search/browse/preview and insert supported rich content; local favorites/cache, honest online-source consent if required. No silent cloud search text from a private field. | Provider/license/API feasibility, recipient MIME compatibility, permission lifetime, offline/cancel/cache bounds, source attribution and actual receiving-app evidence; no fake text-only insertion claiming a GIF was sent. |
| Site/release | Concise, screenshot-led, marketing-ready ChatGPT Site using frontend-design; Android keyboard and Mac speech shown honestly. Separate supported/live features from roadmap. | Real release-matched screenshots, responsive/mobile/keyboard/reduced-motion and link/download checks; independent visual/content9.2; private draft before final publication, no private-history content. |

### ChatGPT decision, not a premature promise

Default architecture: **no OpenAI login or tokens inside Lip**. Prefer an explicit
handoff to the user's installed ChatGPT app/session. A launcher shortcut is not
proof of an exported prompt/effort API. Official documentation checked so far
establishes reasoning controls in ChatGPT/API, but not an Android external intent
that guarantees a fresh chat, populated prompt and any requested effort. Capability
remains unverified, not declared impossible. Inspect official app intent/shortcut
metadata in an owned emulator and test only disposable text. No private endpoint,
cookie extraction, accessibility auto-send, invented URI parameters or paid API
fallback. If only text sharing/new-chat open works, distinguish it from one-action
send and route the exact limitation to Jared for a deferral/compromise decision.
An API-backed client is a different product/cost/auth path, not a hidden substitute.

### Translation release decision and optional ChatGPT backend

All translation, including basic translation, is future-release work by Jared's
latest explicit instruction. Offer On device and ChatGPT (online) as distinct
backend choices, not a hidden automatic fallback. Core dictation/typing remains
local. Selecting ChatGPT must clearly disclose that only the selected translation
text and necessary language instruction leave the device; never attach private
vocabulary/history, unrelated field context or secure/incognito content.

The supported backend/API and authentication mechanism are not yet verified.
Do not assume ChatGPT subscription access includes API usage, reuse account tokens,
invent an app intent that guarantees translation, or silently incur API charges.
Future setup needs an explicitly approved supported credential/account and visible
cost/usage controls. No login, API key, cloud call or billing change is added now.
Treat source text as data, validate bounded output, preserve cancellation/caret and
clipboard/no-send invariants, and keep online failures separate from local operation.
The same9.2 bar applies independently to every offered backend/language combination;
cloud availability does not substitute for translation quality or privacy evidence.

### On-device translation feasibility lead

Google's on-device ML Kit translation is a candidate, not yet selected/qualified.
Official Android API supports explicit model management and English/ja/zh/es.
Evaluate privacy/telemetry, terms, attribution, quality and offline behavior before
adoption; never infer9.2 translation quality from language coverage. Investigate a
portable local model if needed for Mac parity or inadequate quality. Model downloads
are user-visible; inference has no network fallback. Chinese script/variant handling
must be shown explicitly rather than advertising untested variants.

### Vocabulary privacy and portability

The portable artifact contains approved terms, language and weight only; optional
source is a coarse category, not message text, address lists or conversation IDs.
Secrets, password-like tokens, emails/URLs and unseen candidate content need review
before learning. Extract candidates locally, preview/denylist and merge only after
approval. No automatic Gmail/Outlook/WhatsApp-wide retrieval in this implementation
turn. Every real WhatsApp SQLite read must use the installed WhatsApp skill and an
approved read-only snapshot route. Google Drive backup is explicit file export/
restore through the user's selected provider, not an automatic private-data upload.
Portable JSON may be sensitive; show that warning and preserve informed plaintext export as an option.
Encrypted backup is an optional enhancement, not a prerequisite for the requested
portable JSON/selected-provider backup. Preserve both spelling/case and a deterministic identity.

## Fixed next-major9.2 rubric before scoring

Each feature is scored independently, without rounding9.19. Weights: intended-use
correctness30%, safety/privacy25%, reliability/recovery20%, interaction/accessibility
15%, efficiency/platform integration10%. Weighted total>=9.2, every dimension>=8.5,
no critical privacy/security/license failure. Site has separate visual/content9.2
gate, and current keyboard visual contract remains separate. Source/unit/emulator
results cannot substitute for physical/editor/native-speaker/receiving-app evidence.
Private reviewer scores; author receives concrete defects or scoped PASS only.

Named gate IDs: NEXT-VOCAB, NEXT-UNDO, NEXT-TRANSLATE-JA, NEXT-TRANSLATE-ZH,
NEXT-TRANSLATE-ES, NEXT-TRANSLATE-CHATGPT-JA, NEXT-TRANSLATE-CHATGPT-ZH,
NEXT-TRANSLATE-CHATGPT-ES, NEXT-HANDOFF-ANDROID, NEXT-HANDOFF-MAC,
NEXT-CLIPBOARD, NEXT-GIF, NEXT-SITE, NEXT-RELEASE. All OPEN; missing verifiers
fail closed. Record test corpora/oracles before model/provider tuning. No user-data
fixtures in public tests. Add negative controls and bind every acceptance to exact
source/artifact/editor/model identities. Do not publish an overall score averaging
away a weak target language or a broken handoff mode.

## Marketing design plan (before implementation)

Subject: a quiet, local keyboard and voice instrument, not a generic AI dashboard.
Audience: people replacing their everyday keyboard and dictation workflow.
Primary job: see the real product, understand local processing and download a
qualified platform build without reading a technical manifesto.

Updated27October2026 JST; original filename/scope ID retained for continuity.

Tokens: black #000000, key graphite #2C2C2E, pure ink-white #FFFFFF,
screenshot-toolbar gray #353537, restrained cool cyan #80CBC4, paper-white #F7FAFC
only for an optional light screenshot surround (not cream). Type: candidate
Atkinson Hyperlegible Next for readable text; one strong, generously spaced type
scale rather than a second decorative display face. Verify/license the actual font.

Concept A, selected: **the keyboard is the hero**. A generous real Android keyboard
image spans the opening; concise left-aligned headline and Mac dictation strip
explain the two platforms. Calm navigation, intentional spacing, not a grid of
identical SaaS cards. Screenshots carry the craft; no neon gradient decoration.

    Lip                       Android    Mac    Privacy
    Your words. On your device.
    [Android download] [Mac download]
    [large real keyboard capture, readable at actual size]
    [small real Mac dictation sequence]
    Local transcription / Familiar typing / Your vocabulary
    [three editorial screenshot-led sections, different widths]
    [clear support, release notes and limitations]

Alternative B rejected: abstract soundwave/AI-orb hero. It obscures the actual
keyboard and would be the generic design for unrelated AI products. Revised the
plan toward real device screenshots and ordinary words; spend visual boldness on
one product-scale image, not repetitive cards/all-caps labels/animated counters.
No claim such as faster/more accurate/better battery until the actual benchmark
qualifies it. No ChatGPT/OpenAI affiliation implied by a handoff button.

## Sources / evidence ledger

- Current authority and implementation baseline: loop/STATE.md and current major plan.
- Translation API: https://developers.google.com/ml-kit/language/translation/android
- Languages: https://developers.google.com/ml-kit/language/translation/translation-language-support
- Gboard translate interaction: https://support.google.com/gboard/answer/7421372?hl=en
- ChatGPT controls: https://learn.chatgpt.com/docs/web
- API reasoning (not an Android app intent contract): https://developers.openai.com/api/docs/guides/reasoning

Gboard reference APK exists in the owned Google Play emulator image, so inspect
that legitimate signed local reference instead of executing an unknown APK from
a download mirror. It is version15.1.08, not claimed current or identical to Jared's
phone. Only metadata/manifest/resource behavior is evidence; never copy proprietary
code/assets or patch Gboard. Reference binary and private evidence stay ignored.

## PR integration lane

Current parity release/site is the first coherent PR lane. Major-upgrade prototypes
stay out of that release's staged paths and APK behavior. After that release, use
reviewable feature PRs on a next-major integration branch (portable vocabulary+
owned edit journal; translation+explicit ChatGPT routing; clipboard+GIF; site/release
integration). Qualified integration merges do not authorize a premature main release.
Once every requested major feature is accepted or has an evidence-backed approved
unworkable/back-burner disposition, inspect the full integration candidate and merge
its final PR to origin/main. No protection changes, skipped checks or giant private
history payloads. Repository confirmed public: ihearttokyo/Lip, default branch main;
no existing PR for codex/lip-local-bark at inspection. No PR has been published yet.
Current Android CI still targets old engine inputs; repair it to test the actual
Bark/swipe build before triggering paid/remote checks, not by disabling validation.

Cloud handoff must be explicitly separated from local dictation in settings and
site claims. Use official ChatGPT brand assets under applicable guidelines; no
fabricated logo or affiliation. On Android, the long-press bubble must distinguish
a gesture from the current long-press Cancel action, with a tested cancel path and
no accidental external transfer. Existing default Fn insertion remains unchanged;
the new Mac route requires its own shortcut/capture destination ownership.

## Authoritative scope change,27October2026

Jared's response annotation explicitly moves basic translation out of the current
release and requests an optional ChatGPT translation backend in a future release.
This supersedes older baseline-translation statements, not the basic GIF/clipboard
requirements or release order. See RELEASE-SCOPE-ADDENDUM-2026-10-27.md. No test
failure motivated this deferral; no9.2 threshold was reduced or score awarded.


## Additive major-media/history request,27October2026

See MAJOR-MEDIA-HISTORY-2026-10-27.md for high-resolution WeChat artwork admission,
local sticker-pack settings/import and screenshot-led tray, and proposed opt-in
local/encrypted Google Drive typed/swiped history. Five additive OPEN gates retain
all existing9.2/floors/holds. No collection/upload enabled; first-release order and
translation deferral unchanged. This is the v3 additive scope, not a product pass.
