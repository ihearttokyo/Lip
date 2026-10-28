# Local sticker packs — production workflow contract

## Intended user path

**Lip Settings → Manage local sticker packs**, **Keyboard preferences → Manage
local sticker packs**, or **keyboard menu → Local sticker packs → Manage packs**.
These are ordinary build features, not a fixture flag. The older strict synthetic
transport remains isolated; production uses a separate receiver-bound route.

The manager uses the established pure-white-on-black/graphite native material:
one clear primary action, readable wrapping labels, minimum48dp controls and
scrolling instead of clipping. Pack artwork supplies the visual focus; no borrowed
WeChat assets, unsolicited generation, cosmetic animation or template decoration.

1. Choose a **dev.lip.sticker-pack v1 ZIP** using the system picker. Only the
   selected regular, seekable content document is read. No URI grant is persisted,
   no URL/manifest is fetched, and no app/WhatsApp database or APK is inspected.
2. Validate bounded ZIP metadata and each PNG/WebP; decode only in the isolated
   service; review source declarations, item count/dimensions and static previews.
   Rights remain **UNVERIFIED**. Explicitly confirm local import before storage.
3. Browse saved packs in the keyboard. Four preview sizes change the picker only,
   never original resolution or recipient image size. Previews are static even if
   the validated original is animated.
4. Explicitly tap one original image when the current ordinary editor advertises
   its MIME. The production route must verify actual InputBinding UID/package,
   connection/editor/caret/window/library epoch, MIME, signing facts and fresh
   unlocked/visible/non-private/non-incognito/non-composing state. A bounded
   selected-asset-only read grant is not library access. **Lip does not press
   Send. The receiving app controls its own behavior, including effects it may
   perform when it receives content.** No automatic attachment or blind retry.
5. Export one inspected pack: plaintext ZIP warning → create document → visible
   **Export selected original ZIP** confirmation. Only an empty regular seekable
   readable/writable FD supports export. Nontruncating `rw`, lock, bounded write,
   force and complete hash readback are required. Existing/nonregular/pipe or
   write-only destinations refuse before writing. Unknown outcomes preserve the
   possibly partial destination and original; inspect before a new deliberate
   attempt. No automatic Drive backup/cloud sync, deletion or overwrite.
6. **Remove from keyboard** requires confirmation and moves the exact commit into
   a recoverable bin using verified-root, same-dirfd kernel NOREPLACE rename.
   The single Unix stat snapshot's fileKey must match the original private root;
   native open/fstat must match that snapshot's dev/inode, preventing root-path
   substitution from retargeting the mutation. **Restore** also
   requires confirmation and never overwrites a competing destination. No permanent
   delete, recursive cleanup or quota bypass. Interrupted/unknown rename outcomes
   require refreshing both lists; no automatic replay.

## Frozen safety and capacity criteria

Original/archive/member hashes and all source metadata remain unchanged. Active,
removed and interrupted directories share the established256MiB/128-directory
quota; removing a pack does not recover capacity. ZIP16MiB, expanded64MiB,
member8MiB,120stickers and the existing image/frame/pixel/decoder limits remain
unchanged. Corrupt originals stay recoverable and fail closed; no encoded decoder
runs in the IME/manager as a fallback.

Process-local library epochs begin before import/relocation preparation, block new
read/request admission during writers, and change again after actual completion.
They invalidate old tiles and pending originals, even across remove→restore ABA.
Callbacks/IO are outside the epoch lock. Original-image tiles capture the exact
editor context at physical DOWN (or explicit key/accessibility activation), never
mint a request from a late onClick. Unsplit multi-pointer/CANCEL/close invalidates
contact identities; an entire cancellation stream cannot restore an old contact.
Production checks that contact again after external context reads and cleanup,
before creating a pending operation. Selection/composition changes cancel both
old fixture and production pending requests, including same-caret composition ABA.
Cancellation retains resource admission
until real work drains. Already-dispatched recipient bytes cannot be recalled by
removing a pack; temporary-grant revocation is not an unsend claim.

## Evidence boundaries

