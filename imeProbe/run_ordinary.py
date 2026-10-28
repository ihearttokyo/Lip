"""Six fixed owned-emulator process-death controls, no per-case retry.

Install the test-only observer and fixture first. This does not test installation,
package replacement, physical devices or latency. All editor text is synthetic.
"""
import argparse
import json
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent
ADB = pathlib.Path.home() / "Library/Android/sdk/platform-tools/adb"

def require(ok, message):
    if not ok:
        raise RuntimeError(message)

def run(*args):
    return subprocess.check_output([str(ADB), "-s", "emulator-5584", *args], text=True).strip()

def current_pid():
    result = subprocess.run([str(ADB), "-s", "emulator-5584", "shell", "pidof", "dev.lip.android"], text=True, capture_output=True)
    require(result.returncode in (0, 1), "Could not observe app PID")
    return result.stdout.strip() or None

def passed(report):
    return "INSTRUMENTATION_CODE: -1" in report and "failure=" not in report

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("label", help="New evidence directory name, never overwritten")
    args = parser.parse_args()
    require(re.fullmatch(r"[a-z0-9-]{1,48}", args.label), "Use a short directory label")
    require(run("shell", "getprop", "ro.kernel.qemu") == "1", "Owned emulator only")
    out = ROOT / "validation/ime-startup-20261027" / args.label
    out.mkdir(parents=True, exist_ok=False)
    selected = run("shell", "settings", "get", "secure", "default_input_method")
    require(selected == "dev.lip.android/dev.lip.keyboard.LipInputMethodService", "Lip must already be deliberately selected; this tool never changes selection")
    cases = [{"trial": i, "status": "NOT_RUN"} for i in range(1, 7)]
    def save():
        (out / "results.json").write_text(json.dumps(cases, indent=2) + "\n")
    save()
    try:
        baseline = run("shell", "am", "instrument", "-w", "-e", "launch", "fresh", "dev.lip.imeprobe/.StartupObserver")
        (out / "baseline.log").write_text(baseline + "\n")
        require(passed(baseline), "Baseline failed; no process killed")
        for case in cases:
            trial = case["trial"]
            directory = out / f"trial{trial}"
            directory.mkdir()
            pid = current_pid()
            require(pid is not None and pid.isdecimal(), "Own IME process must be live before killing")
            command = run("shell", "run-as", "dev.lip.android", "cat", f"/proc/{pid}/cmdline").replace("\x00", "")
            require(command == "dev.lip.android", "Refusing non-own process")
            (directory / "before-services.txt").write_text(run("shell", "dumpsys", "activity", "services", "dev.lip.android"))
            case.update(status="IN_PROGRESS", old_pid=pid)
            save()
            # Same verified debug UID; exact PID only, never force-stop or change system settings.
            run("shell", "run-as", "dev.lip.android", "kill", "-9", pid)
            report = run("shell", "am", "instrument", "-w", "-e", "launch", "existing", "-e", "expected", "a" * (trial + 1), "dev.lip.imeprobe/.StartupObserver")
            (directory / "runtime.log").write_text(report + "\n")
            case["status"] = "PASS" if passed(report) else "FAIL"
            save()  # Failure is durable even if subsequent PID/diagnostic collection fails.
            case["new_pid"] = current_pid()  # None is valid when restart is still pending.
            save()
            for label, command_args in [("input-method", ("input_method",)), ("services", ("activity", "services", "dev.lip.android"))]:
                (directory / ("after-" + label + ".txt")).write_text(run("shell", "dumpsys", *command_args))
            require(case["status"] == "PASS", "Trial failed; remaining cases NOT_RUN; no retries")
    finally:
        (out / "logcat.txt").write_text(run("shell", "logcat", "-d", "-t", "2500"))
        require(selected == run("shell", "settings", "get", "secure", "default_input_method"), "Selection changed unexpectedly")

if __name__ == "__main__":
    main()
