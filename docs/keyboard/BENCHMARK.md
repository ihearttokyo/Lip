# English swipe benchmark protocol v1

Frozen design before Lip candidate tuning. Public references: Gboard's official
Glide Typing help; each candidate's own versioned model card/license. No claim of
Google internal model knowledge, all-language parity or existing superiority.

## Admission / anti-overfitting

Separate algorithm development replay from untouched confirmatory data. Deduplicate
trace IDs and recording sessions across splits; pin layout, expected text, corpus
license, split hashes, model training-overlap disclosures and runtime/candidate hash.
Reject missing/relabelled targets, empty/non-finite/unbounded traces and cherry-picked
successful words. Score every admitted trace, including OOV/failure/timeout.
Published upstream test sets already inspected by candidates are external baselines,
not a freshly unseen Lip holdout. Synthetic paths are control fixtures, not human data.

## Replay screen (not Gboard superiority)

Pinned candidate vs pinned geometric baseline on the same >=2,000 independently
recorded held-out English word attempts, session-disjoint development/confirmation.
Report top1/top3, OOV, wrong-valid-word errors, number/name/negation strata, per-session
results and failure cases. Runtime geometry must match the admitted keyboard.
No dictionary/reference edits after observing outcomes. Model-card scores are not
Lip measurements. Benchmark candidate includes network, dictionary, decoder and priors.

## Prospective Gboard comparison — physical hold applies

Register exact Gboard/app/OS/device versions, settings, offline/downloaded-language
state, layout sizes, conditions and phrase-bank hash before trials. User-authorized
study: randomized within-person crossover, >=12 participants and >=2,000 total word
attempts, same devices and phrase bank, balanced keyboard order; report all attempts
and corrections. Do not control or inspect an actual messaging app as a shortcut.
Use a disposable benchmark editor, never password/production communications.

A provisional operational meaning for "far superior" (review before collecting):
- >=25% relative reduction in uncorrected word-error rate versus Gboard, with the
  paired participant-bootstrap95% lower confidence bound >=25% reduction;
- >=10% lower-confidence-bound improvement in corrected words/minute, with no accuracy regression;
- swipe-up to visible candidate p95 <=150ms and not >10% worse than Gboard;
- no >10% energy regression over a fixed sustained task and no safety/data-loss bug.
Record raw paired outcomes privately with consent. Zero-error/undersized baselines
cannot establish relative error reduction. No denominator manipulation or removal
of failed/slow trials. The broad superiority claim remains NOT RUN until this
protocol is admitted and executed; the computation appendix below controls exact definitions; independent preflight review is required.

## Safety / adversarial controls

Malformed/oversized/NaN traces, cancelled gestures, multitouch interruption, change
of app/field/caret/layout, stale model result, password/incognito, selection, emoji
clusters, repeat-letter words, apostrophes, OOV names, low-memory, unload/download
cancellation, offline restart and composing correction. No trace persistence by default.

## Evidence and scoring

Unit tests, native host, emulator and actual physical-device results remain separate.
Device timings/energy never come from desktop runs. Threshold comparisons consume
raw trial identities and build hashes, not a hand-edited summary. An independent
reviewer owns numerical quality ratings. Missing measurements cannot award9.2.

## Frozen computation appendix (preflight revision1; before any Lip swipe runs)

Unit of randomization/analysis is participant. At least100 attempted target words
per keyboard per participant (>=2,400 total across12), equal practice20 words per
keyboard excluded prospectively, balanced randomized AB/BA order and the same
frozen English phrase bank. No silent corpus exclusions; withdrawals and unavailable
conditions invalidate qualification until the minimum is met. Personal learning is
reset/disabled for both keyboards under participant consent; no production data.

WER: Unicode NFC + English lowercase; punctuation removed except internal
apostrophes; split on Unicode whitespace; no number conversion or spelling rescue.
Levenshtein word distance (substitutions+insertions+deletions) / target-word count.
Uncorrected WER uses final text at explicit phrase submission. Corrections include
all backspaces, candidate changes and retyping, logged in the disposable editor.
Corrected WPM = max(0, committed Unicode scalar count minus Levenshtein character
errors)/5 divided by elapsed minutes from first touch-down to explicit submission.
Spaces count as characters; no invisible prefilled text. Report raw completion time,
keystrokes/corrections and WER too so one derived measure cannot conceal failures.

Participant means are equally weighted, not weighted by each participant's number
of easy words. Paired participant bootstrap:10,000 resamples, seed20261026, two-sided
percentile95% intervals; all participant observations kept together per resample.
For error reduction use 1 - meanParticipantWER(Lip)/meanParticipantWER(Gboard).
For WPM use meanParticipantWPM(Lip)/meanParticipantWPM(Gboard)-1. Preregister a
separate uncorrected error endpoint and fixed WPM endpoint; no winner selection.

Practical superiority requires the **95% lower confidence bound >=25% relative
WER reduction and >=10% WPM improvement**, not just a positive mean or bound above0.
A zero Gboard WER denominator is UNQUALIFIED for the relative-error claim.
The95% upper bound of paired Lip/Gboard energy and p95 latency ratios must be<=1.10;
Lip p95 swipe-up-to-visible-candidate also<=150ms. Report all absolute values.
No substitution of 'no significant difference' for an equivalence/noninferiority gate.

Timing is observed identically for both keyboards: externally captured>=240fps
video of the disposable editor/keyboard, finger/stylus release frame to first fully
visible candidate frame. Two blinded annotators; disagreements>1 frame adjudicated
before keyboard identity is revealed. Frame quantization and disagreement rates
are reported. Internal Lip timings are diagnostic only, not the Gboard comparator.
Use>=200 completed swipes per keyboard per device and all timeouts as failures;
three-second no-candidate timeout, not removal from the latency denominator.

Energy: same physical device, airplane/offline state, brightness200nits, display
refresh, battery state40–80%, thermal steady state, same fixed20-minute phrase
schedule; randomized condition order and>=5 paired blocks per device. Measure
whole-device DC energy with a calibrated external power monitor while battery
bypass/fixture is verified; subtract an identically measured idle-display baseline.
Report joules, monitor calibration/error and thermal curves. Battery-percentage
deltas, host power, emulator power and unequal work are NOT qualifying evidence.
If compatible measurement is unavailable, energy and the whole superiority gate
remain UNQUALIFIED rather than dropping that dimension.