Focused JVM tests cover revision/ordering and synthetic store management when the
host actually supports SecureDirectoryStream. Unsupported-host skips are NOT
storage passes. Injected host filesystem movers are NOT kernel NOREPLACE/root-dirfd proof.
The real native route must refuse a replaced root by matching the single stat
snapshot to the original opaque fileKey, then matching the opened directory FD's
dev/inode/mode before any relative rename. No private SDS FD API or pathname-child
fallback is used.
Existing fixture decode/import/browser/transport receipts remain scoped evidence,
not automatic production promotion or an overall9.2 score.

Parent-owned actual intended-use checks still must exercise normal app identity,
real SAF/local provider import/cancel/confirm/export/readback, original hash and
source preservation, bin/restore across process recreation, four preview sizes,
and a separate owned rich-content receiver with actual permissions/MIME/hash.
Private/no-learning/composition/lock/editor/caret/window/UID/MIME departure,
multicontact/stale contact, blocked provider/reader and uncertain-outcome controls
must preserve exact ownership and forbid retries/retargeting. No installed Mac,
physical phone, real WhatsApp/private history or cloud/provider login is required
or authorized by these tests.

Not delivered by promotion: third-party/WhatsApp contents.json adapters, all
.wastickers formats, search/favorites/custom order/renamed labels, high-resolution
WeChat artwork, redistribution clearance, arbitrary-app behavior, TalkBack/human
visual acceptance or full Gboard parity. They remain explicit follow-up scope.

## Current validation — 29 October 2026

The integrated ARM64 debug source builds offline with unit tests, lint and both
APKs. The repaired candidate reports758 JUnit cases:740 executed,18 skipped,
zero failures/errors. Five new store-semantic cases are among the macOS skips
because that host lacks the required SecureDirectoryStream; they are not JVM
storage passes.

On the separate owned API36 ARM64 emulator, the packaged production JNI and
actual Android Unix attributes/directory streams passed seven synthetic storage
controls: exact archive/original Remove/Restore, canceled/competing target refusal,
uncertain post-rename reconciliation without replay, shared retained quota,
defensive export snapshot, substituted-root refusal, and native NOREPLACE plus
round-trip original preservation. Fresh synthetic directories were retained.
This establishes those storage/identity controls only, not the system picker,
image decoder, recipient grant/read, gesture UI, arbitrary editor or physical
phone. Bounded independent static review passed after the rooted-rename,
contact/composition ownership, grant-publication and writer-cleanup repairs.

The separate test-APK SAF provider/ordinary rich-content receiver compiles and
its synthetic PNG/WebP document seed runs locally. Seed execution alone is only
fixture readiness. The subsequent actual workflow controls are listed below;
the remaining matrix still prevents full feature qualification. No full release
or overall9.2 score is established.

The same owned emulator now completes the ordinary Settings path through actual
SAF selection, isolated validation, UNVERIFIED-rights review and explicit import.
A separate test-APK document provider receives the explicitly confirmed export;
its independent readback matches all1,540 original ZIP bytes by SHA-256 and its
source remains unchanged. Real Remove confirmation, app-process recreation and
Restore confirmation retain the same commit and exact original archive hash.

Actual Tools → Local sticker packs → pack → single-contact original requests
reach a separate ordinary editor UID for both PNG (370bytes) and WebP (582bytes),
with exact expected MIME and original SHA-256. Each receipt observes one
commitContent call and one permission request, unchanged text, zero editor actions
and zero Enter events. A later second-open probe refuses the consumed PNG URI;
this alone is not expiry, foreign-UID or revocation-completion proof. The receiving
fixture performs no onward send; arbitrary receiving-app behavior is not proven.
Actual password and no-learning editors disable the sticker menu and refuse its
tap with zero attachment calls. An established composition at0..9 remains intact
with the menu disabled and zero calls. A text/plain-only editor permits browsing
but its preview tap causes zero calls. An explicit false receiver sees one call,
no read and no automatic retry/fallback after settling; text, Enter and editor
action counts remain unchanged. These are specific controls, not proof of all
private-field IPC or every cancellation race. Stale/multicontact/blocked-reader,
four-size intended-use, accessibility and physical/editor matrix remain open.
