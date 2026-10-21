"""Simulated subprocess/RPC protocol checks; no adb, emulator, microphone or ASR calls."""
import io
import json
from contextlib import contextmanager
from pathlib import Path
import subprocess
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import probe_audio as probe

PASSED = {"event": "guest_audio_probe", "passed": True, "tone_seen": True, "silence_run": 10}
PCM_PASSED = {**PASSED, "pipeline": "PcmSource/PcmWindow", "asr_tested": False,
    "fixture_wav_sha256": "17c0e211f28c04b75ba98ddc26eb87861021bc87d8f93a10f8a2fe360b030f79",
    "eof_received": True, "reader_joined": True, "writer_joined": True, "cleanup_ok": True,
    "permission_unchanged": True, "unsilenced_ready_window": True,
    "windowed_pcm_hash_matches_pipe": True, "pcm_sha256": "a" * 64, "windowed_pcm_sha256": "a" * 64,
    "accepted_bytes": 384000, "received_bytes": 384000, "captured_bytes": 384000,
    "captured_samples": 192000, "ready_received_bytes": 3200, "source_errors": 0,
    "reader_error": "none", "capture_held_ms": 12000}
MARKER = "READY: guest AudioRecord started; unsilenced_window_samples=1600; pcm_path=/data/user/0/dev.lip.android/cache/audio-injection-test.pcm\n"
PCM = bytes(range(256)) * 250


class FakePipe:
    def __init__(self, process, marker, report, code):
        self.process, self.marker, self.report, self.code = process, marker, report, code
    def __iter__(self):
        if self.marker: yield self.marker
        self.process.footer.wait(1)
        yield "INSTRUMENTATION_RESULT: stream=\n"
        yield json.dumps(self.report) + "\n"
        yield f"INSTRUMENTATION_CODE: {self.code}\n"
        self.process.exited.set()
    def close(self): self.process.footer.set()


class FakeProcess:
    def __init__(self, marker=MARKER, report=None, code=-1, already_finished=False):
        self.footer, self.exited = threading.Event(), threading.Event()
        self.returncode, self.terminated, self.killed = None, False, False
        self.stdout = FakePipe(self, marker, PASSED if report is None else report, code)
        if already_finished: self.footer.set(); self.exited.set()
    def poll(self):
        if self.exited.is_set(): self.returncode = 0
        return self.returncode
    def wait(self, timeout):
        if not self.exited.wait(timeout): raise subprocess.TimeoutExpired("owned adb", timeout)
        self.returncode = 0
        return 0
    def terminate(self): self.terminated = True; self.footer.set()
    def kill(self): self.killed = True; self.footer.set(); self.exited.set()


class FakeFuture:
    def __init__(self, process, packets, consume=True, fail=False):
        self.process, self.packets, self.consume, self.fail = process, packets, consume, fail
        self.completed, self.cancelled, self.received = False, False, []
    def done(self): return self.completed or self.cancelled
    def cancel(self): self.cancelled = True
    def result(self, timeout):
        if self.fail: raise RuntimeError("RPC failure details must not enter logs")
        if self.consume: self.received = list(self.packets)
        self.completed = True
        self.process.footer.set()


class Format:
    Mono, AUD_FMT_S16, MODE_UNSPECIFIED = 0, 1, 0
    def __init__(self, **values): self.__dict__.update(values)


PB = SimpleNamespace(AudioFormat=Format, AudioPacket=lambda **values: SimpleNamespace(**values))


