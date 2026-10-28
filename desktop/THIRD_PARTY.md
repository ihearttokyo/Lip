# Bark Pro attribution

Bark 3.0 Pro by Conway Research, derived from NVIDIA Parakeet TDT 0.6B v3
and Moondream parakeet-ultra. Weights: CC BY 4.0. Source model and runtime:
https://huggingface.co/ConwayResearch/Underdog-Bark-3.0-Pro
Pinned model revision: 4b5c51e04aa8d16dda9ecdff71bc29f7360149e4.
The downloaded NOTICE and coreml/LICENSE accompany the model files.

BarkEngine.swift adapts the model author's runtime/coreml/Sources/main.swift:
persistent compilation cache, automatic short8/full15 encoder selection,
bounded encoder-function residency, native timing/score retention,
5-minute input limit and integration into a local macOS recorder.
Core ML uses FP16 learned weights, not the original BF16 benchmark path.
Do not equate either runtime or quantized conversions with published accuracy
without testing them.

FluidAudio by Fluid Inference: Apache-2.0, pinned in Package.resolved.
https://github.com/FluidInference/FluidAudio
Its repository includes its license and third-party notices. Build resources
and dependencies remain subject to their respective licenses.


Bark 3.0 Clean by Conway Research, CC BY 4.0:
https://huggingface.co/ConwayResearch/Underdog-Bark-3.0-Clean
Core ML export by Fluid Inference (CC BY 4.0):
https://huggingface.co/FluidInference/bark-3.0-coreml
Pinned revision: 73b0c0da665d29ea0332e9f0d32da8e26a4cdb38.
The conversion and its NOTICE are downloaded separately. It retains model-native
filler/restart/repetition removal; Lip adds no extra model or semantic editor.
Component/vocabulary selection, model installation and recording integration are Lip changes.

## Pinned portable-vocabulary Unicode data

`Sources/LipLocal/VocabularyData/` includes selected Unicode16.0.0 tables derived
locally from resident CPython3.14.6, not a Python runtime. The original Unicode
License V3 and CPython license accompany the generated resource. See its
`PROVENANCE.json` for generator/input versions, exact hashes and the bounded
transformations. No Unicode17/Foundation normalization is represented as16.

Regenerate/check with resident CPython3.14 + UCD16 only:
`python3.14 tools/learned_words/generate_unicode16.py` (from repository root;
read-only default). No bootstrap or network/model download occurs. The larger
synthetic differential corpus is test data, not bundled runtime content. Data
and algorithm checks are not installed-app or spelling-provider acceptance.
