"""Authenticated fixed-tone calibration with a fresh, active guest recording handshake."""
import argparse
import json
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import threading
import time

import inject_audio as injector

CAPTURE_SECONDS = 12.0
STREAM_SECONDS = 4.0
DRAIN_SECONDS = 0.3
EXPECTED_BYTES = 128_000
ADB = injector.SDK / "platform-tools/adb"
TONE = Path("/Users/jared/Developer/Lip/validation/hillclimb/tone-1000hz.wav")
TONE_SHA256 = "17c0e211f28c04b75ba98ddc26eb87861021bc87d8f93a10f8a2fe360b030f79"
RUNNERS = {"direct": "dev.lip.AudioInjectionRunner", "pcm": "dev.lip.PcmToneRunner"}
READY = re.compile(r"^READY: guest AudioRecord started; unsilenced_window_samples=1600; pcm_path=/data/user/0/dev\.lip\.android/cache/audio-injection-[a-zA-Z0-9._-]+\.pcm$")


def ready_budget(launched_at, ready_at, finished, exitcode, now):
    if ready_at is None or finished or exitcode is not None or not launched_at <= ready_at <= now:
        raise ValueError("No fresh active full-window guest READY")
    budget = launched_at + CAPTURE_SECONDS - now - DRAIN_SECONDS
    if budget < STREAM_SECONDS:
        raise ValueError("Guest capture budget cannot cover the stream and drain")
    return budget


def validate_result(report, code, yielded_bytes, pipeline="direct"):
    if pipeline not in ("direct", "pcm"):
        raise ValueError("Unknown closed microphone pipeline")
    if yielded_bytes != EXPECTED_BYTES:
        raise ValueError("Injection transport did not yield the entire fixed fixture and tail")
    if (not isinstance(report, dict) or report.get("event") != "guest_audio_probe"
            or report.get("passed") is not True or report.get("tone_seen") is not True
            or not isinstance(report.get("silence_run"), int) or report["silence_run"] < 10 or code != -1):
        raise ValueError("Guest tone/silence proof or instrumentation result failed")
    if pipeline == "pcm":
        flags = ("eof_received", "reader_joined", "writer_joined", "cleanup_ok", "permission_unchanged",
                 "unsilenced_ready_window", "windowed_pcm_hash_matches_pipe")
        counts = ("accepted_bytes", "received_bytes", "captured_bytes", "captured_samples",
                  "ready_received_bytes", "source_errors", "capture_held_ms")
        if (report.get("pipeline") != "PcmSource/PcmWindow" or report.get("asr_tested") is not False
                or report.get("fixture_wav_sha256") != TONE_SHA256
                or any(report.get(key) is not True for key in flags)
                or any(type(report.get(key)) is not int for key in counts)
                or not 0 < report["accepted_bytes"] == report["received_bytes"] == report["captured_bytes"] <= 416_000
                or report["captured_samples"] * 2 != report["captured_bytes"]
                or not 3200 <= report["ready_received_bytes"] <= report["received_bytes"]
                or report["source_errors"] != 0 or report.get("reader_error") != "none"
                or report["capture_held_ms"] < 12_000
                or not isinstance(report.get("pcm_sha256"), str)
                or re.fullmatch(r"[0-9a-f]{64}", report["pcm_sha256"]) is None
                or report["pcm_sha256"] != report.get("windowed_pcm_sha256")):
            raise ValueError("Production PCM pipe/window EOF, count, hash or cleanup proof failed")
    return report


