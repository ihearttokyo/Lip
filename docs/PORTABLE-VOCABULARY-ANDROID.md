# Portable vocabulary — Android implementation increment

The frozen next-major requirement remains: portable approved vocabulary, shared
macOS/Android behavior, actual spell/suggestion use, backup/restore preserving
metadata, and no private-history or cloud access without authorization. This
increment implements the Android source consumer, not the whole requirement.
The existing full-product9.2 quality gates and held physical evidence are unchanged.

## Prospective acceptance checks

- Exact v1 Unicode16 NFC/full-casefold identities and shared golden vectors; strict
  UTF-8/JSON, duplicate/schema/version/limit rejection, safe content-free errors.
- Preview and deliberate merge only; preserve existing approved spelling/source,
  max weights, reimport idempotency; preserve selected originals and manual words.
- Dedicated authenticated encrypted atomic storage, no automatic backup; corrupt,
  canceled, stale-UI or failed writes must not replace previous approved data.
- Deliberate selected-document plaintext export with privacy disclosure; no cloud
  credentials, hidden upload, private mailbox/database extraction or provider claim.
- Real typed suggestions/protection beyond the old200-word cap; bounded indexed
  prefix lookup; reload after import/settings; no stale/private-field suggestion.

## Evidence boundaries

Synthetic JVM tests can qualify parser/merge/index decisions only. Actual Android
ICU/golden vectors, Keystore persistence/restart, SAF cancel/failure/restore,
keyboard/editor tasks and physical-phone operation require their own matching
candidate evidence. Missing evidence is **UNQUALIFIED**, never a simulated pass.
No source-only numeric score is assigned. No release is authorized by this file.

Open follow-on scope: macOS import/export and real consumer, learned-term neural
swipe integration, approved history-export candidate extraction, manual-dictionary
migration/combined backup, actual Google Drive round trips, and full independent
intended-use acceptance. Neither speech engine accepts dictionary hints today.

## Recorded scoped evidence — 29 October

- Local Android aggregate:651JUnit cases,638executed/13skipped, zero errors or
  failures; lint, full ARM64 native build, debug app/test packaging passed.
- Fresh isolated API36 ARM64 emulator: real ICU reported16.0.0;19 synthetic
  normalization/merge/Keystore-store/retention/cancel assertions passed.
- Actual app → Android Downloads picker → five-term preview → deliberate merge
  produced seven stored terms (including two component fixtures). Actual
  plaintext Downloads export contained those seven terms/metadata and was
  accepted by the independent Python Unicode16 reference codec. The original
  selected input retained its exactSHA256. No Google Drive was accessed.
- Those runtime checks bind to the transitional unbundled0.007/50004 fixture
  artifact from feature commit`f1f1cbe`, before the distinct feature reservation
  advances current metadata to **v0.008/50005**. They are component/local-SAF
  evidence, not exactv0.008 installed-app, physical-phone, process-restart,
  arbitrary-provider/editor, macOS, neural-swipe or full9.2 release acceptance.
- Bounded independent source review found and repaired portable-read failure
  dropping base suggestions; ordinary suggestions now survive while automatic
  correction is fenced off until imported-term protection is available.

Only the newly owned headless emulator was stopped afterward. The pre-existing
emulator and physical phone were untouched. No installer/release was published.
