# Offline emoji metadata

Unicode CLDR49 English annotations and derived annotations, pinned at
96e54b273d2da4ec6fbad350ba19ff23fa24a588, ©1991–2026 Unicode, Inc.;
Unicode Emoji17.0 keyboard/display test data, ©2025 Unicode, Inc.
Data: Unicode License V3 (SPDX Unicode-3.0). Exact upstream notices and full
permission/warranty terms retained in source/UNICODE-LICENSE.txt and source/CLDR-LICENSE.txt.
The latter separately restricts the LDML standard publication: NO LDML specification
text is reproduced or incorporated in this catalog. Only annotation DATA is used.

Derivative english.tsv joins English names/keywords to the exact fully-qualified
Unicode17 sequences (including variation selectors). It omits component, minimally-
qualified and unqualified rows. No emoji artwork, proprietary Gboard assets or font
is copied. The app uses device-system emoji fonts, filtering unsupported sequences;
metadata inclusion is not proof of glyph rendering or keyboard usability on a device.

Sources, hashes and bounded transfer accounting: source-manifest.json.
Derivative identity/count and transformation: english-manifest.json and
../../tools/emoji/import_english.py. Lookup normalization removes FE0F only to join
CLDR keys; output sequences are never normalized or fabricated. Names/keywords and
category ordering follow the admitted Unicode data. No Unicode endorsement implied.

The runtime english-indexed.tsv additionally stores deterministic ASCII-normalized
English titles, phrases and word sets. It is authenticated by exact pinned SHA/size
before reading. The original six-column catalog and strict parser remain the oracle;
JVM and actual Android every-row/index equality checks cover the precomputation.
No metadata/source sequence, category, keyword or ranking is personalized or learned.
