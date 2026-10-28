# Portable vocabulary — macOS implementation increment

This continues the frozen shared vocabulary requirement and v1 format, not a new
or narrowed definition of product completion. The full9.2 release rubrics,
held actual-app/editor gates and original benchmark remain unchanged. No source-
only numeric score is assigned. Source publication is not a new installer.

## Consumer

Settings opens a vocabulary window: select one prepared regular JSON file,
review first20 terms/counts and the PII/inactive-language disclosure, then choose
Merge. Background preparation and a one-use admission lease fence close/cancel
and stale callbacks. The latest encrypted store is reread under its lock at merge;
existing spelling/source wins, weights use max, and original files stay unchanged.

A separate device-only, nonsynchronizing Keychain key protects the dedicated
AES-GCM/private/no-backup store. No credential/history key or old archive is reused.
Missing store reads do not touch Keychain. Corrupt/lost-key data is refused and
preserved. Tests use a temporary root and memory key, not the real Keychain.

Review text views consume supported English terms via document-private ignored
word lists. Automatic spelling correction, grammar and replacement are disabled.
No global dictionary learning, other-app mutation, clipboard change or transcript
rewrite occurs. This does not provide unsupported speech-model hints or personal
neural-swipe decoding. An unreadable vocabulary preserves transcript/previously
approved local spelling acceptance rather than inventing replacement content.

Export is explicit **plaintext**, to an exclusive-new selected file. Existing
files/symlinks are not overwritten. A failed admitted export can leave a partial
new file; no automatic retry, deletion, upload or sync occurs. Drive and other
providers need independent actual round trips before qualification.

## Fixed Unicode16 contract; rejected hypothesis preserved

The actual host SDK/runtime ICU reports Unicode17. That route was rejected,
not relabelled as16 or made into an always-refusing importer. Instead, selected
Unicode16 tables are derived reproducibly from resident CPython3.14.6/UCD16,
version/hash pinned and bundled with original Unicode/CPython notices. No runtime,
model or new executable dependency was downloaded. See
`desktop/Sources/LipLocal/VocabularyData/PROVENANCE.json` and
`tools/learned_words/generate_unicode16.py` (read-only default).

Normalization performs recursive canonical decomposition, stable combining-class
ordering, composition blocking/exclusions and algorithmic Hangul. Case folding
and L/C categories use the same pinned tables, never locale lowercasing/OS17.
Strict UTF8/JSON/scalar/schema/duplicate/size/work gates match Android/Python v1.
A rejected Foundation Data sampled-hash implementation caused pathological shared-
prefix behavior; exact identities now hash all bytes. The original100,000-entry
controls were retained and passed, not reduced to hide the failure.

## Recorded evidence and open qualification

- Actual ARM64/macOS26 SwiftPM app compile passes with the bundled resource.
- Bundled synthetic CLI:27,531 independent CPython16 NFC/fullfold cases and55,146
  codec/normalization controls pass, including strict parsing and100k boundaries.
  This is format/algorithm evidence, not recognition or spelling accuracy.
- Actual Swift CryptoKit/store/regular-file tests and AppKit source typecheck pass
  using only memory keys and owned temporary files. Real Keychain, GUI, spelling
  service and user/editor behavior were not run.
- The prior actual Android synthetic Downloads export was decoded/re-encoded by
  the real pinned Mac codec:7 entries/metadata and exact output bytes match. The
  independent Python16 reference validates it. This is interchange evidence,
  not an installed-app/Drive/backup/restore acceptance claim.
- Bounded independent source review passed; full product9.2 remains unqualified.

Next requirements remain: actual Mac UI/Keychain/private-spell/editor evidence,
Android future-Unicode adapter, portable/manual combined backup, history-export
candidate extraction, personal swipe integration and provider round trips. No
physical-phone/installed-Mac test hold, permission or release gate was lifted.
