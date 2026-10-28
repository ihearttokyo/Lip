"""Simulated flow subprocess/RPC contracts; never native microphone, JNI or UI proof."""
import io
import json
from contextlib import contextmanager, nullcontext
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import flow_audio as flow
from test_probe_audio import FakeFuture, FakeProcess, PB

PCM = bytes(range(256)) * 942 + bytes(128)
READY_REPORT = {"scope": "Main in-app cursor flow", "fixture": "fleurs-en-013",
    "fixture_source_sha256": "431d013d01d25cbf8d42587f4a14df57d09dbe9cbc2af4ea91ba93f9bd290ec4",
    "model_verified": True, "model_sha256": "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
    "stream_ms": 7540, "phase": "LISTENING", "microphone_accepted_bytes": 3200,
    "microphone_recording": True, "client_silenced": False,
    "ready_elapsed_ns": 100_000_000_000, "ready_elapsed_ms": 100_000,
    "bubble_tested": False, "cross_app_tested": False, "speech_perfection_claim": False}
PASSED = {**READY_REPORT, "passed": True, "phase": "IDLE", "ready_phase": "READY",
    "capture_after_ready_ms": 9540, "raw": "Although three people were inside the house when the car impacted it, none of them were hurt.",
    "reference_words_once": True, "native_preview_matches_raw": True, "no_auto_insert": True,
    "selected_field_replacement": True, "new_history_entries": 1,
    "reopened_encrypted_history_matches_raw": True, "on_destroy_closes_owned_setup": True,
    "cleanup_ok": True}


class FlowPipe:
    def __init__(self, process, ready, report, code):
        self.process, self.ready, self.report, self.code = process, ready, report, code
        self.iterator = iter(self)
    def __iter__(self):
        if self.ready is not None: yield "LOCAL_FLOW_READY " + json.dumps(self.ready) + "\n"
        self.process.footer.wait(1)
        yield "INSTRUMENTATION_RESULT: stream=\n"
        yield "LOCAL_FLOW_RESULT " + json.dumps(self.report) + "\n"
        yield f"INSTRUMENTATION_CODE: {self.code}\n"
        self.process.exited.set()
    def close(self): self.process.footer.set()
    def readline(self, _limit): return next(self.iterator, "")


def process(ready=READY_REPORT, report=PASSED, code=-1, ended=False):
    child = FakeProcess(already_finished=ended)
    child.stdout = FlowPipe(child, ready, report, code)
    return child


