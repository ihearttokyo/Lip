#!/usr/bin/env bash
# Diagnostic collection only; the parent evaluates original cancellation and quality gates.
set -euo pipefail
refuse() { printf 'REFUSED: %s\n' "$*" >&2; exit 1; }
[[ "$(uname -s)" == Linux ]] || refuse 'Linux is required; no SDK or network action taken.'
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_OS:-}" == Linux ]] || refuse 'GitHub Linux context is required.'
[[ "${GITHUB_REPOSITORY:-}" == ihearttokyo/Lip ]] || refuse 'Only ihearttokyo/Lip is authorized.'
[[ "${RUNNER_ENVIRONMENT:-}" == github-hosted ]] || refuse 'Only the parent-approved hosted runner is authorized.'
[[ "${GITHUB_SERVER_URL:-}" == https://github.com && "${RUNNER_ARCH:-}" == X64 ]] || refuse 'GitHub.com x64 runner is required.'
[[ $# == 0 ]] || refuse 'This single frozen trial takes no arguments.'
[[ "${GITHUB_RUN_ID:-}" =~ ^[0-9]+$ && "${GITHUB_RUN_ATTEMPT:-}" =~ ^[0-9]+$ ]] || refuse 'Missing run identity.'
grep -qx 'ID=ubuntu' /etc/os-release && grep -qx 'VERSION_ID="24.04"' /etc/os-release || refuse 'Ubuntu 24.04 is required.'
[[ -r /dev/kvm && -w /dev/kvm ]] || refuse 'Readable/writable KVM is required; no permission changes or fallback.'
[[ -d "${RUNNER_TEMP:-}" && -d "${GITHUB_WORKSPACE:-}" ]] || refuse 'Runner paths are missing.'
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."
[[ "$PWD" == "$(realpath "$GITHUB_WORKSPACE")" ]] || refuse 'Script must belong to the checked-out workspace.'
[[ "$(git rev-parse HEAD)" == "${GITHUB_SHA:-}" ]] || refuse 'Checkout does not match the workflow SHA.'
for command in timeout curl sha256sum ss; do command -v "$command" >/dev/null || refuse "Missing $command."; done
ports="$(ss -H -ltn '( sport = :5580 or sport = :5581 or sport = :5038 )')" || refuse 'Could not inspect required ports.'
[[ -z "$ports" ]] || refuse 'Console, transport or isolated ADB port is occupied.'

sdk="${ANDROID_HOME:?Installed SDK is required}"
image="$sdk/system-images/android-36/default/x86_64"
adb="$sdk/platform-tools/adb"
emulator="$sdk/emulator/emulator"
avdmanager="$sdk/cmdline-tools/latest/bin/avdmanager"
for tool in "$adb" "$emulator" "$avdmanager"; do [[ -x "$tool" ]] || refuse "Missing installed tool: $tool"; done
grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*37\.2\.12[[:space:]]*$' "$sdk/emulator/source.properties" || refuse 'Installed emulator is not 37.2.12.'
grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*2[[:space:]]*$' "$image/source.properties" || refuse 'Installed system image is not revision 2.'
grep -Eq '^AndroidVersion.ApiLevel[[:space:]]*=[[:space:]]*36[[:space:]]*$' "$image/source.properties" || refuse 'Installed image is not API 36.'
grep -Eq '^SystemImage.Abi[[:space:]]*=[[:space:]]*x86_64[[:space:]]*$' "$image/source.properties" || refuse 'Installed image is not x86_64.'
grep -Eq '^SystemImage.TagId[[:space:]]*=[[:space:]]*default[[:space:]]*$' "$image/source.properties" || refuse 'Installed image is not AOSP default.'
app=app/build/outputs/apk/debug/app-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
pcm=eval/android/fixtures/fleurs-en-013.pcm
pcm_sha=e42fdeed81feac1d9d660e888ceb0351e785760f24ec7b8e9d8420cfe96a800f
model_leaf=ggml-large-v3-turbo-q5_0.bin
model_sha=394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2
[[ -f "$app" && -f "$test_apk" && "$(stat -c %s "$pcm")" == 241280 ]] || refuse 'Matched APK outputs or fixed PCM are missing.'
printf '%s  %s\n' "$pcm_sha" "$pcm" '68e93d689ca935116ae4a21aaf2b0c1878f3d0c14921b75bb3d6d4b2041d8791' app/src/androidTest/java/dev/lip/LocalAsrRunner.kt | sha256sum --check

out="$RUNNER_TEMP/lip-cancel-diagnostic"
[[ ! -e "$out" && ! -L "$out" ]] || refuse 'Diagnostic output must be fresh.'
mkdir -- "$out"
runtime="$(mktemp -d "$RUNNER_TEMP/lip-cancel.XXXXXX")"
export HOME="$runtime/home" ANDROID_USER_HOME="$runtime/android-user" ANDROID_EMULATOR_HOME="$runtime/android-user" ANDROID_AVD_HOME="$runtime/avd"
export ANDROID_SDK_ROOT="$sdk" ANDROID_ADB_SERVER_PORT=5038 ADB_SERVER_SOCKET=tcp:localhost:5038
unset ADB_VENDOR_KEYS
mkdir -- "$HOME" "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME"
avd="LipCancel_${GITHUB_RUN_ID}_${GITHUB_RUN_ATTEMPT}_${runtime##*.}"
serial=emulator-5580
emulator_pid=
cleanup() {
    local status=$? name stopped=false
    trap - EXIT INT TERM
    set +e
    if [[ -n "$emulator_pid" ]]; then
        for ((attempt=0; attempt<6; attempt++)); do
            if ! kill -0 "$emulator_pid" 2>/dev/null && ports="$(ss -H -ltn '( sport = :5580 or sport = :5581 )')" && [[ -z "$ports" ]]; then
                printf 'owned_guest_already_exited=true\n' >>"$out/teardown.txt"
                stopped=true; break
            fi
            name="$(timeout 5s "$adb" -s "$serial" emu avd name 2>>"$out/teardown.txt")"
            name="${name//$'\r'/}"
            name="${name%%$'\n'*}"
            if [[ "$name" == "$avd" ]]; then
                printf 'owned_serial=%s owned_avd=%s launched_pid=%s\n' "$serial" "$name" "$emulator_pid" >>"$out/teardown.txt"
                if timeout 10s "$adb" -s "$serial" emu kill >>"$out/teardown.txt" 2>&1; then
                    for ((check=0; check<15; check++)); do
                        if ! kill -0 "$emulator_pid" 2>/dev/null && ports="$(ss -H -ltn '( sport = :5580 or sport = :5581 )')" && [[ -z "$ports" ]]; then
                            stopped=true; break
                        fi
                        sleep 2
                    done
                fi
                break
            fi
            sleep 2
        done
        printf 'owned_guest_stopped=%s\n' "$stopped" >>"$out/teardown.txt"
        if [[ "$stopped" != true ]]; then
            printf 'Teardown incomplete; no unproved target or forced termination used.\n' >&2
            status=1
        fi
    else
        printf 'guest_not_started=true\n' >>"$out/teardown.txt"
    fi
    printf 'collector_exit=%s\nacceptance=not_evaluated\n' "$status" >>"$out/collection.txt"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

{
    printf 'repository=%s\nsource_sha=%s\nrun_id=%s\nrun_attempt=%s\n' "$GITHUB_REPOSITORY" "$GITHUB_SHA" "$GITHUB_RUN_ID" "$GITHUB_RUN_ATTEMPT"
    printf 'hosted_image=%s\nhosted_image_version=%s\navd=%s\nserial=%s\n' "${ImageOS:-unknown}" "${ImageVersion:-unknown}" "$avd" "$serial"
    printf 'primary_emulator_archive=emulator-linux_x64-16428233.zip sha1=cd7362ea55dfb86a418958138dc396e74165dd01\nprimary_image_archive=x86_64-36_r02.zip sha1=829c076e8ff448a336097ae25a355b495ba36e2c\n'
    printf 'cohort_native_workers=1\nstable_witness_ms=2000\ncleanup_acceptance_ms=15000\ndiagnostic_join_ms=120000\nhost_instrumentation_ceiling_s=450\nacceptance=not_evaluated\n'
    uname -srvmo
    printf 'host_online_cpus=%s\n' "$(getconf _NPROCESSORS_ONLN)"
    cat /etc/os-release
    stat -c 'kvm_mode=%a kvm_owner=%U kvm_group=%G' /dev/kvm
    git submodule status third_party/whisper.cpp
} >"$out/identity.txt"
sha256sum "$app" "$test_apk" "$pcm" eval/android/run_cancel_ci.sh app/src/androidTest/java/dev/lip/LocalAsrRunner.kt \
    app/src/main/cpp/CMakeLists.txt app/src/main/cpp/whisper_jni.cpp app/src/main/java/dev/lip/speech/ModelFile.kt \
    third_party/whisper.cpp/src/whisper.cpp >"$out/source-apk-sha256.txt"
find "$sdk/emulator" "$image" -type f -print0 | sort -z | xargs -0 sha256sum >"$out/sdk-sha256.txt"
cat "$sdk/emulator/source.properties" "$image/source.properties" >"$out/sdk-source-properties.txt"
timeout 20s "$emulator" -version >"$out/emulator-version.txt" 2>&1
grep -q '37\.2\.12' "$out/emulator-version.txt" || refuse 'Executable emulator version disagrees.'
timeout 20s "$adb" version >"$out/adb-version.txt" 2>&1
timeout 20s "$adb" devices -l >"$out/preexisting-devices.txt" 2>"$out/adb-start.stderr.txt"
[[ -z "$(sed '1d' "$out/preexisting-devices.txt" | tr -d '[:space:]')" ]] || refuse 'Preexisting ADB device detected.'
[[ ! -e "$ANDROID_AVD_HOME/$avd.ini" && ! -e "$ANDROID_AVD_HOME/$avd.avd" ]] || refuse 'AVD target already exists.'

# No redirect headers, URLs, server bodies or credentials enter diagnostic artifacts.
set +e
curl --silent --fail --location --proto '=https' --proto-redir '=https' --max-redirs 5 \
    --connect-timeout 30 --max-time 600 --speed-limit 1048576 --speed-time 60 --max-filesize 574041195 \
    --output "$runtime/$model_leaf" \
    'https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-large-v3-turbo-q5_0.bin' 2>/dev/null
download_status=$?
set -e
printf 'model_download_exit=%s\n' "$download_status" >>"$out/collection.txt"
[[ "$download_status" == 0 && "$(stat -c %s "$runtime/$model_leaf")" == 574041195 ]] || refuse 'Pinned public model transfer failed or size differs.'
printf '%s  %s\n' "$model_sha" "$runtime/$model_leaf" | sha256sum --check >"$out/model-check.txt"
sha256sum "$runtime/$model_leaf" >>"$out/source-apk-sha256.txt"

printf 'no\n' | timeout 60s "$avdmanager" create avd --name "$avd" --package 'system-images;android-36;default;x86_64' >"$out/avd-create.txt" 2>&1
[[ -f "$ANDROID_AVD_HOME/$avd.ini" && -d "$ANDROID_AVD_HOME/$avd.avd" ]] || refuse 'Isolated AVD was not created.'
sha256sum "$ANDROID_AVD_HOME/$avd.ini" "$ANDROID_AVD_HOME/$avd.avd/config.ini" >"$out/avd-config-sha256.txt"
"$emulator" -avd "$avd" -port 5580 -cores 2 -memory 2048 -accel on -no-window -no-snapshot -read-only \
    -gpu swiftshader -no-audio -no-boot-anim -camera-back none -camera-front none >"$out/emulator.stdout.txt" 2>"$out/emulator.stderr.txt" &
emulator_pid=$!
printf 'launched_pid=%s\n' "$emulator_pid" >>"$out/identity.txt"
boot_deadline=$((SECONDS + 240))
booted=false
while ((SECONDS < boot_deadline)); do
    kill -0 "$emulator_pid" 2>/dev/null || refuse 'Owned emulator process exited before boot.'
    if [[ "$(timeout 5s "$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then booted=true; break; fi
    sleep 5
done
[[ "$booted" == true ]] || refuse 'Boot exceeded 240 seconds.'
guest() { timeout 120s "$adb" -s "$serial" "$@"; }
guest emu avd name >"$out/guest-avd.txt"
[[ "$(head -n 1 "$out/guest-avd.txt" | tr -d '\r')" == "$avd" ]] || refuse 'Guest AVD identity differs.'
guest shell cat /sys/devices/system/cpu/online >"$out/guest-online-cpus.txt"
[[ "$(tr -d '\r\n' <"$out/guest-online-cpus.txt")" == 0-1 ]] || refuse 'Guest must have exactly online CPUs 0-1.'
guest shell getprop ro.build.fingerprint >"$out/guest-build.txt"
guest shell getprop ro.product.cpu.abi >>"$out/guest-build.txt"
guest install -r "$app" >"$out/install-app.txt" 2>&1
guest install -r "$test_apk" >"$out/install-test.txt" 2>&1
guest shell pm list instrumentation >"$out/instrumentation-components.txt"
grep -Fq 'dev.lip.android.test/dev.lip.LocalAsrRunner' "$out/instrumentation-components.txt" || refuse 'Correct instrumentation is not installed.'
guest_stage="/data/local/tmp/$avd"
guest shell mkdir "$guest_stage" >"$out/guest-mkdir.txt" 2>&1
guest push "$pcm" "$guest_stage/fleurs-en-013.pcm" >"$out/push-pcm.txt" 2>&1
guest push "$runtime/$model_leaf" "$guest_stage/$model_leaf" >"$out/push-model.txt" 2>&1
guest shell run-as dev.lip.android test ! -e no_backup/asr-fixtures
guest shell run-as dev.lip.android mkdir -p no_backup/asr-fixtures
guest shell run-as dev.lip.android cp "$guest_stage/fleurs-en-013.pcm" no_backup/asr-fixtures/fleurs-en-013.pcm
guest shell run-as dev.lip.android cp "$guest_stage/$model_leaf" "no_backup/asr-fixtures/$model_leaf"
guest shell run-as dev.lip.android sha256sum no_backup/asr-fixtures/fleurs-en-013.pcm "no_backup/asr-fixtures/$model_leaf" | tr -d '\r' >"$out/guest-input-sha256.txt"
printf '%s  %s\n' "$pcm_sha" no_backup/asr-fixtures/fleurs-en-013.pcm "$model_sha" "no_backup/asr-fixtures/$model_leaf" >"$out/expected-guest-input-sha256.txt"
diff -u "$out/expected-guest-input-sha256.txt" "$out/guest-input-sha256.txt"

printf 'mode=cancel_active\nmodel=%s\nmodelSha256=%s\npcm=fleurs-en-013.pcm\npcmSha256=%s\nlanguage=en\nprompt=\ncomponent=dev.lip.android.test/dev.lip.LocalAsrRunner\n' "$model_leaf" "$model_sha" "$pcm_sha" >"$out/instrumentation-inputs.txt"
set +e
# This hard ceiling ends only this owned host client; the guest stops through its console.
timeout --foreground --signal=KILL 450s "$adb" -s "$serial" shell am instrument -w -r -e mode cancel_active \
    -e model "$model_leaf" -e modelSha256 "$model_sha" -e pcm fleurs-en-013.pcm -e pcmSha256 "$pcm_sha" \
    -e language en -e prompt "''" dev.lip.android.test/dev.lip.LocalAsrRunner >"$out/instrumentation.stdout.txt" 2>"$out/instrumentation.stderr.txt"
instrumentation_status=$?
set -e
printf 'adb_instrumentation_exit=%s\nacceptance=not_evaluated\n' "$instrumentation_status" >>"$out/collection.txt"
cat "$out/instrumentation.stdout.txt"
cat "$out/instrumentation.stderr.txt" >&2
grep -E 'ASR_RESULT |ASR_CANCEL_HOLD ' "$out/instrumentation.stdout.txt" >"$out/raw-report-lines.txt" || refuse 'No raw runner result/hold was collected; inspect preserved output.'
printf 'Collector complete. Acceptance NOT EVALUATED. adb exit=%s (including 0) does not prove test success.\n' "$instrumentation_status"
