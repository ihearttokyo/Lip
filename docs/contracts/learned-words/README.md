# Portable learned-word format, version1 candidate

This is a future-major-release foundation, not an enabled Android/Mac importer,
a trained model, an automatic history scan, or9.2 acceptance. No private histories
were read. The example contains synthetic/public-language test terms only.

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
not claimed compatible. Android DocumentProvider and Swift UI integration remain
future work; the CLI is not proof of Google Drive support.

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