class ProbeTest(unittest.TestCase):
    def test_only_fresh_active_ready_with_full_stream_budget_is_admitted(self):
        self.assertAlmostEqual(probe.ready_budget(100.0, 100.2, False, None, 100.3), 11.4)
        for ready, finished, exitcode, now in ((None, False, None, 100.3),
                (99.0, False, None, 100.3), (100.2, True, None, 100.3),
                (100.2, False, 0, 100.3), (100.2, False, None, 108.0)):
            with self.subTest(ready=ready, finished=finished, exitcode=exitcode, now=now):
                with self.assertRaises(ValueError):
                    probe.ready_budget(100.0, ready, finished, exitcode, now)

    def test_native_tone_proof_result_code_and_transport_count_are_distinct_gates(self):
        passed = {"event": "guest_audio_probe", "passed": True, "tone_seen": True, "silence_run": 10}
        self.assertEqual(probe.validate_result(passed, -1, probe.EXPECTED_BYTES), passed)
        for report, code, count in ((None, -1, probe.EXPECTED_BYTES),
                ({**passed, "passed": False}, -1, probe.EXPECTED_BYTES),
                ({**passed, "tone_seen": False}, -1, probe.EXPECTED_BYTES),
                ({**passed, "silence_run": 9}, -1, probe.EXPECTED_BYTES),
                (passed, 0, probe.EXPECTED_BYTES), (passed, -1, 1280)):
            with self.subTest(report=report, code=code, count=count), self.assertRaises(ValueError):
                probe.validate_result(report, code, count)

    def _run(self, process, directory, consume=True, fail=False, pipeline="direct"):
        calls = []
        def future(packets, **values):
            calls.append(values)
            result = FakeFuture(process, packets, consume, fail)
            calls.append(result)
            return result
        stub = SimpleNamespace(injectAudio=SimpleNamespace(future=future))
        with patch("probe_audio.subprocess.Popen", return_value=process) as launch, patch("probe_audio.time.sleep") as sleep, patch("probe_audio.time.monotonic", return_value=100.0):
            options = {} if pipeline == "direct" else {"pipeline": pipeline}
            result = probe.run_probe("emulator-5598", stub, PB, (("authorization", "not-a-real-token"),), PCM, directory, **options)
            calls.append(sleep)
            calls.append(launch.call_args.args[0])
            return result, calls

    def test_closed_pipeline_selector_preserves_direct_default_and_uses_only_pcm_runner(self):
        for pipeline, report, runner in (("direct", PASSED, "AudioInjectionRunner"),
                                         ("pcm", PCM_PASSED, "PcmToneRunner")):
            with self.subTest(pipeline=pipeline), tempfile.TemporaryDirectory() as folder:
                _, calls = self._run(FakeProcess(report=report), Path(folder), pipeline=pipeline)
                self.assertEqual(calls[3][-1], "dev.lip.android.test/dev.lip." + runner)
        with tempfile.TemporaryDirectory() as folder, patch("probe_audio.subprocess.Popen") as launch:
            with self.assertRaises(ValueError):
                probe.run_probe("emulator-5598", None, PB, (), PCM, Path(folder), pipeline="dev.lip.ArbitraryRunner")
            launch.assert_not_called()

    def test_pcm_pipeline_requires_eof_counts_hash_threads_cleanup_and_native_tone(self):
        self.assertEqual(probe.validate_result(PCM_PASSED, -1, probe.EXPECTED_BYTES, pipeline="pcm"), PCM_PASSED)
        for key, bad in (("pipeline", "direct"), ("asr_tested", True), ("eof_received", False),
                ("reader_joined", False), ("writer_joined", False), ("cleanup_ok", False),
                ("permission_unchanged", False), ("unsilenced_ready_window", False),
                ("windowed_pcm_hash_matches_pipe", False), ("pcm_sha256", "bad"),
                ("windowed_pcm_sha256", "b" * 64), ("accepted_bytes", 383999),
                ("received_bytes", 3200), ("captured_bytes", 384002), ("captured_samples", True),
                ("ready_received_bytes", 3199), ("source_errors", 1), ("reader_error", "IOException"),
                ("capture_held_ms", 11999), ("fixture_wav_sha256", "0" * 64), ("tone_seen", False)):
            with self.subTest(key=key), self.assertRaises(ValueError):
                probe.validate_result({**PCM_PASSED, key: bad}, -1, probe.EXPECTED_BYTES, pipeline="pcm")
        with self.assertRaises(ValueError):
            probe.validate_result(PASSED, -1, probe.EXPECTED_BYTES, pipeline="pcm")

    def test_thread_drains_after_fresh_ready_and_preserves_pcm_with_zero_tail(self):
        report = {**PASSED, "padding": "x" * 40_000}
        process = FakeProcess(report=report)
        with tempfile.TemporaryDirectory() as folder:
            result, calls = self._run(process, Path(folder))
            self.assertTrue(result["guest_delivery_proven"])
            packets = calls[1].received
            self.assertEqual(b"".join(p.audio for p in packets), PCM + bytes(64_000))
            self.assertTrue(all(p.format.samplingRate == 16000 and p.format.mode == Format.MODE_UNSPECIFIED for p in packets))
            self.assertIn('"padding"', (Path(folder) / "guest.log").read_text())
            self.assertNotIn("not-a-real-token", "".join(p.read_text() for p in Path(folder).iterdir()))
            self.assertFalse(process.terminated)

    def test_blocking_delivery_never_delays_packets_on_the_client(self):
        process = FakeProcess()
        with tempfile.TemporaryDirectory() as folder:
            _, calls = self._run(process, Path(folder))
            calls[2].assert_not_called()

    def test_old_marker_eof_or_finished_probe_never_starts_rpc(self):
        for marker in ("READY: guest AudioRecord started; pcm_path=old.pcm\n", MARKER, ""):
            process = FakeProcess(marker=marker, already_finished=True)
            with self.subTest(marker=marker), tempfile.TemporaryDirectory() as folder:
                with patch("probe_audio.subprocess.Popen", return_value=process), patch("probe_audio.time.monotonic", return_value=100.0):
                    future = unittest.mock.Mock()
                    stub = SimpleNamespace(injectAudio=SimpleNamespace(future=future))
                    with self.assertRaises(ValueError):
                        probe.run_probe("emulator-5598", stub, PB, (), PCM, Path(folder))
                    future.assert_not_called()

    def test_native_failure_or_partial_transport_cannot_pass(self):
        for report, code, consume in (({**PASSED, "passed": False}, -1, True),
                                     (PASSED, 0, True), (PASSED, -1, False)):
            process = FakeProcess(report=report, code=code)
            with self.subTest(report=report, code=code, consume=consume), tempfile.TemporaryDirectory() as folder:
                with self.assertRaises(ValueError):
                    self._run(process, Path(folder), consume=consume)

    def test_rpc_failure_cleans_only_its_owned_child_without_logging_details(self):
        process = FakeProcess()
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(RuntimeError): self._run(process, Path(folder), fail=True)
            self.assertTrue(process.terminated)
            self.assertNotIn("RPC failure details", (Path(folder) / "events.jsonl").read_text())

    def test_main_auth_and_both_pid_checks_precede_guest_capture_and_key_cleanup(self):
        order = []
        pin = probe.injector.RUNTIME_PINS[probe.injector.EMULATOR_ROOT]
        entries = {"port.serial": "5598", "avd.id": "fixture"}
        @contextmanager
        def identity(_entries):
            order.append("key-enter")
            try: yield object()
            finally: order.append("key-cleanup")
        @contextmanager
        def channel(_endpoint): yield object()
        def authenticated(*_): order.append("auth-negative-probes")
        def verified(*_): order.append("pid")
        def capture(*_): order.append("capture"); return {"passed": True}
        modules = {"grpc": SimpleNamespace(insecure_channel=channel),
                   "emulator_controller_pb2": PB,
                   "emulator_controller_pb2_grpc": SimpleNamespace(EmulatorControllerStub=lambda _: object())}
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "proto.sha256").write_text(pin["lib/emulator_controller.proto"])
            argv = ["probe_audio.py", "--emulator-pid", "123", "--endpoint", "127.0.0.1:8554",
                    "--discovery", str(root / "pid_123_info.ini"), "--avd-dir", str(root),
                    "--bindings", str(root), "--log-dir", str(root)]
            with patch("sys.argv", argv), patch.dict("sys.modules", modules), \
                    patch("probe_audio.injector.runtime_provenance", return_value=pin), \
                    patch("probe_audio.injector.wav_pcm", return_value=PCM), \
                    patch("probe_audio.injector.read_discovery", return_value=entries), \
                    patch("probe_audio.injector.verify_live_target", side_effect=verified), \
                    patch("probe_audio.subprocess.run", return_value=SimpleNamespace(stdout="p123\nn127.0.0.1:5598\n")), \
                    patch("probe_audio.injector.jwt_identity", side_effect=identity), \
                    patch("probe_audio.injector.auth_checks", side_effect=authenticated), \
                    patch("probe_audio.injector.metadata", return_value=(("authorization", "not-a-real-token"),)), \
                    patch("probe_audio.run_probe", side_effect=capture), patch("probe_audio.signal.signal"), \
                    patch("sys.stdout", new_callable=io.StringIO) as output:
                probe.main()
            self.assertEqual(order, ["pid", "key-enter", "auth-negative-probes", "pid", "capture", "key-cleanup"])
            self.assertNotIn("not-a-real-token", output.getvalue())


if __name__ == "__main__":
    unittest.main()
