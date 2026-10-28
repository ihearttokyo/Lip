# Next-major addition: rich emoji, sticker packs and optional history

Authority: Jared's27October2026 request and supplied207325.jpg. This is an additive
next-major plan, NOT implemented/installed behavior. Parity release and marketing
Site still come first; all translation remains future-only. Existing9.2 threshold,
8.5 dimension floors, independent review and physical-phone hold remain unchanged.
No existing accepted or failing requirement is waived by this addition.

## 1. High-resolution WeChat emoji

Keep ordinary Unicode emoji insertion and rich image emoji distinct. Lip can draw
an image in its picker but cannot force another app to render Unicode using that
artwork. WeChat-specific names/tokens are not a universal interoperable emoji code.
Rich images require a receiving app's supported content route; never silently send
bracketed text or a low-resolution substitute and call it the requested emoji.

Target original high-resolution static/animated assets with sharp small previews,
transparent edges, search aliases, accessible names and explicit preview before
insertion. Preserve native source resolution; no claim that upscaling restores
missing detail. Cache bounded thumbnails; decode/animation work off the IME thread.

**Asset admission gate OPEN:** no redistributable WeChat artwork collection was
established by this investigation. The official service-terms URL could not be
retrieved. Public availability/GitHub hosting is not permission to bundle artwork.
Obtain exact assets plus applicable permission/license from Tencent/rightsholder,
or supported authorized user imports. Do not extract an APK, scrape/private-API
fetch, redistribute paid packs, or claim user ownership clears third-party rights.
An original/openly licensed alternative needs Jared's approval as a substitution;
it does not satisfy the specifically requested WeChat artwork gate.

## 2. Sticker packs: settings and keyboard

Settings > Stickers & emoji > **Import sticker pack** opens the system file picker.
This is local import, NOT upload to our server or to WhatsApp. Preview pack name,
author, provenance, item count, animation, source dimensions and storage size;
confirm before installing to Lip. Support safe ZIP containing a versioned JSON
manifest and static/animated WebP; PNG static source conversion may be offered as a
separate, disclosed step. Begin with the official WhatsApp contents.json shape as
an interoperability adapter, not a promise to read every third-party .wastickers
format. Check actual samples before declaring any format supported. Never execute
or install a sticker-pack APK, fetch manifest URLs, or read WhatsApp private storage.

Screenshot-derived interaction (not copied artwork): top search and media tabs;
large sticker grid; bottom horizontally scrolling installed-pack tray with recents,
favorites and an add/manage-pack control. Settings permits preview, reorder, rename
user labels, export selected packs and remove with confirmation. Use the existing
black/dark-gray/pure-white/Liquid Glass material. The screenshot's clipped Create
sticker label is NOT a desirable behavior. AI creation is not requested by this
image and must not become an unsolicited cloud service or dependency.

Pack import must stage transactionally before activation: reject absolute/traversal
paths, symlinks, duplicate/normalized collisions, encrypted/nested archives, unknown
manifest versions, external references, forged MIME/dimensions and decompression
bombs. Bound compressed bytes, cumulative expanded bytes, item count, dimensions,
frame count, duration and decoded working-set before decode; cancellation, low disk,
corruption and process death leave installed packs intact. Freeze measured limits
before product tests, never weaken them to fit a failing pack. Hash payloads and
preserve attribution/license evidence. A pack's text/manifest is untrusted data.

Android: reuse the owned rich-content provider/commitContent seam with exact MIME
negotiation and per-item temporary grants. Check editor/connection/selection and
pack generation before dispatch; one insertion attempt, no retry/clipboard/Send.
A successful dispatch boolean is not proof a recipient displayed the image. Test
actual editor preview, deferred URI reads, refusal, editor switch and deletion while
content is leased. Unsupported hosts show an honest error. An explicit share/export
or **Add to WhatsApp** action is separate from keyboard insertion and requires
verified official supported integration; never automate WhatsApp UI or auto-send.
Lip Mac is not currently a system-wide keyboard/IME; portable pack management/export
may share the format but must
not claim global rich-image insertion where unsupported.

## 3. Typed/swiped history: feasibility and recommended design

**Recommendation: Off by default, then Local only or Local + encrypted Google Drive
sync.** Google Drive is the preferred cross-platform candidate, not an account
connection, upload approval or completed integration. The Drive appDataFolder and
app-specific OAuth scope offer app-scoped storage; they do NOT provide client-side
end-to-end encryption or solve key recovery. Do not use a third-party sync folder
for plaintext databases. No backend payment/account/API use is authorized now.

Offer history as a transparent activity journal. Entry granularity is NOT yet
approved: per-character commits can retain typos and secrets subsequently erased.
Before capture implementation, freeze journal coalescing/finalization boundaries,
Backspace/correction/replacement behavior and whether deleted drafts are retained.
Consent must explicitly describe which transient/corrected/deleted text persists;
do not call the design non-keystroke-surveillance until those semantics justify it.
No collector may be enabled before this decision and privacy review. Proposed scope:
record only future Lip-owned committed text (typed, accepted swipe, optional
separately selected dictation) after the setting is enabled. Acknowledge dispatch
versus actual host receipt and refuse capture where receipt cannot be established;
never label a prediction/preedit/cancelled gesture a committed message. Entries are
NOT proof a message was sent, or the full final document after edits in another app.
No retroactive recovery, background Accessibility scraping, clipboard collection,
raw swipe paths, key timing, microphone audio or other keyboards' text. Mac only
records opted-in Lip dictation insertions, not all system typing. Failed/ambiguous
insertion must not create a falsely confirmed entry. Keep this distinct from the
learned-word dictionary, ephemeral undo journal and clipboard archive; enabling
one never enables another. Training/personalization is separate explicit consent.

