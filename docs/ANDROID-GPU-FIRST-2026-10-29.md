# Android16+ ARM64 automatic GPU feature candidate

Source candidate, not physical-phone/efficiency/release qualification. Built from
sourcePR17 head a087045 in the isolated GPU feature worktree. No native-engine
vendor file or1618-file manifest is changed. No phone/device, permissions, default
keyboard, installed Mac app, microphone, history, provider or network action is
part of this increment.

## Actual production change

The default Android Bark build now compiles GGML Vulkan alongside its CPU safety
backend, rather than leaving Vulkan OFF. Production checked tickets select the
existing AUTOMATIC policy, not the previous forced CPU policy. This is an actual
model/backend route, not a label or new user setting. Eligible Adreno/native Vulkan
provenance can initialize a GPU; unknown/software/virtual/unsupported candidates
remain rejected before initialization. The first intended phone is Xiaomi15Ultra
(Snapdragon8Elite/Adreno); its installed OS and actual driver still need verification.
Android API36+ and arm64-v8a are required. No backend chooser is exposed.

Checked sealed-byte model hashing, fixed Clean/Pro profiles/padding, cancellation,
heap request retention, sticky unknown-retirement fencing and at-most-one fresh
same-model CPU retry remain unchanged. Retry requires actual nonCPU native failure
and confirmed retirement, never a build flag or status2 alone. CPU is retained as
a safety/unsupported-operation fallback, not advertised as a GPU/NPU result.
STARLING_FAST remains OFF because its separate Pixel TPU path is not compatible
with the current checked-FD lifecycle and is not Xiaomi Hexagon support.

## Reproducible dependency

`third_party/vulkan` contains only the complete49-header closure from official
Vulkan-Headers v1.4.335 (commit2fa203425eb4af9dfc6b03f97ef72b0b5bcb8350), unchanged
license metadata/texts and Lip's small manifest/check. CMake verifies all52 original
files before configuring the backend. It uses the pinned installed NDK30's host
glslc, SPIR-V headers and exact ARM64/API loader stub; no SDK binary, hidden
validation-folder dependency, fetch fallback or model weight is added to the repo
or APK. Headers are build-only. All selected headers permit Apache-2.0; the already
bundled Apache license is byte-identical to upstream's full text. Updated root
NOTICE supplies the component/pin/copyright attribution. No new Gradle notice
packaging is required.

## Evidence and commands

Executed (source/syntax only):

```sh
python3 -m unittest eval.test_android_gpu_feature -v
cmake -DLIP_VULKAN_HEADER_ROOT="$PWD/third_party/vulkan" -P third_party/vulkan/verify_headers.cmake
python3 third_party/starling/scripts/verify_lip_vendor.py --root third_party/starling
```

Three new checks pass; existing engine1618 files verify unchanged. Host and NDK30
ARM64/API36 C++17/-Wall/-Wextra/-Werror/-pedantic syntax checks of the checked-session
header pass. These do not compile/link the Vulkan backend or prove device execution.
The broader old `eval.test_local_bark_contract` run retained one PRE-EXISTING failure:
its exact-language-string assertion no longer matches unchanged HEAD Dictation.kt.
That source/test were not edited or silently repaired by this feature.

Parent's next serialized build (NOT executed by this builder):

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew ':app:configureCMakeRelWithDebInfo[arm64-v8a]' --offline --no-daemon
python3 - <<'BUILD_NATIVE'
import json
from pathlib import Path
import subprocess
root = Path.cwd().resolve()
models = list((root / 'app/.cxx/RelWithDebInfo').glob('*/arm64-v8a/android_gradle_build.json'))
assert len(models) == 1, 'Use a fresh isolated worktree/build; do not guess among native candidates'
model = json.loads(models[0].read_text())
command = model['buildTargetsCommandComponents']
assert command[-1] == '{LIST_OF_TARGETS_TO_BUILD}'
assert '-C' in command and not any(arg.startswith('-j') for arg in command)
assert Path(command[command.index('-C') + 1]).resolve().is_relative_to(root / 'app/.cxx')
subprocess.run(command[:-1] + ['-j1', 'lip_bark', 'lip_swipe', 'lip_sticker_codec'], check=True)
BUILD_NATIVE
./gradlew :app:testDebugUnitTest :app:assembleDebug --offline --no-daemon
```

Use one native outer build job: a single GGML shader generator has no exposed
concurrency flag/environment setting; its hardcoded min(16,hardware_concurrency)
permits10 glslc children on this host. There is no truthful2-process bound.
Resident AGP8.13.2 Cmake/CmakeFlags DSL has NO `buildArguments` property; no invented
Gradle option was added. AGP invokes Ninja directly, so CMAKE_BUILD_PARALLEL_LEVEL
alone cannot impose the required outer limit. Configure first, run the exact AGP
Ninja command with `-j1` as above, then let assembly reuse native artifacts. Parent
must inspect actual assembly logs rather than claim up-to-date native work from
intent (the upstream external shader-generator build step is always scheduled).
No immutable vendor concurrency patch/new tool wrapper, build, CI or publication
was triggered here. If AGP's configuration task spelling changes, obtain the actual
configuration-only task from `./gradlew :app:tasks --all --offline`; do not begin
an unbounded assemble as a task-name workaround.

For a direct native-only candidate build under the parent's serialized lane:

```sh
cmake -S app/src/main/cpp -B build-android-gpu-api36 -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=/Users/jared/Library/Android/sdk/ndk/30.0.16248370/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-36 -DCMAKE_BUILD_TYPE=Release
cmake --build build-android-gpu-api36 --parallel 1 --target lip_bark lip_swipe lip_sticker_codec
```

## Remaining actual hardware gates

Physical-phone testing remains HELD. Only after Jared explicitly lifts that hold,
read the actual Xiaomi identity, ARM64,
Android SDK≥36 and Vulkan driver facts first; do not assume OS version or upgrade
it. Then qualify actual pinned Clean/Pro file-fed decoding, selected backend,
transcript/timing equivalence, cancellation/retirement/fallback and application
editor behavior. Measure matched CPU/GPU latency, memory and power before any
most-efficient or improvement claim. Physical NPU/Hexagon requires its own real
model conversion/runtime route; no fake TPU/NPU option is included. All whole9.2
and release gates remain open. Existing published CPU previews are unchanged.

## Parent build result
Native CPU+Vulkan ARM64/API36 compilation/linking and application assembly/lint passed. Android JUnit results:628total,615executed,13skipped,zero failures. The unbundled DEBUG APK is60,448,718bytes; this is not a release-size promise or a published installer. ELF directly depends on libvulkan.so. Actual Xiaomi GPU inference, efficiency/power and Hexagon remain unqualified. Use a no-space source/build path: the upstream shader-generator subbuild failed on the Backup-volume spaced path; the unchanged source built from the no-space checkout. The original failure is retained locally; no vendor/hash gate was weakened.
