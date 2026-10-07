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

A copy of the Apache License 2.0 is included in [docs/assets/apache-2.0.txt](docs/assets/apache-2.0.txt). Upstream components remain subject to their own licenses and notices; Lip's MIT License does not replace them. Retain applicable third-party license metadata when packaging modified builds.

The website uses system fonts, native HTML controls, and original vector/CSS artwork. It has no third-party font package or frontend framework. Android, Gboard, ChatGPT, OpenAI, and GitHub are names or trademarks of their respective owners; their mention is descriptive, not an endorsement. Lip is independently maintained.
