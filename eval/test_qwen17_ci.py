"""Hosted orchestration fixtures; no downloads, builds, or model inference."""
import hashlib
import io
import json
from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import patch

import qwen17_ci as ci


class Qwen17CiTest(unittest.TestCase):
    def test_download_is_complete_pinned_and_never_overwrites(self):
        data = b"synthetic weight"
        pin = {"url": "https://huggingface.co/owned/resolve/pin/model.gguf",
               "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "model.gguf"
            with patch.object(ci.urllib.request, "urlopen", return_value=io.BytesIO(data)):
                ci.download(pin, path)
            self.assertEqual(path.read_bytes(), data)
            with patch.object(ci.urllib.request, "urlopen") as network, self.assertRaises(FileExistsError):
                ci.download(pin, path)
            network.assert_not_called()
            for body in [data[:-1], data + b"extra", b"x" * len(data)]:
                target = Path(temp) / (hashlib.sha256(body).hexdigest() + ".gguf")
                with patch.object(ci.urllib.request, "urlopen", return_value=io.BytesIO(body)), \
                        self.assertRaises(ValueError):
                    ci.download(pin, target)

    def test_capacity_rejects_less_than_six_gib(self):
        ci.require_capacity(6 * 1024 ** 3)
        with self.assertRaises(ValueError):
            ci.require_capacity(6 * 1024 ** 3 - 1)

    def test_build_output_is_bounded_and_failed_group_is_joined(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "build.log"
            with path.open("wb") as log, self.assertRaises(ValueError):
                ci.run_logged([sys.executable, "-c", "print('x' * 256)"], {}, log, byte_limit=64)
            self.assertLessEqual(path.stat().st_size, 64)

    def test_transfer_cannot_accept_slow_eof(self):
        data = b"fixture"
        pin = {"url": "https://huggingface.co/owned/resolve/pin/model.gguf", "bytes": len(data),
               "sha256": hashlib.sha256(data).hexdigest()}
        with tempfile.TemporaryDirectory() as temp, \
                patch.object(ci.urllib.request, "urlopen", return_value=io.BytesIO(data)), \
                patch.object(ci.time, "monotonic", side_effect=[0, 599, 1000]), \
                patch.object(ci.signal, "getitimer", return_value=(0.0, 0.0)), \
                patch.object(ci.signal, "signal"), patch.object(ci.signal, "setitimer"), \
                self.assertRaises(ValueError):
            ci.download(pin, Path(temp) / "model.gguf")

    def test_admission_does_not_allow_reference_gate_or_audio_drift(self):
        original = json.loads((Path(__file__).parent / "corpus.json").read_text())
        admitted = json.loads(json.dumps(original))
        admitted.update(status="complete_set_verified_no_engine_results", admitted_utc="now")
        for case in admitted["cases"]:
            case["admission"] = "downloaded_and_verified"
        ci.verify_admission(original, admitted)
        for field, value in [("reference_raw", "easier"), ("max_error_rate", .5), ("sha256", "0" * 64)]:
            changed = json.loads(json.dumps(admitted))
            changed["cases"][0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                ci.verify_admission(original, changed)

    def test_denied_main_does_not_read_download_or_spawn(self):
        with patch.object(ci.sys, "platform", "darwin"), \
                patch.object(ci, "load_json") as files, \
                patch.object(ci, "download") as downloads, \
                patch.object(ci.subprocess, "run") as processes:
            with self.assertRaises(ValueError):
                ci.main()
            files.assert_not_called()
            downloads.assert_not_called()
            processes.assert_not_called()


if __name__ == "__main__":
    unittest.main()
