"""Fixed real-audio Main cursor-flow canary; no general speech or cross-app claim."""
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
from probe_audio import stop_owned

ADB = injector.SDK / "platform-tools/adb"
WAV = Path("/Users/jared/Developer/Lip/validation/hillclimb/fleurs-en-013-pcm16.wav")
WAV_SHA256 = "7e8b283e4e2c832f0e07c48ce883c45744c7de73084bd7b65a4cf19ee5a549a1"
SOURCE_SHA256 = "431d013d01d25cbf8d42587f4a14df57d09dbe9cbc2af4ea91ba93f9bd290ec4"
MODEL_SHA256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"
REFERENCE = "Although three people were inside the house when the car impacted it, none of them were hurt."
FIXTURE_FRAMES, TAIL_FRAMES = 120_640, 20_000
EXPECTED_BYTES = (FIXTURE_FRAMES + TAIL_FRAMES) * 2
STREAM_SECONDS, CAPTURE_SECONDS, DRAIN_SECONDS = 8.79, 9.54, 0.3
READY_TIMEOUT, RESULT_TIMEOUT = 75.0, 165.0  # Observe timeout diagnostics; the original guest deadline still fails.


def validate_ready(report):
    if (not isinstance(report, dict) or report.get("scope") != "Main in-app cursor flow"
            or report.get("fixture") != "fleurs-en-013" or report.get("fixture_source_sha256") != SOURCE_SHA256
            or report.get("model_verified") is not True or report.get("model_sha256") != MODEL_SHA256
            or report.get("stream_ms") != 7540 or report.get("phase") != "LISTENING"
            or report.get("microphone_recording") is not True or report.get("client_silenced") is not False
            or type(report.get("microphone_accepted_bytes")) is not int or report["microphone_accepted_bytes"] < 3200
            or type(report.get("ready_elapsed_ns")) is not int or report["ready_elapsed_ns"] <= 0
            or report.get("ready_elapsed_ms") != report["ready_elapsed_ns"] // 1_000_000):
        raise ValueError("Guest READY lacks verified model and active full-window microphone proof")
    return report


def ready_budget(launched_at, ready_at, finished, exitcode, now):
    if ready_at is None or finished or exitcode is not None or not launched_at <= ready_at <= now:
        raise ValueError("No fresh active LOCAL_FLOW_READY")
    budget = ready_at + CAPTURE_SECONDS - now - DRAIN_SECONDS
    if budget < STREAM_SECONDS:
        raise ValueError("Guest capture budget cannot cover the fixed speech and tail")
    return budget


def validate_result(report, code, yielded_bytes):
    if yielded_bytes != EXPECTED_BYTES:
        raise ValueError("Injection transport did not yield the entire speech fixture and tail")
    required = ("passed", "model_verified", "reference_words_once", "native_preview_matches_raw",
                "no_auto_insert", "selected_field_replacement", "reopened_encrypted_history_matches_raw",
                "on_destroy_closes_owned_setup", "cleanup_ok")
    if (not isinstance(report, dict) or any(report.get(key) is not True for key in required)
            or report.get("scope") != "Main in-app cursor flow" or report.get("fixture") != "fleurs-en-013"
            or report.get("fixture_source_sha256") != SOURCE_SHA256 or report.get("model_sha256") != MODEL_SHA256
            or report.get("stream_ms") != 7540 or report.get("phase") != "IDLE" or report.get("ready_phase") != "READY"
            or type(report.get("capture_after_ready_ms")) is not int or report["capture_after_ready_ms"] < 9540
            or type(report.get("new_history_entries")) is not int or report["new_history_entries"] != 1
            or any(report.get(key) is not False for key in ("bubble_tested", "cross_app_tested", "speech_perfection_claim"))
            or not isinstance(report.get("raw"), str)
            or re.findall(r"[a-z0-9]+", report["raw"].lower()) != re.findall(r"[a-z0-9]+", REFERENCE.lower())
            or code != -1):
        raise ValueError("Native transcript, UI, history, cleanup or instrumentation proof failed")
    return report


class FlowStream:
    """One bounded stdout reader owns READY, final result and RPC cancellation."""
    def __init__(self, process, output):
        self.process, self.output = process, output
        self.ready, self.done = threading.Event(), threading.Event()
        self.ready_at, self.report, self.code, self.error = None, None, None, None
        self.finished, self.rpc = False, None
        self.thread = threading.Thread(target=self.drain, name="lip-local-flow", daemon=True)
        self.thread.start()

    def drain(self):
        size = 0
        try:
            while True:
                line = self.process.stdout.readline(262_145)
                if not line: break
                size += len(line.encode("utf-8"))
                if size > 1_048_576 or len(line) > 262_144:
                    raise ValueError("Guest flow output exceeds its bound")
                self.output.write(line); self.output.flush()
                text = line.strip()
                if text.startswith("LOCAL_FLOW_READY "):
                    if self.ready_at is not None: raise ValueError("Guest emitted duplicate flow READY")
                    validate_ready(json.loads(text.removeprefix("LOCAL_FLOW_READY ")))
                    self.ready_at = time.monotonic()
                    self.ready.set()
                if text.startswith("INSTRUMENTATION_RESULT:"):
                    self.finished = True
                    if self.rpc is not None and not self.rpc.done(): self.rpc.cancel()
                if text.startswith("LOCAL_FLOW_RESULT "):
                    if self.report is not None: raise ValueError("Guest emitted duplicate flow result")
                    self.report = json.loads(text.removeprefix("LOCAL_FLOW_RESULT "))
                match = re.fullmatch(r"INSTRUMENTATION_CODE: (-?\d+)", text)
                if match:
                    if self.code is not None: raise ValueError("Guest emitted duplicate instrumentation code")
                    self.code = int(match[1])
        except Exception as error:
            self.error = str(error) if isinstance(error, ValueError) else type(error).__name__
        finally:
            self.finished = True
            if self.rpc is not None and not self.rpc.done(): self.rpc.cancel()
            self.done.set(); self.ready.set()


def run_flow(serial, stub, pb, headers, pcm, directory):
    if len(pcm) != FIXTURE_FRAMES * 2:
        raise ValueError("The fixed speech fixture must contain exactly 120640 PCM16 frames")
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
                "-e", "fixture_sha256", SOURCE_SHA256, "-e", "stream_ms", "7540",
                "dev.lip.android.test/dev.lip.LocalFlowRunner"],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
            guest = FlowStream(process, output)
            if not guest.ready.wait(max(0, launched + READY_TIMEOUT - time.monotonic())):
                raise ValueError("Guest flow READY deadline expired")
            now = time.monotonic()
            budget = ready_budget(launched, guest.ready_at, guest.finished, process.poll(), now)
            deadline = guest.ready_at + CAPTURE_SECONDS - DRAIN_SECONDS
            timestamps.update(ready_at=guest.ready_at, rpc_started_at=now)
            record("rpc_start", handoff_seconds=now - guest.ready_at)
            def packets():
                nonlocal yielded
                fmt = pb.AudioFormat(samplingRate=injector.RATE, channels=pb.AudioFormat.Mono,
                                     format=pb.AudioFormat.AUD_FMT_S16, mode=pb.AudioFormat.MODE_UNSPECIFIED)
                for chunk in injector.pcm_chunks(pcm, FIXTURE_FRAMES + TAIL_FRAMES):
                    if guest.finished or process.poll() is not None or time.monotonic() >= deadline:
                        raise ValueError("Guest flow ended or its active capture budget expired")
                    yielded += len(chunk)
                    yield pb.AudioPacket(format=fmt, audio=chunk)
            rpc = stub.injectAudio.future(packets(), metadata=headers, timeout=budget)
            guest.rpc = rpc
            if guest.finished: rpc.cancel()
            rpc.result(timeout=budget)
            timestamps["rpc_completed_at"] = time.monotonic()
            record("rpc_complete", yielded_bytes=yielded, native_flow_proven=False)
            if not guest.done.wait(max(0, guest.ready_at + CAPTURE_SECONDS + RESULT_TIMEOUT - time.monotonic())):
                raise ValueError("Guest native flow result/EOF deadline expired")
            process.wait(timeout=2)
            if guest.error is not None or process.returncode != 0:
                raise ValueError("Guest flow output or adb transport failed")
            report = validate_result(guest.report, guest.code, yielded)
            result = {"passed": True, "yielded_bytes": yielded, "native_flow_proven": True,
                      "wav_sha256": WAV_SHA256, "source_sha256": SOURCE_SHA256,
                      "timing": timestamps, "guest": report}
            with (directory / "result.json").open("x") as final: json.dump(result, final)
            return result
        except BaseException:
            record("flow_failed", yielded_bytes=yielded, native_flow_proven=False)
            raise
        finally:
            if rpc is not None and not rpc.done():
                rpc.cancel(); time.sleep(DRAIN_SECONDS)
            if process is not None: stop_owned(process)
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
    args = parser.parse_args()
    pin = injector.runtime_provenance(args.emulator_root)
    if (args.bindings / "proto.sha256").read_text().strip() != pin["lib/emulator_controller.proto"]:
        raise ValueError("Bindings do not match the selected emulator proto")
    pcm = injector.wav_pcm(WAV, WAV_SHA256)
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
    directory = Path(tempfile.mkdtemp(prefix="audio-flow-", dir=args.log_dir))
    print(json.dumps({"event": "flow_artifacts", "run_dir": str(directory), "emulator_version": pin["version"]}), flush=True)
    sys.path.insert(0, str(args.bindings.resolve(strict=True)))
    import grpc
    import emulator_controller_pb2 as pb
    import emulator_controller_pb2_grpc as bindings
    from google.protobuf.empty_pb2 import Empty
    def interrupted(*_): raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, interrupted); signal.signal(signal.SIGINT, interrupted)
    with injector.jwt_identity(entries) as identity, grpc.insecure_channel(args.endpoint) as channel:
        stub = bindings.EmulatorControllerStub(channel)
        injector.auth_checks(stub, Empty(), identity, grpc)
        injector.verify_live_target(args.emulator_pid, entries, args.endpoint, args.emulator_root)
        headers = injector.metadata(identity, injector.METHOD + "injectAudio", 90)
        run_flow(f"emulator-{port}", stub, pb, headers, pcm, directory)
    print(json.dumps({"event": "flow_complete", "run_dir": str(directory), "passed": True}), flush=True)


if __name__ == "__main__":
    try: main()
    except (Exception, KeyboardInterrupt) as error:
        message = str(error) if isinstance(error, ValueError) else type(error).__name__
        print(json.dumps({"event": "flow_failed", "error": message}), file=sys.stderr)
        sys.exit(1)
