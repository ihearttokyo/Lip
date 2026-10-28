# Public English next-word counts

Lip adapts word bigram/trigram counts redistributed by **Wasi Master / WM Keyboard**,
repository revision e66fe7098922de3bf81915cf93a63d2da7dbf634:
https://github.com/wasi-master/wmkeyboard-data/tree/e66fe7098922de3bf81915cf93a63d2da7dbf634

The dataset is NOT covered solely by the repository's MIT code license.

1. **Leipzig Corpora Collection**: English `eng_news_2024_1M` and
   `eng-com_web-public_2018_1M`, declared by the upstream distributor under
   **Creative Commons Attribution 4.0 International**.
   https://wortschatz.uni-leipzig.de/en/download/English
   https://creativecommons.org/licenses/by/4.0/
   Cite: D. Goldhahn, T. Eckart and U. Quasthoff, *Building Large Monolingual
   Dictionaries at the Leipzig Corpora Collection: From100 to200 Languages*,
   Proceedings of LREC2012. Retain `CC-BY-4.0.txt`.
2. **Tatoeba and its contributors**, https://tatoeba.org/:
   textual sentence data under **Creative Commons Attribution 2.0 France**
   (some contributions additionally CC0). Upstream blends conversational counts
   at weight3; see commit cf34a4225f023c6f0ecf3fac6a070455551d9466 and pinned importer.
   https://tatoeba.org/en/downloads
   License URI: https://creativecommons.org/licenses/by/2.0/fr/
   Legal code URI: https://creativecommons.org/licenses/by/2.0/fr/legalcode
   Official textual-attribution guidance:
   https://en.wiki.tatoeba.org/articles/show/faq

Lip modifications: deterministic intersection with the pinned admitted English
lexicon; omit flagged offensive entries and unsupported spellings; aggregate no
private data; retain up to three highest-count continuations for each one/two-word
context, lexical tie break; encode a verified tab-separated public-count asset.
This is a derivative of mixed-source counts, not an untouched corpus or a learned
personal model. Licenses/attribution remain applicable; no endorsement implied.

Provenance limit: Leipzig's original landing was behind an anti-bot gate and was
not bypassed. Its license/source identity is downstream asserted, not independently
reconstructed from original corpus tarballs. Tatoeba's official downloads and FAQ
were checked; FAQ accepts project-level textual attribution. A separate usage page
mentions author names; no sentence-level author identities survive the aggregate
upstream counts. This is an explicit attribution rationale, not legal certainty.
The CC2FR legal-code download returned403; include its license URI, not fabricated
full license text. No upstream scripts were executed to retrieve raw corpora.
