# Shared English speech contract v1

Prospective qualification, not a claim that existing tests passed it. Applies to
both apps; Clean and Pro evaluated separately. Existing synthetic tests are controls.

Admit>=60 independent human utterances across>=10 speakers, >=30 seconds of speech
per speaker, with consent/redistributable source licenses, varied accents, quiet
and fixed additive-noise conditions. Pin original audio hashes, mono16k conversion,
utterance boundaries, references, semantic anchors and speaker-disjoint development/
confirmation assignments before model runs. No paid generation or private audio upload.

Pro reference is adjudicated verbatim speech; Clean reference is independently
adjudicated filler/false-start/repetition removal retaining substantive words,
facts, negation, names and numeric units. Two annotators resolve differences before
results. Normalize NFC/lowercase and punctuation for WER as defined in BENCHMARK;
keep internal apostrophes. Do not silently add number normalization. Separate
case/punctuation scores use unnormalized references and deterministic sentence marks.
Natural punctuation may have multiple pre-adjudicated acceptable references; no
post-result rescue. Native model behavior is evaluated, not a second editor stage.

Each profile/platform: aggregate WER<=5%, no admitted speaker>10% WER;100% of pinned
negation/numeric/unit/proper-name semantic anchors preserved. Numbers compare via
pre-admitted canonical quantity/unit anchors, not guessed equivalence after decode.
Clean removes>=95% of annotated fillers/restarts with zero substantive-anchor loss.
Same-profile cross-platform aggregate WER difference<=1 percentage point on identical
clips; neither platform may fail its own quality bounds. Every failed/empty decode
counts; no successful-only denominator. Silence/no-speech controls: zero invented
words in10 pinned silent/noise-only clips per profile/platform.

Physical p95 Stop→visible final transcript<=2seconds for30-second utterances, and
warm RTF<=0.25, measured on each declared supported target. Cold model setup is
reported separately (<=180seconds after downloaded files, not counted as warm).
Cancel→microphone capture closed<=250ms, with no later automatic insertion across
at least5seconds of drained callbacks and a following new session. Hold/release,
locked stop, app sleep/lock, field/caret/selection changes, model switch refusal,
permission revoke, low space and cancelled setup each have explicit outcome cases.

Representative physical recording/routing, device timing and actual target-editor
insertion are separate evidence from file-fed decode, host JNI and synthetic speech.
The physical-phone hold leaves those criteria OPEN/HELD. Failure means improve the
product or report that Bark cannot meet the frozen scope; never weaken references,
add an unauthorized model/LLM or silently waive the criterion to get9.2.
