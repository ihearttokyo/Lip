# Audio evaluation

These scripts test actual audio with an explicitly supplied offline engine.
The scoring and offline-engine adapters do not call an AI API or download a
model. The hosted research canary below separately acquires pinned weights.
Neither path establishes Android microphone or ChatGPT-cleanup behavior. The
unit tests exercise scoring and subprocess contracts with simulated output.

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

## Hosted Qwen 1.7B research canary

`qwen17_ci.py` builds only the pinned standard `llama-server` on an
unprivileged GitHub-hosted Ubuntu runner. A push containing `[qwen17-canary]`
runs it after ordinary checks. It never uses KVM, root, an API key or the Mac
for model inference. Model acquisition is limited to the two immutable Q8
files in `qwen17-pins.json` (2,520,744,288 bytes total), with complete size and
SHA-256 verification. Weights are not uploaded or shipped in Lip.

The canary stage scores only original `fleurs-en-013`, using the unchanged 5%
per-clip gate and critical-fact checks. It must qualify the actual audio
protocol, natural EOS and measured peak memory before a full 18-case run.
The adapter uses AUTO-language mode and removes only the exact Qwen language
header; it preserves repetition and all transcript words. Missing metadata,
length-limited output, errors and timeouts fail rather than count as silence.

The [first qualified canary](qwen17-canary-receipt.json) produced the exact
17-word reference, preserved both critical facts and ended naturally. Its
Linux peak RSS was 3,295,793,152 bytes; startup, request and total case times
were 1.018, 5.086 and 8.039 seconds respectively. The parent independently
parsed the digest-verified response and recomputed the frozen score. This
qualifies only the next research stage, not multilingual or Android quality.

Adding `[qwen17-corpus]` to the same marked push requests the full 18-case
stage. It requires that reviewed receipt and a fresh passing EN13 canary,
then runs the remaining 17 clips in original manifest order. Each case is
retained before proceeding; no failed case is omitted. The original per-case
bounds remain; the job ceiling is 120 minutes to cover 18 possible timeouts
plus setup. Expected elapsed time is 15–25 minutes, not a latency guarantee.

The runner requires six GiB free disk, caps process address space at 12 GiB,
builds with two workers and decodes with four CPU threads. Each case starts
one cold loopback-only server in the benchmark's owned process group, with
90-second startup, 180-second request and 300-second outer bounds. This is
not warm latency, Android memory fitness, microphone or ChatGPT evidence.
Bounded model output, source/build identities and failed results remain
research receipts. Unit tests use simulated responses, not recognized speech.

The [official GGUF card](https://huggingface.co/ggml-org/Qwen3-ASR-1.7B-GGUF/blob/36a678687ba7d07a74ca70ccb0e36902e005fb80/README.md)
names the Qwen base and converter but does not publish the exact conversion
command or source revision. The pinned Qwen base card declares Apache-2.0;
that evidence is not a claim of reproducible conversion or Android
redistribution clearance. No candidate is promoted by this harness.

The [completed full-set receipt](qwen17-corpus-receipt.json) records **11/18**
strict passes (English 4/6, Japanese 4/6, Mandarin 3/6). All 18 responses ended
naturally, but name substitutions and other frozen-score failures remain.
The candidate is rejected for quality and is not integrated or promoted.
A failed research job is retained evidence, not a reason to change references
or omit cases. Phone routing and authenticated cleanup still need live tests.