Hard exclusions: passwords (including visible/web/numeric password variants),
incognito and no-personalized-learning fields, unsupported/unknown field policy,
user-excluded apps and paused sessions. Field flags are imperfect; **do not promise
that arbitrary secrets in ordinary text fields can be automatically recognized**.
Provide a conspicuous recording state and quick Pause, app allowlist, optional
session-only mode, per-entry delete, export preview, configurable byte/age retention
and storage-full behavior. Opt-in long-term retention may retain until a user-set
limit; no claim of literally unlimited storage. Defaults/limits fixed in the privacy
review before implementation. Metadata minimized; do not persist host text beyond
the committed Lip transaction or labels/package names unless separately necessary
and disclosed. Search/index remains local and inside the protected storage boundary.
No content logs, analytics, crash-report payloads or notification previews.

Local storage: vetted authenticated encryption, device key protected by Android
Keystore/macOS Keychain, OS auto-backup exclusion for plaintext/key-bearing caches,
explicit lock/export/recovery behavior. Cloud: client-side authenticated encryption
before upload, per-vault keys, unique nonces, authenticated schema/device/sequence,
no key in the same Drive storage, deliberate recovery-key/device-pairing ceremony.
Use vetted primitives/libraries, not custom crypto. Explain loss of recovery key;
account authentication alone must not decrypt the archive. Provider still sees
traffic sizes/times. Credential storage and OAuth use minimum app-specific scope.

Sync as append-only bounded chunks plus versioned manifest, stable record/device
IDs and deduplication, authenticated tombstones and explicit conflict handling.
Deletion/pause/consent revocation supersede queued/in-flight uploads by durable
incarnation and erase fences. Restored/offline devices cannot resurrect deleted
entries; freeze tombstone/epoch retention and offline rejoin protocol before tests.
Tombstones alone are NOT erasure: old chunks remain decryptable. Before sync
implementation, freeze measured active-vault compaction/object-deletion deadlines,
per-record or bounded-segment key-erasure/rekey behavior, purge manifests and
acknowledged-device propagation bounds. Physically remove superseded active objects
and retire old decryption keys on enrolled devices; reject replay/restore that
omits tombstones or rolls back the authenticated purge epoch. Exact deletion and
offline-rejoin timing guarantees remain an OPEN privacy-contract prerequisite, not
a claimed capability. Never retain decryptable active chunks indefinitely behind
tombstones or present provider deletion as proof of erased provider backups.
Deletion propagates to enrolled devices on next successful sync; remote backups,
exports and disconnected copies cannot be promised instantly erased. Account
switch, token revocation, provider quota, partial transfer, clock skew, corruption,
reinstall and lost-key behavior all require destructive-test fixtures, not real
private content. No automatic paid fallback, content upload or account login now.

## Sequencing and acceptance

1. Finish current parity release + site under its existing gates. These additions
   do not add collection or incomplete settings to the current APK.
2. Implement validated local sticker-pack import/management and receiver tests;
   use original/licensed fixture art. Obtain WeChat assets/rights in parallel when
   possible; keep exact requested artwork gate OPEN if unavailable.
3. Independent privacy/threat-model review of history before implementation, then
   local journal with synthetic data, then opt-in encrypted Drive sync and recovery.
   Considering history is not consent to activate capture or upload Jared's text.
4. Integrate through scoped next-major PRs. Keep all added gates OPEN until actual
   intended-use evidence; no release until accepted or Jared explicitly approves an
   evidence-backed deferral. No self-awarded9.2 from plans, screenshots or unit tests.

New gates in NEXT-MAJOR-ACCEPTANCE.json:
- NEXT-WECHAT-ASSETS: exact licensed source, high-resolution render/search; no
  substituted art or false cross-app Unicode rendering claims.
- NEXT-STICKER-PACKS: actual settings import/manage/export and screenshot-led tray,
  malformed archive/decode/storage/cancel tests and independent accessibility/render.
- NEXT-STICKER-RECEIVE: actual supported receiving apps, leases/MIME/refusal/no-send/
  clipboard-unchanged; WhatsApp-specific install separate and explicitly verified.
- NEXT-HISTORY-LOCAL: approved threat model, explicit consent/exclusions/capture
  provenance/retention/encryption/deletion/restart/export and cross-feature isolation.
- NEXT-HISTORY-SYNC: approved key recovery + encrypted Drive protocol, genuine
  two-device offline/rejoin/delete/revoke/account-switch/restore/quotas evidence.
All inherit9.2 without rounding and existing floors; a material privacy, asset-rights
or integrity defect blocks acceptance regardless of an aggregate score. History
remains a design proposal until privacy review; no capture activation assumed.

## Evidence, consulted27October2026

- User207325.jpg SHA256 cd27023eaa3c4adfcdd2c94368b17c5e49682d5bedd0f09742269fe2a2370013;
  visual reference only. No WhatsApp app/DB access, copyrighted sticker extraction,
  image upload or redistribution performed.
- Official WhatsApp sample/specification:
  https://github.com/WhatsApp/stickers/blob/main/Android/README.md
  Compatibility source, NOT a license to all artwork or evidence of Lip integration.
- Official Android rich-content contract:
  https://developer.android.com/develop/ui/views/touch-and-input/image-keyboard
- Official Android field policy:
  https://developer.android.com/reference/android/view/inputmethod/EditorInfo
- Drive app storage/scope:
  https://developers.google.com/workspace/drive/api/guides/appdata
- WeChat service terms URL attempted but unavailable to browser:
  https://www.wechat.com/en/service_terms.html
  No admitted collection/license established; do not infer blanket prohibition or
  permission from this failed retrieval.