class FlowTest(unittest.TestCase):
    def test_zero_tail_covers_upstream_queue_without_changing_speech_or_capture(self):
        self.assertEqual(flow.FIXTURE_FRAMES, 120_640)
        self.assertEqual(flow.TAIL_FRAMES, 20_000)
        self.assertGreater(flow.TAIL_FRAMES * 2, 32_767)
        self.assertAlmostEqual(flow.STREAM_SECONDS, 8.79)
        self.assertAlmostEqual(flow.STREAM_SECONDS, (flow.FIXTURE_FRAMES + flow.TAIL_FRAMES) / 16000)
        self.assertEqual(flow.CAPTURE_SECONDS, 9.54)
        self.assertGreater(flow.CAPTURE_SECONDS - flow.DRAIN_SECONDS, flow.STREAM_SECONDS)
        chunks = list(flow.injector.pcm_chunks(PCM, flow.FIXTURE_FRAMES + flow.TAIL_FRAMES))
        self.assertEqual(b"".join(chunks), PCM + bytes(40_000))
        with self.assertRaises(ValueError):
            flow.ready_budget(100.0, 100.2, False, None, 100.8)

    def test_ready_requires_verified_model_full_unsilenced_recording_and_source_identity(self):
        self.assertEqual(flow.validate_ready(READY_REPORT), READY_REPORT)
        for key, value in (("model_verified", False), ("model_sha256", "0" * 64),
                ("fixture_source_sha256", "0" * 64), ("microphone_recording", False),
                ("client_silenced", True), ("microphone_accepted_bytes", 3199),
                ("microphone_accepted_bytes", True), ("phase", "READY"),
                ("stream_ms", 20_000), ("ready_elapsed_ns", 0)):
            with self.subTest(key=key), self.assertRaises(ValueError):
                flow.validate_ready({**READY_REPORT, key: value})

    def test_budget_is_after_fresh_ready_and_covers_audio_tail_and_drain(self):
        self.assertAlmostEqual(flow.ready_budget(100.0, 100.2, False, None, 100.3), 9.14)
        for ready, finished, code, now in ((None, False, None, 100.3),
                (99.9, False, None, 100.3), (100.2, True, None, 100.3),
                (100.2, False, 0, 100.3), (100.2, False, None, 101.5)):
            with self.subTest(ready=ready, now=now), self.assertRaises(ValueError):
                flow.ready_budget(100.0, ready, finished, code, now)

    def test_transport_native_ui_history_and_cleanup_are_distinct_gates(self):
        self.assertEqual(flow.validate_result(PASSED, -1, 281_280), PASSED)
        for key, value in (("passed", False), ("reference_words_once", False),
                ("native_preview_matches_raw", False), ("no_auto_insert", False),
                ("selected_field_replacement", False), ("new_history_entries", 2),
                ("new_history_entries", True), ("reopened_encrypted_history_matches_raw", False),
                ("on_destroy_closes_owned_setup", False), ("cleanup_ok", False),
                ("raw", "three people were hurt"), ("capture_after_ready_ms", 9539),
                ("bubble_tested", True), ("cross_app_tested", True), ("phase", "LISTENING")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                flow.validate_result({**PASSED, key: value}, -1, 281_280)
        for report, code, count in ((None, -1, 281_280), (PASSED, 0, 281_280), (PASSED, -1, 241_280)):
            with self.assertRaises(ValueError): flow.validate_result(report, code, count)

    def _run(self, child, directory, consume=True, fail=False, expired=False):
        futures = []
        now = [100.0]
        def future(packets, **values):
            if expired: now[0] = 109.3
            result = FakeFuture(child, packets, consume, fail)
            futures.append((result, values))
            return result
        stub = SimpleNamespace(injectAudio=SimpleNamespace(future=future))
        with patch("flow_audio.subprocess.Popen", return_value=child) as launch, \
                patch("flow_audio.time.monotonic", side_effect=lambda: now[0]), \
                patch("flow_audio.time.sleep") as sleep:
            result = flow.run_flow("emulator-5598", stub, PB, (("authorization", "not-a-real-token"),), PCM, directory)
            sleep.assert_not_called()
            self.assertIn("dev.lip.android.test/dev.lip.LocalFlowRunner", launch.call_args.args[0])
            self.assertIn(flow.SOURCE_SHA256, launch.call_args.args[0])
            return result, futures

    def test_fresh_ready_full_unthrottled_payload_tail_and_native_result(self):
        child = process(report={**PASSED, "padding": "x" * 40_000})
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            result, futures = self._run(child, directory)
            self.assertTrue(result["native_flow_proven"])
            packets = futures[0][0].received
            self.assertEqual(b"".join(p.audio for p in packets), PCM + bytes(40_000))
            self.assertTrue(all(len(p.audio) <= 1280 and len(p.audio) % 2 == 0 and p.format.mode == 0 for p in packets))
            self.assertNotIn("not-a-real-token", "".join(p.read_text() for p in directory.iterdir()))
            self.assertFalse(child.terminated)

    def test_bad_ready_eof_or_finished_probe_never_starts_injection(self):
        for ready in (None, READY_REPORT, {**READY_REPORT, "client_silenced": True}):
            child = process(ready=ready, ended=True)
            with self.subTest(ready=ready), tempfile.TemporaryDirectory() as folder:
                with patch("flow_audio.subprocess.Popen", return_value=child), \
                        patch("flow_audio.time.monotonic", return_value=100.0):
                    future = Mock()
                    with self.assertRaises(ValueError):
                        flow.run_flow("emulator-5598", SimpleNamespace(injectAudio=SimpleNamespace(future=future)), PB, (), PCM, Path(folder))
                    future.assert_not_called()

    def test_rpc_failure_or_partial_transport_cannot_pass_and_cleans_its_child(self):
        for fail, consume, report in ((True, True, PASSED), (False, False, PASSED),
                                    (False, True, {**PASSED, "cleanup_ok": False})):
            child = process(report=report)
            with self.subTest(fail=fail, consume=consume), tempfile.TemporaryDirectory() as folder:
                with self.assertRaises((ValueError, RuntimeError)):
                    self._run(child, Path(folder), consume=consume, fail=fail)
                self.assertFalse((Path(folder) / "result.json").exists())
                self.assertNotIn("RPC failure details", (Path(folder) / "events.jsonl").read_text())
                if fail: self.assertTrue(child.terminated)

    def test_expired_active_capture_budget_cancels_before_any_packet(self):
        child = process()
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, "active capture budget expired"):
                self._run(child, Path(folder), expired=True)
            self.assertTrue(child.terminated)
            self.assertFalse((Path(folder) / "result.json").exists())
            self.assertIn('"yielded_bytes": 0', (Path(folder) / "events.jsonl").read_text())

    def test_duplicate_invalid_or_oversized_output_is_bounded_and_rejected(self):
        marker = "LOCAL_FLOW_READY " + json.dumps(READY_REPORT) + "\n"
        for content in (marker * 2, "LOCAL_FLOW_READY []\n", "x" * 262_145,
                        "LOCAL_FLOW_RESULT {}\nLOCAL_FLOW_RESULT {}\n"):
            with self.subTest(length=len(content)):
                output = io.StringIO()
                stream = flow.FlowStream(SimpleNamespace(stdout=io.StringIO(content)), output)
                self.assertTrue(stream.done.wait(1))
                stream.thread.join(1)
                self.assertIsNotNone(stream.error)
                self.assertTrue(stream.finished)
                self.assertLessEqual(len(output.getvalue().encode()), 1_048_576)

    def test_preauth_and_pid_recheck_precede_guest_and_exact_key_cleanup(self):
        order = []
        pin = flow.injector.RUNTIME_PINS[flow.injector.EMULATOR_ROOT]
        @contextmanager
        def identity(_):
            order.append("key-enter")
            try: yield object()
            finally: order.append("key-cleanup")
        modules = {"grpc": SimpleNamespace(insecure_channel=lambda _: nullcontext(None)),
            "emulator_controller_pb2": PB,
            "emulator_controller_pb2_grpc": SimpleNamespace(EmulatorControllerStub=lambda _: object())}
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); (root / "proto.sha256").write_text(pin["lib/emulator_controller.proto"])
            argv = ["flow_audio.py", "--emulator-pid", "123", "--endpoint", "127.0.0.1:8554",
                    "--discovery", str(root / "pid_123.ini"), "--avd-dir", str(root),
                    "--bindings", str(root), "--log-dir", str(root)]
            with patch("sys.argv", argv), patch.dict("sys.modules", modules), \
                    patch("flow_audio.injector.runtime_provenance", return_value=pin), \
                    patch("flow_audio.injector.wav_pcm", return_value=PCM), \
                    patch("flow_audio.injector.read_discovery", return_value={"port.serial": "5598"}), \
                    patch("flow_audio.injector.verify_live_target", side_effect=lambda *_: order.append("pid")), \
                    patch("flow_audio.subprocess.run", return_value=SimpleNamespace(stdout="n127.0.0.1:5598\n")), \
                    patch("flow_audio.injector.jwt_identity", side_effect=identity), \
                    patch("flow_audio.injector.auth_checks", side_effect=lambda *_: order.append("negative-auth")), \
                    patch("flow_audio.injector.metadata", return_value=()), \
                    patch("flow_audio.run_flow", side_effect=lambda *_: order.append("capture")), \
                    patch("flow_audio.signal.signal"), patch("sys.stdout", new_callable=io.StringIO):
                flow.main()
            self.assertEqual(order, ["pid", "key-enter", "negative-auth", "pid", "capture", "key-cleanup"])


if __name__ == "__main__":
    unittest.main()
