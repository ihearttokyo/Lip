# Android microphone fixtures

This test-only client injects PCM into the emulator microphone. Lip's real
AudioRecord, recognition, controller, cleanup and insertion paths remain in
use. No recognition results are supplied. Yielded-byte counts do not prove
guest delivery or ASR quality; the parent run must verify guest frequency/RMS
first, then recognized speech and UI behavior.

## Setup

Use `/Users/jared/Developer/Lip/validation/audio-venv/bin/python`, built from
the bundled Python 3.12 runtime with system site packages. The parent installed
the exact versions in `requirements.lock`; these are validation-only tools,
not production APK dependencies. Cryptography 50.0.1 comes from the bundled
runtime. Keep the venv, generated bindings, runtime receipts and auth state out
of Git. Do not install or change global tools from this script.

Generate bindings once from the resident emulator proto, not a newer online
schema:

```sh
PY=/Users/jared/Developer/Lip/validation/audio-venv/bin/python
EMULATOR_ROOT=/Users/jared/Library/Android/sdk/emulator
BINDINGS=/Users/jared/Developer/Lip/validation/grpc-generated
mkdir -p "$BINDINGS"
INCLUDE=$($PY -c 'import grpc_tools; from pathlib import Path; print(Path(grpc_tools.__file__).parent / "_proto")')
$PY -m grpc_tools.protoc -I "$EMULATOR_ROOT/lib" -I "$INCLUDE" \
  --python_out="$BINDINGS" --grpc_python_out="$BINDINGS" \
  "$EMULATOR_ROOT/lib/emulator_controller.proto"
$PY -c 'import hashlib, pathlib, sys; print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())' \
  "$EMULATOR_ROOT/lib/emulator_controller.proto" > "$BINDINGS/proto.sha256"
```

Required proto SHA-256:
`8a086dc39d71e11ce7a65cc7e0634d3e268361b1eb97d656bfb8543b6e70c99e`.
The default remains the installed emulator 35.6.11, build 13610412. The only
other admitted root is the parent-verified isolated 37.2.12, build 16428233:
`/Users/jared/Developer/Lip/validation/emulator-37.2.12/emulator`.
Its proto SHA-256 is
`564e00a929e7e4a7af78269a738ea715f32d88c0fc9f92659bb8a1251c9d558f`.
For that canary, set `EMULATOR_ROOT` to this exact path and use a separate
`BINDINGS` directory in the same generation command. Pass both
`--emulator-root "$EMULATOR_ROOT"` and `--bindings "$BINDINGS"` to the client.
Other roots and changed source.properties, protos, vendor auth policies or
binding markers fail closed; the process must belong to the selected runtime.

The parent recorded the official stable package's 416,112,708 bytes and ZIP
SHA-256 `f4c4a142bda54fcec064bdb32b51f19e25bae0a45ad019197ef968bf7a48979b`
in `/Users/jared/Developer/Lip/validation/hillclimb/receipts/emulator-candidate.json`. The existing SDK
was not replaced. License content matched the installed package, with only
whitespace serialization differences; no new terms or license-file changes
were accepted. This client does not download, install or license a runtime.
Admitting the canary does not assert that it fixes microphone delivery.

## Run

The parent launches the isolated AVD with guest audio enabled, explicit
`-grpc PORT -grpc-use-jwt`, and no `-allow-host-audio` or `-no-audio`.
Do not alter the SDK allowlist or console token. Pass the engine PID, exact
discovery file and expected AVD directory; the client verifies the process
owner, SDK engine, AVD identity, port, exact loopback listener addresses and
private JWT paths. Every address resolved for the endpoint must be bound by
that PID; prefer an explicit `127.0.0.1` endpoint.

```sh
$PY /Users/jared/Developer/Lip/eval/android/inject_audio.py \
  --emulator-pid ENGINE_PID --endpoint 127.0.0.1:GRPC_PORT \
  --discovery EXACT_DISCOVERY_INI \
  --avd-dir /Users/jared/Developer/Lip/validation/avd/LipValidation.avd \
  --wav FROZEN_WAV --wav-sha256 FROZEN_SHA256 \
  --bindings "$BINDINGS" --duration-seconds 200
```

The WAV must be uncompressed 16 kHz, mono, signed 16-bit PCM. The client reads
at most 19,265,536 WAV bytes once, checks that snapshot's SHA-256, then streams
its immutable PCM. Later path replacement cannot change the emitted fixture.
Duration includes the fixture and a silent tail, never truncates the fixture,
and is bounded at 600 seconds. Packets use the emulator's non-overwriting
blocking delivery mode; the client does not throttle. SIGINT/SIGTERM ends the producer; it does not finish
Lip's capture. The parent must use the real Finish/Cancel control and verify
capture teardown independently.