class GuestStream:
    """One reader owns the pipe throughout capture and final result delivery."""
    def __init__(self, process, output):
        self.process, self.output = process, output
        self.ready, self.done = threading.Event(), threading.Event()
        self.ready_at, self.report, self.code, self.error = None, None, None, None
        self.finished = False
        self.rpc = None
        self.thread = threading.Thread(target=self.drain, name="lip-guest-probe", daemon=True)
        self.thread.start()

    def drain(self):
        size = 0
        try:
            for line in self.process.stdout:
                size += len(line)
                if size > 1_048_576 or len(line) > 262_144:
                    raise ValueError("Guest output exceeds its bound")
                self.output.write(line); self.output.flush()
                text = line.strip()
                if READY.fullmatch(text):
                    if self.ready_at is not None:
                        raise ValueError("Guest emitted multiple READY markers")
                    self.ready_at = time.monotonic()
                    self.ready.set()
                if text.startswith("INSTRUMENTATION_RESULT:"):
                    self.finished = True
                    if self.rpc is not None and not self.rpc.done(): self.rpc.cancel()
                if text.startswith("{"):
                    report = json.loads(text)
                    if report.get("event") == "guest_audio_probe":
                        if self.report is not None: raise ValueError("Guest emitted multiple final results")
                        self.report = report
                match = re.fullmatch(r"INSTRUMENTATION_CODE: (-?\d+)", text)
                if match:
                    if self.code is not None: raise ValueError("Guest emitted multiple result codes")
                    self.code = int(match[1])
        except Exception as error:
            self.error = str(error) if isinstance(error, ValueError) else type(error).__name__
        finally:
            self.finished = True
            if self.rpc is not None and not self.rpc.done(): self.rpc.cancel()
            self.done.set(); self.ready.set()


def stop_owned(process):
    if process.poll() is None:
        process.terminate()
        try: process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            process.kill(); process.wait(timeout=2)


