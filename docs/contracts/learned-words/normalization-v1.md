# Version1 normalization contract

Normative Unicode tables:16.0.0, including general categories, whitespace trim,
NFC and Default Case Folding (full, non-Turkic, locale-independent). Preserve
approved display spelling after outer whitespace trim and NFC. Identity is the
pair(language,NFC(full-casefold(display-term))). Internal spaces are preserved.
Category C codepoints are refused; at least one category L letter is required.
Do not classify characters using an older OS table and then claim this contract.

Schema JSON is structural; normalization and byte/work/secret-review constraints
also apply. Unknown/mismatched Unicode contract must refuse before mutation and
preserve the original artifact. Future migrations require new explicit metadata,
a preview of changed identities, collision handling and user-approved atomic merge.
No current phone/Mac adapter compatibility is asserted by the Python reference.
