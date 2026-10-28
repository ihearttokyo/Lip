#!/bin/bash
# Real model + production JNI source on host JVM. NOT Android-device evidence.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then echo 'Usage: eval/run_bark_host_smoke.sh pinned-bark-pro.gguf synthetic-mono-16k.wav [CLEAN|PRO]' >&2; exit 2; fi
# Fixture must contain the expected Friday fact; see docs/LOCAL-BARK.md.
MODEL="$1"; AUDIO="$2"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
OUT="$ROOT/validation/bark-host-smoke"
cmake -S third_party/starling/backends/native -B "$OUT/native" -DSTARLING_GGML_SHARED=ON \
 -DSTARLING_GGML_TESTS=OFF -DSTARLING_SERVE=OFF -DSTARLING_QUANTIZE=OFF \
 -DGGML_NATIVE=OFF -DGGML_LLAMAFILE=OFF -DGGML_OPENMP=OFF -DCMAKE_BUILD_TYPE=Release
cmake --build "$OUT/native" -j 4 --target starling_ggml
xcrun clang++ -std=c++17 -dynamiclib app/src/main/cpp/bark_jni.cpp \
 -I"$JDK/include" -I"$JDK/include/darwin" -Ithird_party/starling/cpp -Ithird_party/starling/cpp/include \
 -L"$OUT/native" -lstarling_ggml -Wl,-rpath,"$OUT/native" -o "$OUT/native/liblip_bark.dylib"
STDLIB=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" -name '*.jar' | head -1)
CP="app/build/tmp/kotlin-classes/debug:$STDLIB"
"$JDK/bin/javac" -cp "$CP" -d "$OUT/classes" eval/BarkSmoke.java
"$JDK/bin/java" -Djava.library.path="$OUT/native" -cp "$OUT/classes:$CP" dev.lip.speech.BarkSmoke "$MODEL" "$AUDIO" "${3:-PRO}"