def run_probe(serial, stub, pb, headers, pcm, directory, pipeline="direct"):
    if pipeline not in ("direct", "pcm"):
        raise ValueError("Unknown closed microphone pipeline")
    if len(pcm) != injector.RATE * 2 * 2:
        raise ValueError("The fixed tone must contain exactly two seconds of PCM")
    process = guest = rpc = None
    yielded = 0
    timestamps = {}
    with (directory / "guest.log").open("x") as output, (directory / "events.jsonl").open("x") as events:
        def record(event, **values):
            events.write(json.dumps({"event": event, "monotonic": time.monotonic(), **values}) + "\n")
            events.flush()
        try:
            launched = time.monotonic()
            timestamps["launched_at"] = launched
            process = subprocess.Popen([str(ADB), "-s", serial, "shell", "am", "instrument", "-w", "-r",
                                        "dev.lip.android.test/" + RUNNERS[pipeline]],
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
            guest = GuestStream(process, output)
            if not guest.ready.wait(max(0, launched + CAPTURE_SECONDS - STREAM_SECONDS - DRAIN_SECONDS - time.monotonic())):
                raise ValueError("Guest READY did not arrive within the safe capture budget")
            now = time.monotonic()
            budget = ready_budget(launched, guest.ready_at, guest.finished, process.poll(), now)
            timestamps.update(ready_at=guest.ready_at, rpc_started_at=now)
            record("rpc_start", handoff_seconds=now - guest.ready_at)
            def packets():
                nonlocal yielded
                fmt = pb.AudioFormat(samplingRate=injector.RATE, channels=pb.AudioFormat.Mono,
                                     format=pb.AudioFormat.AUD_FMT_S16, mode=pb.AudioFormat.MODE_UNSPECIFIED)
                for chunk in injector.pcm_chunks(pcm, int(STREAM_SECONDS * injector.RATE)):
                    if guest.finished or process.poll() is not None or time.monotonic() >= launched + CAPTURE_SECONDS - DRAIN_SECONDS:
                        raise ValueError("Guest recording ended or its safe budget expired during injection")
                    yielded += len(chunk)
                    yield pb.AudioPacket(format=fmt, audio=chunk)
            rpc = stub.injectAudio.future(packets(), metadata=headers, timeout=budget)
            guest.rpc = rpc
            if guest.finished: rpc.cancel()
            rpc.result(timeout=budget)
            timestamps["rpc_completed_at"] = time.monotonic()
            record("rpc_complete", yielded_bytes=yielded, guest_delivery_proven=False)
            if not guest.done.wait(max(0, launched + CAPTURE_SECONDS + 5 - time.monotonic())):
                raise ValueError("Guest result/EOF deadline expired")
            process.wait(timeout=2)
            if guest.error is not None or process.returncode != 0:
                raise ValueError("Guest output or adb transport failed")
            report = validate_result(guest.report, guest.code, yielded, pipeline)
            result = {"passed": True, "yielded_bytes": yielded, "guest_delivery_proven": True,
                      "pipeline": pipeline, "timing": timestamps, "guest": report}
            (directory / "result.json").write_text(json.dumps(result))
            return result
        except BaseException:
            record("probe_failed", yielded_bytes=yielded, guest_delivery_proven=False)
            raise
        finally:
            if rpc is not None and not rpc.done():
                rpc.cancel()
                time.sleep(DRAIN_SECONDS)
            if process is not None:
                stop_owned(process)
            if guest is not None:
                guest.thread.join(timeout=2)
                if guest.thread.is_alive():
                    process.stdout.close(); guest.thread.join(timeout=2)
            if process is not None: process.stdout.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--emulator-pid", "--emulatorPID", type=int, required=True)
    parser.add_argument("--emulator-root", type=Path, default=injector.EMULATOR_ROOT)
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--discovery", type=Path, required=True)
    parser.add_argument("--avd-dir", type=Path, required=True)
    parser.add_argument("--bindings", type=Path, required=True)
    parser.add_argument("--log-dir", type=Path, required=True)
    parser.add_argument("--pipeline", choices=("direct", "pcm"), default="direct")
    args = parser.parse_args()
    pin = injector.runtime_provenance(args.emulator_root)
    if (args.bindings / "proto.sha256").read_text().strip() != pin["lib/emulator_controller.proto"]:
        raise ValueError("Bindings do not match the selected emulator proto")
    pcm = injector.wav_pcm(TONE, TONE_SHA256)
    entries = injector.read_discovery(args.discovery, args.emulator_pid, args.avd_dir, args.endpoint)
    injector.verify_live_target(args.emulator_pid, entries, args.endpoint, args.emulator_root)
    try: port = int(entries["port.serial"])
    except (KeyError, ValueError): raise ValueError("Invalid discovered guest console port") from None
    if not 1 <= port <= 65_535: raise ValueError("Invalid discovered guest console port")
    listener = subprocess.run(["/usr/sbin/lsof", "-nP", "-a", "-p", str(args.emulator_pid),
                               f"-iTCP:{port}", "-sTCP:LISTEN", "-Fn"],
                              check=True, capture_output=True, text=True, timeout=5)
    injector.validate_listener(listener.stdout, f"127.0.0.1:{port}")
    args.log_dir.mkdir(parents=True, exist_ok=True)
    injector.owned_path(args.log_dir, directory=True)
    directory = Path(tempfile.mkdtemp(prefix="audio-probe-", dir=args.log_dir))
    print(json.dumps({"event": "probe_artifacts", "run_dir": str(directory), "emulator_version": pin["version"]}), flush=True)
    sys.path.insert(0, str(args.bindings.resolve(strict=True)))
    import grpc
    import emulator_controller_pb2 as pb
    import emulator_controller_pb2_grpc as bindings
    from google.protobuf.empty_pb2 import Empty
    def interrupted(*_): raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    with injector.jwt_identity(entries) as identity, grpc.insecure_channel(args.endpoint) as channel:
        stub = bindings.EmulatorControllerStub(channel)
        injector.auth_checks(stub, Empty(), identity, grpc)
        injector.verify_live_target(args.emulator_pid, entries, args.endpoint, args.emulator_root)
        headers = injector.metadata(identity, injector.METHOD + "injectAudio", 60)
        result = run_probe(f"emulator-{port}", stub, pb, headers, pcm, directory, args.pipeline)
    print(json.dumps({"event": "probe_complete", "run_dir": str(directory), "passed": result["passed"]}), flush=True)


if __name__ == "__main__":
    try: main()
    except (Exception, KeyboardInterrupt) as error:
        message = str(error) if isinstance(error, ValueError) else type(error).__name__
        print(json.dumps({"event": "probe_failed", "error": message}), file=sys.stderr)
        sys.exit(1)
