# Audio evaluation

These scripts test actual audio with an explicitly supplied offline engine.
They do not call an AI API, download a model, or establish Android microphone
or ChatGPT-cleanup behavior. The unit tests exercise scoring and subprocess
contracts with simulated engine output.

## Corpus and license

`corpus.json` selects 18 human read-speech recordings: six each for US English,
Japanese and Mandarin. The admitted set is **9,615,124 bytes / 150.22 seconds**.
Each case preserves its source filename, validation-row index, utterance ID,
gender label, frame count, transcript and immutable dataset revision. Gender
labels establish neither unique speaker counts nor accent coverage.

[Google FLEURS](https://huggingface.co/datasets/google/fleurs) publishes the
audio under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
Credit: Alexis Conneau, Min Ma, Simran Khanuja, Yu Zhang, Vera Axelrod,
Siddharth Dalmia, Jason Riesa, Clara Rivera and Ankur Bapna,
[FLEURS (2022)](https://arxiv.org/abs/2205.12446). The audio retains this license,
not Lip's MIT code license. Preserve this attribution if redistributing it.

No original recording is modified. `source-rows.json` is a metadata-only
research snapshot. `fetch_corpus.py` accepts only the admitted 18-file set,
checks exact source identity and transcripts, HEAD/GET byte sizes and ETags,
then records SHA-256 after file readback. Signed delivery URLs exist only in
memory. The manifest becomes complete only after every file verifies.
Audio and results are ignored by `eval/.gitignore`; publication is a separate
decision.

FLEURS is read speech. This small selected set cannot establish spontaneous
self-correction, conversational pauses, noisy-room accuracy or formatting
quality. Names, negation and numbers have separate exact critical checks.
Parent-owned Apple Say fixtures are synthetic, not human evidence. Human
dictation recordings and real Android testing remain separate acceptance
gates. Any later noise/pause transformation must record its source hash and
parameters and remain labeled as transformed audio.

## Run

```sh
python3 -B -m unittest discover -s eval -p 'test_*.py' -v
python3 -B eval/fetch_corpus.py eval/corpus.json
python3 -B eval/controls.py
python3 -B eval/benchmark.py eval/corpus.json \
  --output eval/results/engine.json --engine-id 'engine/model/config/hash' \
  --timeout 120 -- /absolute/path/to/whisper-cli \
  -m /absolute/path/to/model.bin -l '{language}' -f '{audio}' \
  -otxt -of '{output_stem}'
```

Install and pin the engine/model before the run; the runner executes whatever
offline command the parent supplies. No shell is used. `{audio}`, `{language}`,
`{output}` and `{output_stem}` are literal argument substitutions. The command
must write a UTF-8 transcript at `{output}`. It may instead write JSON containing
`raw` and optional `clean` text to that same path. Missing cleanup text fails
clean-output assertions; raw ASR text is never substituted for cleanup.
Use `--case ID` repeatedly for a declared subset. Keep whole-set results too.

The parent froze **0.05 maximum error rate per human case before inference**.
Critical facts must all pass. References, regexes and thresholds are never
changed to accommodate an engine's results. English uses WER; Japanese and
Mandarin use CER. Scoring normalizes Unicode NFC and case, ignores ordinary
punctuation, and preserves decimal/sign distinctions. It does not equate spoken
number words with digits or traditional with simplified Chinese. Any alternate
critical-fact spellings are explicit in the frozen manifest.

Elapsed time includes process startup and model initialization for each clip.
Real-time factor is elapsed seconds divided by actual WAV duration. A failed
process, timeout, missing output or integrity mismatch cannot produce a passing
score. Reports retain per-case scores, critical checks and the manifest hash;
engine identity is caller-supplied, so retain engine/model hashes separately.

`controls.py` creates two reproducible five-second PCM files: silence and
seeded low-amplitude white noise. `controls.json` evaluates each in all three
language modes. They contain no human or synthetic speech; any recognized
words fail. Run them through `benchmark.py` separately, never average their
scores into the human recordings.
