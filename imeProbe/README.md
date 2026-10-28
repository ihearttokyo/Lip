# Isolated emulator startup observer

Test-only APK, separate UID from both `dev.lip.android` and its synthetic editor
`dev.lip.android.test`. Starting/finishing its instrumentation does not force-stop
either target. It has no Internet/microphone/storage permissions, app UI or release
variant, and is never packaged inside Lip. Owned `ranchu` emulator only.

Build with the repository's Java17/SDK36 offline toolchain:

    ./gradlew :imeProbe:assembleDebug :imeProbe:lintDebug --offline

On owned `emulator-5584` only, after verifying `ro.kernel.qemu == 1`, install the
observer with `adb -s emulator-5584 install -r -t` and its debug APK path. Lip and
its debug editor fixture must already be installed and deliberately selected;
this helper never changes selection or OS permissions.

    adb -s emulator-5584 shell am instrument -w -e launch fresh dev.lip.imeprobe/.StartupObserver

Fresh mode awaits its unique fixture marker, touches the ordinary editor once,
observes all accessibility windows, synchronizes UI idle and reacquires key bounds,
then touches a once and verifies exact editor text. Existing mode never resets
focus or launches an editor; supply `-e expected` for the known synthetic text
plus one a. Neither mode calls showSoftInput or restartInput. CODE -1 without a
failure is a named-case pass only. Visibility and ready timestamps are diagnostics,
not latency or device acceptance.

`python3 imeProbe/run_ordinary.py <new-label>` runs a fresh baseline then six fixed
same-editor ordinary process-death trials. It verifies each exact own debug PID
before SIGKILL, never force-stops/reinstalls, and aborts on first failure while
recording remaining trials NOT_RUN. Rapid kills can invoke Android restart backoff;
that is not a reliability benchmark or permission to lengthen a failed timeout.
Missing PID after failure is represented as null, not dropped from results.

This observer does not replace physical-phone, initial-enable, post-update,
third-party-editor, no-send, accessibility or whole-product acceptance gates.
