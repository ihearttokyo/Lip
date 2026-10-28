# Portable learned-word format, version1 candidate

The Android source now includes an explicit JSON import/preview/merge and export
path in Dictionary. This is not a trained model, automatic history scan, macOS
importer or9.2 acceptance. No private histories were read during implementation.
The example contains synthetic/public-language test terms only.

`schema-v1.json` documents structure. The reference runtime codec additionally
requires valid UTF-8, Unicode16.0.0 NFC terms/default casefold identity, no duplicate
JSON properties or normalized entries, finite integer weights, bounded file/merge
work and no control/bidi characters. Structural JSON Schema alone is insufficient.
A term can be a proper name or short phrase. Preserve approved display spelling;
identity is language + NFC(full Unicode casefold(term)). Android and Swift adapters
must match shared Unicode golden vectors before claiming cross-platform parity.
Do not substitute locale-sensitive lowercasing for full casefold.

Limits:16MiB input/output,100,000 unique entries,128codepoints and512UTF-8bytes
per term, language en/ja/zh/es/und, weight1..1,000,000. The optional-provenance
concept is represented by a required coarse source category: manual/gmail/outlook/
whatsapp/import. It must never be a message body, sender list or conversation ID.
Unknown fields and versions are refused rather than silently discarded.

The codec rejects email/URL/control-like terms but **is not a secret/PII detector**.
A password-looking alphabetic token could still be a valid word: local candidate
review and exclusions are mandatory before learning or cloud backup. JSON exports
are plaintext and may be sensitive. No Drive upload happens in this tool. Future
apps must show export/privacy warnings and support selected-provider backup/restore;
encrypted portable backup is optional, not a prerequisite for informed plaintext
export/backup.

The word list changes dictionary acceptance/suggestions where wired, not neural
model weights. Speech recognition biasing must only be claimed if the selected
native engine actually supports it. Future adapters need import preview, atomic
merge/rollback, spell/suggestion integration and upgrade/restore tests.

## Local reference tool

From the repository root:

    python3 -m tools.learned_words verify docs/contracts/learned-words/example-v1.json
    python3 -m tools.learned_words merge approved.json incoming.json --output new-backup.json
    python3 -m unittest discover -s tests/learned_words -v

Merge preserves existing spelling/source and uses maximum weight, so repeated
imports do not inflate counts. The tool only reads the exact two selected files.
It never opens Gmail/Outlook/WhatsApp or sends data anywhere. New output is written
with owner-only permissions and exclusive same-directory publication; existing
backups/symlinks are never overwritten. A provider lacking hard links is refused,
not claimed compatible. Android source uses an explicitly selected DocumentProvider; the CLI itself is
not proof of Android or Google Drive support. macOS UI integration and physical
Google Drive backup/restore qualification remain open.

## Pinned Unicode compatibility

Version1 candidate now carries `unicode_version: "16.0.0"`. The reference tool
refuses a runtime whose Unicode tables are not exactly16.0.0 BEFORE interpreting
terms or modifying anything. This covers classification, trimming, normalization
and full casefold, not just a lowercase operation. Verified CPython reference:
Homebrew Python3.14/Unicode16. The available system Python/Unicode13 is deliberately
refused cleanly; it must not silently drop a newer proper-name character.

Android/Mac adapters must provide this same pinned behavior (bundled fixed tables
or a verified compatible implementation), or refuse with an upgrade path while
preserving the original portable file. No adapter compatibility has been claimed.
A future Unicode contract uses an explicit version and previewed migration, not
reinterpretation of existing spellings/identities. Pre-review prototype files
without this property were never released; they are refused rather than guessed.
The pre-score schema revision is recorded in the next-major lock history.

## Android consumer scope

Import a prepared **v1 JSON** file from Dictionary; inspect the preview before
choosing Merge. Import does not open email, WhatsApp, contacts or other histories.
The original selected file and existing manual dictionary remain unchanged.
Portable entries use a dedicated Keystore-authenticated encrypted atomic store,
excluded from automatic backup. Unsupported Unicode runtimes refuse rather than
reinterpret a file. Reimporting does not inflate weights or duplicate identities.

English/undetermined A–Z words with optional apostrophes, up to48characters, feed
actual typed suggestions and autocorrect protection. The personal prefix index
supports the contract's100,000 entries without scanning the entire personal list
on every keystroke. Other approved entries are retained for export, but phrases,
other scripts and imported weights are not yet supported by the typing ranker.
This does **not** add personal terms to neural swipe decoding or Bark recognition.
If portable storage cannot be read, base/manual suggestions remain available but
automatic correction is disabled until personal protection is available again.
Returning to the keyboard reloads vocabulary; protected/no-learning fields do not
schedule personal-vocabulary loading and never receive stale suggestion results.

Export is deliberately initiated and produces **plaintext JSON** at the selected
provider. It can contain sensitive names or secret-looking words; this format is
not a PII detector. It does not silently upload/synchronize, promise encrypted
cloud storage, or establish Google Drive compatibility. The UI distinguishes the
portable list from the retained legacy manual dictionary. Keep a private backup;
preview changes before merging a restore. Local JVM parser/merge tests are not
real Android ICU, DocumentProvider, Keystore, editor or physical-device evidence.
