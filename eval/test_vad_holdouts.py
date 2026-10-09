"""Frozen-builder fixtures only; no say invocation, native ASR/VAD or microphone."""
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import wave

import vad_holdouts as holdouts


class TransformTest(unittest.TestCase):
    def test_fixed_gain_vectors_do_not_peak_renormalize(self):
        for gain, expected in ((-24, (-1034, 0, 1034)), (-42, (-130, 0, 130))):
            with self.subTest(gain=gain):
                pcm, clipped = holdouts.quantize_pcm([-.5, 0, .5], gain)
                self.assertEqual(pcm, struct.pack("<hhh", *expected))
                self.assertEqual(clipped, 0)

    def test_pcm16_positive_endpoint_clipping_is_counted(self):
        self.assertEqual(holdouts.quantize_pcm([-1.0, 1.0], 0),
                         (struct.pack("<hh", -32768, 32767), 1))

    def test_invalid_samples_or_gain_are_rejected(self):
        for samples, gain in (([], -24), ([float("nan")], -24),
                              ([float("inf")], -24), ([1.001], -24),
                              ([True], -24), ([.5], float("nan")), ([.5], -23), ([.5], False)):
            with self.subTest(samples=samples, gain=gain), self.assertRaises(ValueError):
                holdouts.quantize_pcm(samples, gain)

    def test_before_and_after_variants_preserve_entire_source_and_truth_envelope(self):
        source = struct.pack("<hhh", 1, -2, 3)
        noise = struct.pack("<h", 17) * holdouts.PAUSE_SAMPLES
        edge = noise[:holdouts.EDGE_SAMPLES * 2]
        self.assertEqual(holdouts.layout_pcm(source, "before", noise),
                         (noise + source + edge, holdouts.PAUSE_SAMPLES,
                          holdouts.PAUSE_SAMPLES + 3))
        self.assertEqual(holdouts.layout_pcm(source, "after", noise),
                         (edge + source + noise, holdouts.EDGE_SAMPLES,
                          holdouts.EDGE_SAMPLES + 3))

    def test_oversized_or_unaligned_sources_cannot_be_trimmed_to_fit(self):
        noise = bytes(holdouts.PAUSE_SAMPLES * 2)
        too_long = bytes((holdouts.MAX_SAMPLES - holdouts.PAUSE_SAMPLES -
                          holdouts.EDGE_SAMPLES + 1) * 2)
        for source, position, pause in ((too_long, "before", noise),
                                        (b"\0", "before", noise),
                                        (b"\0\0", "both", noise),
                                        (b"\0\0", "after", noise[:-2])):
            with self.subTest(position=position), self.assertRaises(ValueError):
                holdouts.layout_pcm(source, position, pause)


def write_wave(path, pcm):
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as audio:
        audio.setparams((1, 2, holdouts.RATE, 0, "NONE", ""))
        audio.writeframes(pcm)


def scratch_directory():
    root = Path(__file__).resolve().parent.parent / "validation/hillclimb/vad-heldout"
    root.mkdir(parents=True, exist_ok=True)
    return tempfile.TemporaryDirectory(prefix="test-", dir=root)


