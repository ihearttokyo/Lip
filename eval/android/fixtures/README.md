# Public cancellation fixture

`fleurs-en-013.pcm` is a byte-for-byte copy of the existing validated test
PCM at `validation/hillclimb/platform-corpus/assets/fleurs-en-013.pcm`.
This copy adds no gain, crop, resampling or re-encoding. It contains the full
120640 samples: mono 16000 Hz signed 16-bit little-endian PCM, 7.54 seconds,
241280 bytes. SHA-256:

```text
e42fdeed81feac1d9d660e888ceb0351e785760f24ec7b8e9d8420cfe96a800f
```

## Attribution and source pins

Source: Google [FLEURS](https://huggingface.co/datasets/google/fleurs/tree/70bb2e84b976b7e960aa89f1c648e09c59f894dd),
licensed under [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/).
Dataset revision `70bb2e84b976b7e960aa89f1c648e09c59f894dd`, configuration
`en_us`, validation row 13, utterance ID 1598, source file
`10461636001046553742.wav`. Repository provenance is recorded in
[`eval/source-rows.json`](../../source-rows.json) and
[`eval/corpus.json`](../../corpus.json).

The full source WAVE is 482618 bytes with SHA-256
`431d013d01d25cbf8d42587f4a14df57d09dbe9cbc2af4ea91ba93f9bd290ec4`.
The existing test conversion reads the full mono 16 kHz float-source WAVE,
rounds each sample multiplied by 32768 and clamps to the signed 16-bit range
at zero gain (`eval/observe_vad.py:pcm_samples` and
`eval/vad_holdouts.py:quantize_pcm`). It preserves the full sample count and
order, not float-source byte identity. The published PCM is copied unchanged
from that conversion; this task performs no audio conversion.

Frozen reference:

> Although three people were inside the house when the car impacted it, none of them were hurt.

The original corpus scoring and negation/count checks remain unchanged.
This fixture supports one file-fed cancellation diagnostic, not an accuracy,
performance, microphone, phone or parity claim.
