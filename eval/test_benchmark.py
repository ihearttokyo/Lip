"""Scoring/storage fixtures, not evidence that an ASR engine recognizes speech."""
import hashlib
import json
import os
from pathlib import Path
import sys
import struct
import tempfile
import time
import unittest
import wave

from benchmark import run_case, score_case, verify_audio
from controls import prepare
from fetch_corpus import verify_delivery, verify_row


def case(language="en", reference="Do not send 3.5 dollars to Jared."):
    return {"id": "fixture", "language": language, "reference_raw": reference,
            "max_error_rate": 0.10, "checks": []}


class BenchmarkTest(unittest.TestCase):
    def test_narrative_critical_phrase_ignores_only_layout_whitespace(self):
        manifest = json.loads((Path(__file__).parent / "corpus.json").read_text())
        c = next(c for c in manifest["cases"] if c["id"] == "fleurs-en-013")
        wrapped = c["reference_raw"].replace("were hurt", "were\n\thurt")
        result = score_case(c, wrapped)
        self.assertEqual(result["raw"]["errors"], 0)
        self.assertTrue(result["passed"])
        changed = score_case(c, wrapped.replace("none of them", "one of them"))
        self.assertFalse(changed["passed"])
        self.assertFalse(next(check for check in changed["checks"]
                              if check["name"] == "negation")["passed"])

    def test_literal_and_formatting_checks_keep_exact_whitespace(self):
        literal = case(reference='print("a  b")')
        literal["checks"] = [{"name": "literal spacing", "target": "raw", "scope": "literal",
                               "required": [r'print\("a  b"\)']}]
        self.assertTrue(score_case(literal, literal["reference_raw"])["passed"])
        self.assertFalse(score_case(literal, 'print("a b")')["passed"])
        self.assertFalse(score_case(literal, 'print("a\nb")')["passed"])
        # Even a FLEURS narrative source cannot override an explicit literal check.
        literal.update(origin="human_recording", source={"dataset": "google/fleurs"})
        self.assertFalse(score_case(literal, 'print("a b")')["passed"])
        formatting = case(reference="milk eggs")
        formatting["checks"] = [{"name": "list layout", "target": "clean",
                                  "required": [r"(?m)^1\. Milk$", r"(?m)^2\. Eggs$"]}]
        self.assertTrue(score_case(formatting, "milk eggs", "1. Milk\n2. Eggs")["passed"])
        self.assertFalse(score_case(formatting, "milk eggs", "1. Milk 2. Eggs")["passed"])

    def test_frozen_human_manifest_refs_and_critical_anchors_are_self_consistent(self):
        manifest = json.loads((Path(__file__).parent / "corpus.json").read_text())
        self.assertEqual(len(manifest["cases"]), 18)
        self.assertEqual(sum(c["size_bytes"] for c in manifest["cases"]), 9615124)
        for c in manifest["cases"]:
            with self.subTest(case=c["id"]):
                self.assertEqual(c["max_error_rate"], .05)
                self.assertEqual(c["origin"], "human_recording")
                self.assertTrue(c["checks"])
                self.assertTrue(score_case(c, c["reference_raw"])["passed"])

    def test_download_source_drift_size_and_etag_fail_closed(self):
        c = {"size_bytes": 3, "source": {"revision": "a" * 40, "config": "en_us",
             "split": "validation", "row_index": 2, "utterance_id": 10,
             "num_samples": 160, "gender_label": "male", "source_file": "source.wav",
             "source_raw_transcription": "Do not send it.",
             "source_asr_transcription": "do not send it", "cached_audio_etag": "etag"}}
        row = {"id": 10, "num_samples": 160, "gender": 0, "path": "/source.wav",
               "raw_transcription": "Do not send it.", "transcription": "do not send it",
               "audio": [{"src": "https://datasets-server.huggingface.co/cached-assets/google/fleurs/--/" +
                          "a" * 40 + "/--/en_us/validation/2/audio/audio.wav?ephemeral=not-saved"}]}
        self.assertEqual(verify_row(c, row, 2), row["audio"][0]["src"])
        for field, value in [("id", 11), ("num_samples", 159),
                             ("raw_transcription", "Do send it.")]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                verify_row(c, dict(row, **{field: value}), 2)
        self.assertEqual(verify_delivery(c, b"abc", "etag", "etag"),
                         hashlib.sha256(b"abc").hexdigest())
        with self.assertRaises(ValueError):
            verify_delivery(c, b"ab", "etag", "etag")
        with self.assertRaises(ValueError):
            verify_delivery(c, b"abc", "etag", "changed")

    def test_local_nonspeech_controls_are_hashed_repeatable_and_nonclobbering(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            manifest = prepare(root)
            self.assertEqual(len(manifest["cases"]), 6)
            self.assertEqual({c["language"] for c in manifest["cases"]}, {"en", "ja", "zh"})
            for c in manifest["cases"]:
                self.assertEqual(c["origin"], "negative_control")
                self.assertEqual(c["reference_raw"], "")
                verify_audio(c, root)
            self.assertEqual(prepare(root), manifest)
            (root / "audio/silence.wav").write_bytes(b"existing unrelated bytes")
            with self.assertRaises((ValueError, wave.Error)):
                prepare(root)
            self.assertEqual((root / "audio/silence.wav").read_bytes(), b"existing unrelated bytes")

    def test_english_word_errors_and_punctuation(self):
        result = score_case(case(), "do not send 3.5 dollars to jared!")
        self.assertEqual(result["raw"]["reference_units"], 7)
        self.assertEqual(result["raw"]["errors"], 0)
        self.assertTrue(result["passed"])
        self.assertEqual(score_case(case(), "Do send 35 dollars to Jared.")
                         ["raw"]["errors"], 2)

    def test_japanese_and_mandarin_character_errors(self):
        for language, reference, wrong in [("ja", "送らないで。", "送って。"),
                                           ("zh", "不要发送35元。", "要发送35元。")]:
            with self.subTest(language=language):
                self.assertEqual(score_case(case(language, reference), reference)
                                 ["raw"]["errors"], 0)
                self.assertFalse(score_case(case(language, reference), wrong)["passed"])

    def test_silence_hallucination_is_not_zero_error(self):
        c = case(reference="")
        self.assertTrue(score_case(c, "")["passed"])
        result = score_case(c, "Thank you for watching.")
        self.assertEqual(result["raw"]["errors"], 4)
        self.assertIsNone(result["raw"]["rate"])
        self.assertFalse(result["passed"])

    def test_signed_amount_and_decimal_change_cannot_score_as_identical(self):
        for language, reference, wrong in [("en", "send -3.5 dollars", "send 3.5 dollars"),
                                           ("ja", "3.5円", "35円"),
                                           ("zh", "-3.5元", "3.5元")]:
            with self.subTest(language=language):
                self.assertGreater(score_case(case(language, reference), wrong)["raw"]["errors"], 0)

    def test_critical_fact_fails_even_under_average_error_threshold(self):
        c = case(reference="do not send the message " + "please " * 30)
        c["checks"] = [{"name": "negation", "target": "raw",
                        "required": [r"\bnot\b"]}]
        result = score_case(c, c["reference_raw"].replace("not ", ""))
        self.assertLess(result["raw"]["rate"], c["max_error_rate"])
        self.assertFalse(result["passed"])

    def test_clean_checks_require_actual_clean_output_and_list_format(self):
        c = case(reference="first milk second eggs")
        c["checks"] = [{"name": "numbered list", "target": "clean",
                        "required": [r"(?m)^1\. Milk$", r"(?m)^2\. Eggs$"],
                        "forbidden": [r"(?i)\bfirst\b"]}]
        self.assertFalse(score_case(c, c["reference_raw"])["passed"])
        self.assertFalse(score_case(c, c["reference_raw"], "Milk, eggs.")["passed"])
        self.assertTrue(score_case(c, c["reference_raw"], "1. Milk\n2. Eggs")["passed"])

    def test_both_checks_cannot_hide_cleanup_fact_changes(self):
        c = case()
        c["checks"] = [{"name": "amount", "target": "both",
                        "required": [r"(?<!\d)3\.5(?!\d)"]}]
        self.assertFalse(score_case(c, c["reference_raw"],
                                   "Do not send 35 dollars to Jared.")["passed"])

    def test_audio_hash_size_and_path_verified_before_execution(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            path = root / "test.wav"
            path.write_bytes(b"not a wave")
            c = case()
            c.update(audio="test.wav", sha256="0" * 64, size_bytes=10)
            with self.assertRaises(ValueError):
                verify_audio(c, root)
            c["audio"] = "../outside.wav"
            with self.assertRaises(ValueError):
                verify_audio(c, root)

    def test_offline_cli_real_timing_and_exit_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            audio = root / "space in name.wav"
            with wave.open(str(audio), "wb") as wav:
                wav.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
                wav.writeframes(b"\0\0" * 1600)
            c = case(reference="hello")
            c.update(audio=audio.name, sha256=hashlib.sha256(audio.read_bytes()).hexdigest(),
                     size_bytes=audio.stat().st_size)
            command = [sys.executable, "-c",
                       "import pathlib,sys,time; time.sleep(.01); "
                       "pathlib.Path(sys.argv[1]).write_text('hello')", "{output}"]
            result = run_case(c, root, command, timeout=2)
            self.assertEqual(result["raw"], "hello")
            self.assertGreaterEqual(result["elapsed_seconds"], .01)
            self.assertAlmostEqual(result["audio_seconds"], .1)
            self.assertTrue(result["score"]["passed"])
            failed = run_case(c, root, [sys.executable, "-c", "raise SystemExit(7)"], 2)
            self.assertEqual(failed["status"], "engine_error")
            self.assertNotIn("score", failed)

    def test_cli_json_preserves_distinct_raw_and_clean(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            audio = root / "silence.wav"
            with wave.open(str(audio), "wb") as wav:
                wav.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
                wav.writeframes(b"\0\0" * 160)
            c = case(reference="raw")
            c.update(audio=audio.name, sha256=hashlib.sha256(audio.read_bytes()).hexdigest(),
                     size_bytes=audio.stat().st_size)
            c["checks"] = [{"name": "cleanup", "target": "clean", "required": ["Clean\\."]}]
            evidence = {"model_raw": "language English<asr_text>raw", "detected_language": "en",
                        "research": {"stop_type": "eos", "peak_rss_bytes": 123}}
            payload = json.dumps({"raw": "raw", "clean": "Clean.", **evidence})
            result = run_case(c, root, [sys.executable, "-c",
                              "import pathlib,sys;pathlib.Path(sys.argv[1]).write_text(sys.argv[2])",
                              "{output}", payload], 2)
            self.assertEqual(result["clean"], "Clean.")
            self.assertTrue(result["score"]["passed"])
            self.assertEqual(result["engine_evidence"], evidence)

    @unittest.skipUnless(os.name == "posix", "Owned process-group timeout requires POSIX")
    def test_failed_wrapper_stops_its_nested_child(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            audio = root / "silence.wav"
            with wave.open(str(audio), "wb") as wav:
                wav.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
                wav.writeframes(b"\0\0" * 160)
            c = case(reference="")
            c.update(audio=audio.name, sha256=hashlib.sha256(audio.read_bytes()).hexdigest(),
                     size_bytes=audio.stat().st_size)
            marker = root / "leaked"
            child = "import time,pathlib; time.sleep(.2); pathlib.Path(" + repr(str(marker)) + ").write_text('leaked')"
            wrapper = "import subprocess,sys; subprocess.Popen([sys.executable,'-c',sys.argv[1]]); sys.exit(7)"
            result = run_case(c, root, [sys.executable, "-c", wrapper, child], timeout=2)
            self.assertEqual(result["status"], "engine_error")
            time.sleep(.3)
            self.assertFalse(marker.exists())

    @unittest.skipUnless(os.name == "posix", "Owned process-group timeout requires POSIX")
    def test_timeout_stops_nested_child_before_the_next_case_can_run(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            audio = root / "fixture.wav"
            with wave.open(str(audio), "wb") as wav:
                wav.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
                wav.writeframes(b"\0\0" * 160)
            c = case(reference="")
            c.update(audio=audio.name, sha256=hashlib.sha256(audio.read_bytes()).hexdigest(),
                     size_bytes=audio.stat().st_size)
            started, leaked = root / "child-started", root / "child-outlived-timeout"
            child = ("import os,pathlib,signal,sys,time; "
                     "signal.signal(signal.SIGTERM,signal.SIG_IGN); "
                     "pathlib.Path(sys.argv[1]).write_text(str(os.getpid())); "
                     "time.sleep(.5); pathlib.Path(sys.argv[2]).write_text('leaked')")
            wrapper = ("import pathlib,subprocess,sys,time; "
                       "subprocess.Popen([sys.executable,'-c',sys.argv[1],sys.argv[2],sys.argv[3]]); "
                       "time.sleep(10)")
            result = run_case(c, root, [sys.executable, "-c", wrapper, child,
                                        str(started), str(leaked)], timeout=.25)
            self.assertEqual(result["status"], "timeout")
            self.assertTrue(started.exists(), "Fixture must start an actual nested child")
            self.assertLess(result["elapsed_seconds"], 2)
            time.sleep(.65)  # Past the child's bounded write deadline, with no model/native call.
            self.assertFalse(leaked.exists(), "Timed-out wrapper must not leave its child running")

    def test_float_wave_audio_uses_actual_header_duration(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            audio = root / "float.wav"
            samples = b"\0" * 6400
            chunks = (b"fmt " + struct.pack("<IHHIIHH", 16, 3, 1, 16000, 64000, 4, 32) +
                      b"data" + struct.pack("<I", len(samples)) + samples)
            audio.write_bytes(b"RIFF" + struct.pack("<I", len(chunks) + 4) + b"WAVE" + chunks)
            c = case(reference="")
            c.update(audio=audio.name, sha256=hashlib.sha256(audio.read_bytes()).hexdigest(),
                     size_bytes=audio.stat().st_size)
            result = run_case(c, root, [sys.executable, "-c",
                              "import pathlib,sys;pathlib.Path(sys.argv[1]).write_text('')",
                              "{output}"], 2)
            self.assertAlmostEqual(result["audio_seconds"], .1)
            self.assertTrue(result["score"]["passed"])


if __name__ == "__main__":
    unittest.main()