class FrozenBuilderTest(unittest.TestCase):
    def fixture(self, directory):
        root = Path(directory)
        cases = []
        for identity, language, text in zip(holdouts.HUMAN_IDS, ("en", "ja", "zh"),
                                             ("Do not send it.", "送らないで。", "不要发送。")):
            path = root / "sources" / "audio" / (identity + ".wav")
            write_wave(path, struct.pack("<hhhh", 16384, -16384, 8192, -8192))
            cases.append({"id": identity, "language": language,
                          "audio": "audio/" + path.name, "size_bytes": path.stat().st_size,
                          "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                          "reference_raw": text, "max_error_rate": .05,
                          "checks": [{"name": "negation", "target": "raw", "required": [text]}],
                          "license": "CC-BY-4.0", "source": {"dataset": "google/fleurs",
                          "revision": "a" * 40, "num_samples": 4}})
        manifest = root / "sources" / "corpus.json"
        manifest.write_text(json.dumps({"cases": cases}, ensure_ascii=False), encoding="utf-8")
        return manifest, root / "frozen", cases

    def fake_say(self, command, **kwargs):
        self.assertEqual(command[0], "/usr/bin/say")
        self.assertIn("-o", command)
        self.assertIn("--file-format=WAVE", command)
        self.assertIn("--data-format=LEI16@16000", command)
        self.assertIn("--channels=1", command)
        self.assertEqual(command[command.index("-r") + 1], "150")
        self.assertNotIn("-n", command)
        self.assertNotIn("-a", command)
        write_wave(Path(command[command.index("-o") + 1]), struct.pack("<hh", 16384, -16384))

    def test_builder_freezes_24_cases_exact_sources_and_independent_truth(self):
        with scratch_directory() as directory:
            manifest, output, sources = self.fixture(directory)
            original = manifest.read_bytes()
            read_bytes = Path.read_bytes

            def fixture_bytes(path):
                return b"synthetic say executable fixture" if path == Path("/usr/bin/say") else read_bytes(path)

            with patch("vad_holdouts.subprocess.run", side_effect=self.fake_say) as say, \
                    patch.object(Path, "read_bytes", fixture_bytes):
                result = holdouts.build(manifest, output)
            self.assertEqual(len(result["cases"]), 24)
            self.assertEqual(say.call_count, 3)
            self.assertEqual(manifest.read_bytes(), original)
            self.assertEqual(len({case["id"] for case in result["cases"]}), 24)
            self.assertEqual(json.loads((output / "manifest.json").read_text()), result)
            for case in result["cases"]:
                path = output / case["audio"]
                self.assertEqual(case["size_bytes"], path.stat().st_size)
                self.assertEqual(case["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
                self.assertIn(case["transform"]["gain_db"], (-24, -42))
                self.assertFalse(case["truth"]["voiced_alignment_claimed"])
                self.assertEqual(case["truth"]["source_envelope_samples"][1] -
                                 case["truth"]["source_envelope_samples"][0],
                                 case["source"]["num_samples"])
                self.assertFalse(case["transform"]["noise"]["actual_room_tone"])
                self.assertEqual(case["transform"]["clipped_samples"], 0)
                with wave.open(str(path), "rb") as audio:
                    self.assertEqual(audio.getparams()[:3], (1, 2, 16000))
                    self.assertLessEqual(audio.getnframes(), holdouts.MAX_SAMPLES)
                if case["origin"] == "human_recording":
                    source = next(source for source in sources if source["id"] == case["source"]["case_id"])
                    self.assertEqual(case["reference_raw"], source["reference_raw"])
                    self.assertEqual(case["checks"], source["checks"])
                    self.assertEqual(case["source"]["audio_sha256"], source["sha256"])
                else:
                    self.assertEqual(case["reference_raw"], holdouts.WORDS[case["language"]][1])
                    self.assertEqual(case["source"]["say_sha256"],
                                     hashlib.sha256(b"synthetic say executable fixture").hexdigest())

    def test_existing_output_is_never_overwritten_or_resynthesized(self):
        with scratch_directory() as directory:
            manifest, output, _ = self.fixture(directory)
            output.mkdir(); marker = output / "preserve.txt"; marker.write_text("preserve")
            with patch("vad_holdouts.subprocess.run") as say, self.assertRaises(FileExistsError):
                holdouts.build(manifest, output)
            self.assertEqual(marker.read_text(), "preserve")
            say.assert_not_called()

    def test_source_hash_drift_fails_before_synthesis_or_freeze(self):
        with scratch_directory() as directory:
            manifest, output, sources = self.fixture(directory)
            (manifest.parent / sources[0]["audio"]).write_bytes(b"changed")
            with patch("vad_holdouts.subprocess.run") as say, self.assertRaises(ValueError):
                holdouts.build(manifest, output)
            say.assert_not_called()
            self.assertFalse((output / "manifest.json").exists())


if __name__ == "__main__":
    unittest.main()
