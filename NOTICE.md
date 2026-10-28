# Notices

Lip source and original website SVG/CSS artwork are copyright 2026 Jared Bland and licensed under the MIT License in `LICENSE`. The retained AI-generated design reference is labeled concept art, not a product screenshot. No Wispr code, assets, logos, or testimonials are distributed.

## Runtime components and development tools

| Component | License and source | Use |
| --- | --- | --- |
| Kotlin standard library and compiler 2.3.10 | [Apache License 2.0](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt), JetBrains and contributors | Android language/runtime |
| Nimbus JOSE + JWT 10.10 | [Apache License 2.0](https://connect2id.com/products/nimbus-jose-jwt), Connect2id Ltd. and contributors | OAuth identity-token/JWT validation |
| Gson 2.14.0 | [Apache License 2.0](https://github.com/google/gson/blob/main/LICENSE), Google and contributors | Shaded inside the Nimbus runtime JAR |
| JCIP annotations 1.0-1 | [Apache License 2.0](https://github.com/stephenc/jcip-annotations/blob/master/LICENSE.txt), JCIP authors and contributors | Shaded inside the Nimbus runtime JAR |
| JetBrains annotations | [Apache License 2.0](https://github.com/JetBrains/java-annotations/blob/master/LICENSE.txt), JetBrains and contributors | Kotlin runtime dependency |
| Android SDK, platform APIs, and Android Gradle plugin | [Android Open Source Project licensing](https://source.android.com/docs/setup/about/licenses); Apache License 2.0 applies to relevant source components | Build tools/API integration; SDK tools are not shipped as Lip application code |
| Gradle 8.13 and wrapper | [Apache License 2.0](https://github.com/gradle/gradle/blob/master/LICENSE), Gradle and contributors | Build tooling |
| JUnit 4.13.2 | [Eclipse Public License 1.0](https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt) | Tests only |
| JSON-java | [Public domain](https://github.com/stleary/JSON-java/blob/master/LICENSE) | JVM tests only |
| whisper.cpp 1.9.2 | [MIT License at the pinned source](https://github.com/ggml-org/whisper.cpp/blob/306c88f4d1286aec1bf96e544632897886af5501/LICENSE), copyright 2023–2026 The ggml authors | Native, CPU-only speech runtime linked into Lip's JNI library |
| GGML, vendored in that whisper.cpp pin | Same pinned MIT notice; [GGML upstream license](https://github.com/ggml-org/ggml/blob/bc0cac483baee73dec1b690b6d024a4dad13ab3e/LICENSE) | Native tensor/CPU operations; no separate GGML version is substituted |
| OpenAI Whisper large-v3-turbo, GGML Q5_0 conversion | [OpenAI turbo model card: MIT](https://huggingface.co/openai/whisper-large-v3-turbo), [fixed conversion publisher: MIT](https://huggingface.co/ggerganov/whisper.cpp/blob/5359861c739e955e79d9a303bcbc70fb988958b1/README.md); copyright 2022 OpenAI; conversion by Georgi Gerganov and contributors | Optional, user-requested model download; weights are not bundled in the APK |

A copy of the Apache License 2.0 is included in [docs/assets/apache-2.0.txt](docs/assets/apache-2.0.txt). Upstream components remain subject to their own licenses and notices; Lip's MIT License does not replace them. Retain applicable third-party license metadata when packaging modified builds.

## Native runtime and optional speech weights

The native source is pinned to whisper.cpp **v1.9.2**, commit
`306c88f4d1286aec1bf96e544632897886af5501`, including its vendored GGML tree.
There is no separate `ggml/LICENSE` file in that checkout; the root MIT notice
and the standalone GGML upstream notice have the same copyright and license
text. Their notice and the OpenAI MIT notice are retained in
[docs/assets/whisper-mit.txt](docs/assets/whisper-mit.txt). The Gradle
`bundleNotices` task includes that file with `NOTICE.md` and `apache-2.0.txt`
as APK assets.

The configured optional weight file is `ggml-large-v3-turbo-q5_0.bin`, published
by `ggerganov/whisper.cpp` at revision
`5359861c739e955e79d9a303bcbc70fb988958b1`. Lip verifies **574,041,195 bytes** and
SHA-256 `394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2`
before installation. OpenAI's [Whisper license statement](https://github.com/openai/whisper#license)
covers code and weights under MIT; its
[turbo-specific model card](https://huggingface.co/openai/whisper-large-v3-turbo)
and the pinned conversion publisher also specify MIT. The separate,
non-turbo [large-v3 card](https://huggingface.co/openai/whisper-large-v3)
labels that model Apache-2.0; that differing metadata is not presented as the
license of Lip's selected turbo artifact. This notice does not grant additional
rights or resolve licensing for other model variants.

Experimental full large-v3 and Silero VAD weight files used in local validation
are not bundled or configured for download by this production model installer.

The hosted research harness separately evaluates
[llama.cpp at its pinned MIT source](https://github.com/ggml-org/llama.cpp/blob/71ad0590f4808b6202f9213d166913858c73b1bc/LICENSE),
copyright 2023–2026 The ggml authors, with the official
[Qwen3-ASR-1.7B GGUF conversion](https://huggingface.co/ggml-org/Qwen3-ASR-1.7B-GGUF/blob/36a678687ba7d07a74ca70ccb0e36902e005fb80/README.md).
The [pinned Qwen base card](https://huggingface.co/Qwen/Qwen3-ASR-1.7B/blob/7278e1e70fe206f11671096ffdd38061171dd6e5/README.md)
declares Apache-2.0. Exact converter revision and command are unpublished;
the research pins do not establish reproducible conversion or redistribution
clearance. Neither runtime nor Qwen weights are part of Lip's APK or optional
production model download. Research weights are not distributed by this repo.

## Public speech evaluation fixture

The repository distributes one unchanged Google FLEURS EN13 test PCM file
under [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/), separately from
Lip's MIT code. It is not bundled in the application APK. Dataset revision,
source attribution, conversion and hashes are recorded in the
[fixture notice](eval/android/fixtures/README.md); corpus transcripts and
references retain their original attribution in [eval/README.md](eval/README.md).

The website uses system fonts, native HTML controls, and original vector/CSS artwork. It has no third-party font package or frontend framework. Android, Gboard, ChatGPT, OpenAI, and GitHub are names or trademarks of their respective owners; their mention is descriptive, not an endorsement. Lip is independently maintained.

## Local Bark development integration (October 2026)

Bark 3.0 Pro by Conway Research, derived from NVIDIA Parakeet TDT 0.6B v3 and
Moondream parakeet-ultra, is licensed CC BY 4.0. See bundled bark-NOTICE.txt
and bark-CC-BY-4.0.txt. Mac inference uses the publisher's Core ML FP16 export;
Android inference uses Maximilian Scholz's Q4/Q8 GGUF conversion, pinned in
BarkModel.kt. Neither is a new measurement of the publisher's BF16 benchmarks.

Starling engine by Maximilian Scholz (MIT), revision
28d1ff66da2514a3866ec5ba38c5df90105b9dd4, and its pinned ggml dependency (MIT)
are used by the Android native adapter. Full licenses are bundled in assets.
Starling applies its upstream ggml patches when configuring its CMake build.
The Lip adapter adds bounded PCM, leading-silence padding, timestamp translation
and per-context cancellation while retaining Lip's capture-lease safeguards.

The macOS app uses FluidAudio (Apache-2.0), pinned in desktop/Package.resolved.
See desktop/THIRD_PARTY.md and the app's bundled licenses.

Bark 3.0 Clean model by Conway Research, CC BY 4.0. The separately downloaded
Android Q4 GGUF conversion is by scholzmx (revision
000c6b353d237ff647644ea2c3beb3910a21215b); the macOS Core ML conversion is by
Fluid Inference (revision 73b0c0da665d29ea0332e9f0d32da8e26a4cdb38), CC BY 4.0.
Source: https://huggingface.co/ConwayResearch/Underdog-Bark-3.0-Clean
Conversions: https://huggingface.co/scholzmx/underdog-bark-3.0-clean-gguf and
https://huggingface.co/FluidInference/bark-3.0-coreml
Lip integrates these derivatives; original-model benchmark results are not guaranteed
for either conversion. Downloaded NOTICE files remain alongside models.

Portable neural swipe engine: six MIT C++ files by Destin Moss, pinned to
itsdestin/swipe-keyboard f48099755cd1ebeb3f3a9e40f680a683ccc95b2b.
See third_party/swipe/NOTICE.md and MIT.txt (bundled in the Android APK).
Weights and dictionary data are separately licensed; no claim of Gboard superiority.

Bundled English swipe dictionary and neural weights: see
third_party/swipe/english-data-manifest.json and data-licenses/ for exact source,
derivative rules, full AOSP NOTICE/copyrights, Apache-2.0, ODC-By-1.0, Tatoeba
CC-BY-2.0-France notice and complete English contributor attribution. FineWeb
counts are by Hugging Face/Common Crawl under applicable terms. The MIT swipe
weights are by Destin Moss/youcoded.ai (2026); FUTO's MIT donated training-data
notice is retained. No proprietary FUTO/Google keyboard engine is incorporated.

## Local GIF reaction catalog

Animated Noto Emoji by Google, CC BY4.0. Official source/license announcement:
https://googlefonts.github.io/noto-emoji-files/?view=docs

Twelve pinned official GIFs are bundled for offline use. Lip adds an attribution
comment; visual frames are unchanged. See `third_party/gif/NOTICE.md`, the per-file
source/hash manifest and full `third_party/gif/CC-BY-4.0.txt`. No endorsement implied.

## English contextual next-word counts

Mixed Leipzig Corpora Collection (CC BY4.0) and Tatoeba contributors (CC BY2.0FR)
counts adapted from Wasi Master's WM Keyboard data. Original source identities,
attribution, modifications and license URIs/text are in
`third_party/nextword/NOTICE.md` and packaged `nextword-licenses/`. The repository's
MIT code license does not replace these data licenses. No endorsement is implied.

Offline emoji metadata: Unicode CLDR49/Emoji17.0 data, Unicode-3.0. See third_party/emoji/NOTICE.md and bundled emoji-licenses notices. No emoji artwork/font or LDML specification copied.


## All-in-one offline preview (0.5.0-preview.1-offline)

The separately identified Lip Preview APK includes **both** pinned Bark 3.0 Clean
and Pro GGUF conversion files as uncompressed assets. For this package, the earlier
"separately downloaded" wording above does not describe delivery of these two
models. No speech-model network download is required. Clean is the default; Pro
can be selected and prepared locally from the same APK. The quantized weights
remain CC BY 4.0, not MIT. Full model attribution, exact converter revisions,
checksums and derivative-change notice are in `bark-offline-models-NOTICE.txt`,
bundled with the full CC BY 4.0 license. Older optional Whisper/research model
files are not part of the offline preview. No private/unlicensed WeChat artwork
or private communication/history data is bundled. This preview is not a claim
of completed Gboard parity, physical-phone performance or the 9.2 release gate.

## Android Vulkan GPU build dependency

Android16+/ARM64 builds compile the existing GGML Vulkan backend and use internal
automatic eligible-GPU selection, retaining checked same-model CPU safety fallback.
This is not a claim of NPU support, physical GPU utilization or measured efficiency.
Khronos Vulkan-Headers **v1.4.335**, commit
`2fa203425eb4af9dfc6b03f97ef72b0b5bcb8350`, is vendored as unchanged build-only headers
in [third_party/vulkan](third_party/vulkan/NOTICE.md), including original copyright
2015–2025 The Khronos Group Inc. and per-file notices. All selected headers permit
Apache-2.0; Lip selects that license for dual-licensed generated Vulkan-Hpp headers.
The full Apache license is already included in the APK notices assets as
`apache-2.0.txt`, and original Apache/MIT license texts and exact source hashes are
retained in that directory. Android supplies the Vulkan loader and the installed
NDK supplies build tools; no SDK/compiler binaries or speech weights are bundled.