The client keeps its private ES256 key and JWTs in memory. Only a uniquely
named public JWKS file enters this exact emulator's discovered directory; it
is removed on exit without touching other keys. A valid status request plus
anonymous and wrong-audience rejection are required before injecting audio.
RPC/JWT details and metadata are never logged.

## Guest microphone gate

`AudioInjectionRunner` belongs to the test APK. Register it in the test
manifest as an additional instrumentation targeting `dev.lip.android`; the
parent owns that integration and APK build/install. It refuses physical
devices and requires the permission to be granted on the verified isolated
emulator. Its AudioRecord source/format match Lip's `PcmSource`:
VOICE_RECOGNITION, 16 kHz mono PCM16, privacy-sensitive.
It opens/finishes Lip's UI only inside that guest and requires every measured
window's active recording configuration to be present and unsilenced. Android
can otherwise substitute silence for background or lower-priority captures.
[Android input-sharing rules](https://developer.android.com/media/platform/sharing-audio-input)
describe that policy and its `isClientSilenced` check.

Run against the explicitly verified emulator serial, never an unqualified
`adb` target. These are parent-run commands, not host recording or playback:

```sh
ADB=/Users/jared/Library/Android/sdk/platform-tools/adb
SERIAL=VERIFIED_ISOLATED_EMULATOR_SERIAL
RUN=/Users/jared/Developer/Lip/validation/hillclimb/receipts
"$ADB" -s "$SERIAL" shell pm grant dev.lip.android android.permission.RECORD_AUDIO
"$ADB" -s "$SERIAL" shell am instrument -w -r \
  dev.lip.android.test/dev.lip.AudioInjectionRunner \
  > "$RUN/guest-audio-probe.instrument.txt" 2>&1 &
PROBE_PID=$!
```

Wait for `READY: guest AudioRecord started; unsilenced_window_samples=1600`
in that owned log, then run the
authenticated injector above with a frozen two-second 1 kHz sine WAV at 0.25
full-scale amplitude and `--duration-seconds 4`. The extra two seconds are
injected zeros. Do not reuse a speech fixture for this calibration gate. Wait
for the owned probe process with `wait "$PROBE_PID"`; success requires the
runner's JSON `passed: true` and instrumentation result code `-1`.

The runner holds capture through its 12-second/384,000-byte cap even after the
gate passes, keeping the guest input alive while the injection RPC restores
its driver. It requires five
consecutive 100 ms windows with RMS in `[0.05, 0.60]` full-scale and at least
85% of energy at 1 kHz, followed by ten consecutive all-zero windows. Other
tones, noise, mere nonzero bytes and silence alone cannot pass. This proves
that known audio reached guest AudioRecord and was followed by zero input;
it does not prove ASR, UI insertion or all emulator launch settings.

The result reports `pcm_path` and `receipt_path` under
`/data/user/0/dev.lip.android/cache/audio-injection-<unique>.pcm` and the sibling
`.json`. The JSON contains actual per-window RMS, tone energy and exact-zero
measurements. Keep both as disposable validation evidence, not Git artifacts.
The first full unsilenced window also emits route metadata: client/device
rate, channels, encoding and audio session/device IDs. The final JSON retains
that route; use actual values for any one-variable format experiment.
Retrieve the exact reported paths with the same explicit serial:

```sh
"$ADB" -s "$SERIAL" exec-out run-as dev.lip.android cat "$PCM_PATH" \
  > "$RUN/guest-audio-probe.pcm"
"$ADB" -s "$SERIAL" exec-out run-as dev.lip.android cat "$RECEIPT_PATH" \
  > "$RUN/guest-audio-probe.json"
```

## Checks

```sh
PYTHONDONTWRITEBYTECODE=1 "$PY" -B -m unittest discover \
  -s /Users/jared/Developer/Lip/eval/android -p 'test_*.py' -v
```

These tests cover input/ownership rules, PCM preservation, real local JWT
signing, public-file cleanup and simulated auth-denial handling. They do not
contact an emulator, recognize speech or certify actual authentication.
Initial RED rejected missing endpoint/WAV guards; review RED exposed localhost
resolution, truncated-stream masking, wrong listener hosts and WAV replacement.
The same assertions pass after repair.

The detector check compiles the actual owned Kotlin runner against the
resident Android-36 jar using cached Kotlin 2.3.10 tools and managed Homebrew
JDK 17, then runs only its detector on the JVM. It uses one CPU and a bounded
192 MiB compiler heap; no Gradle, native build, emulator or download occurs:

```sh
PYTHONDONTWRITEBYTECODE=1 "$PY" -B \
  /Users/jared/Developer/Lip/eval/android/check_tone_gate.py
```

Its intended RED reported `RMS must measure actual samples`. The same
assertions now cover 1 kHz plus silence, absent/other/mixed tones, noise,
amplitude bounds, phase, interrupted tone bursts and exact-zero timing. These
are deterministic JVM fixtures, not live microphone-delivery evidence.
