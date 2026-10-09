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
| Android NDK r30 C++ runtime | LLVM licenses and component-specific notices from NDK `30.0.16248370`, retained verbatim in [NDK notices](docs/assets/android-ndk-notice.txt) and [toolchain notices](docs/assets/android-ndk-toolchain-notice.txt) | Experimental shared `libc++_shared.so`; both notice files are bundled as APK assets. Toolchain notices also describe build-only components and do not replace Lip's MIT license. |
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
