"""Pure VAD boundary fixtures, not evidence that a model detects real speech."""
import ctypes
import struct
from types import SimpleNamespace
import unittest

from observe_vad import (ContextParams, FRAME_SAMPLES, MAX_SAMPLES, MODEL_BYTES,
                         MODEL_REVISION, MODEL_SHA256, MODEL_URL,
                         initialize_context, pcm_samples, probability_report)


class ProbabilityReportTest(unittest.TestCase):
    def test_exact_frame_count_extrema_and_original_sample_bounds(self):
        self.assertEqual(probability_report(1_025, [.1, .8, .2], True), {
            "sample_count": 1_025,
            "frame_samples": 512,
            "frame_count": 3,
            "probabilities": [.1, .8, .2],
            "max_probability": .8,
            "argmax_frame": 1,
            "argmax_start_sample": 512,
            "argmax_end_sample": 1_024,
            "tail_padding_samples": 511,
        })

    def test_partial_final_frame_does_not_invent_observed_audio(self):
        self.assertEqual(probability_report(1, [.5], True), {
            "sample_count": 1,
            "frame_samples": 512,
            "frame_count": 1,
            "probabilities": [.5],
            "max_probability": .5,
            "argmax_frame": 0,
            "argmax_start_sample": 0,
            "argmax_end_sample": 1,
            "tail_padding_samples": 511,
        })

    def test_failed_native_call_is_not_observation(self):
        with self.assertRaises(ValueError):
            probability_report(512, [.5], False)

    def test_logged_compute_error_invalidates_even_success_and_complete_probs(self):
        with self.assertRaises(ValueError):
            probability_report(512, [.5], True, native_error=True)

    def test_incomplete_extra_or_zero_probabilities_rejected(self):
        for probabilities in ([], [.5], [.1, .2, .3]):
            with self.subTest(probabilities=probabilities), self.assertRaises(ValueError):
                probability_report(513, probabilities, True)

    def test_probabilities_are_finite_numbers_within_closed_unit_interval(self):
        for probability in (float("nan"), float("inf"), -.001, 1.001, True, ".5"):
            with self.subTest(probability=probability), self.assertRaises(ValueError):
                probability_report(512, [probability], True)

    def test_sample_count_is_positive_integer_bounded_to_thirty_seconds(self):
        for samples in (0, -1, MAX_SAMPLES + 1, True, 1.5):
            with self.subTest(samples=samples), self.assertRaises(ValueError):
                probability_report(samples, [.5], True)


class PinnedContractTest(unittest.TestCase):
    def test_context_abi_matches_native_int_bool_int_struct(self):
        self.assertEqual(ctypes.sizeof(ContextParams), 12)
        self.assertEqual((ContextParams.n_threads.offset, ContextParams.use_gpu.offset,
                          ContextParams.gpu_device.offset), (0, 4, 8))
        params = ContextParams(4, False, 0)
        self.assertEqual((params.n_threads, params.use_gpu, params.gpu_device), (4, False, 0))

    def test_model_identity_and_frame_size_are_frozen(self):
        self.assertEqual(FRAME_SAMPLES, 512)
        self.assertEqual(MODEL_BYTES, 885_098)
        self.assertEqual(MODEL_SHA256, "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987")
        self.assertEqual(MODEL_REVISION, "9ffd54a1e1ee413ddf265af9913beaf518d1639b")
        self.assertEqual(MODEL_URL, "https://huggingface.co/ggml-org/whisper-vad/resolve/9ffd54a1e1ee413ddf265af9913beaf518d1639b/ggml-silero-v6.2.0.bin")


def wav_bytes(payload, tag=1, channels=1, rate=16_000, bits=16):
    alignment = channels * bits // 8
    fmt = struct.pack("<HHIIHH", tag, channels, rate, rate * alignment, alignment, bits)
    chunks = b"fmt " + struct.pack("<I", len(fmt)) + fmt
    chunks += b"data" + struct.pack("<I", len(payload)) + payload
    if len(payload) % 2:
        chunks += b"\0"
    return b"RIFF" + struct.pack("<I", len(chunks) + 4) + b"WAVE" + chunks


class AudioBoundaryTest(unittest.TestCase):
    def test_pcm16_is_normalized_without_changing_amplitude(self):
        self.assertEqual(list(pcm_samples(wav_bytes(struct.pack("<hhh", -32768, 0, 16384)))),
                         [-1.0, 0.0, .5])

    def test_fleurs_float32_is_preserved(self):
        self.assertEqual(list(pcm_samples(wav_bytes(struct.pack("<fff", -1.0, .125, 1.0),
                                                    tag=3, bits=32))), [-1.0, .125, 1.0])

    def test_wrong_format_truncation_duplicate_data_and_unaligned_pcm_fail_closed(self):
        valid = wav_bytes(struct.pack("<h", 1))
        duplicate = valid + b"data" + struct.pack("<I", 2) + b"\0\0"
        duplicate = duplicate[:4] + struct.pack("<I", len(duplicate) - 8) + duplicate[8:]
        for data in (b"not a WAV", valid[:-1], wav_bytes(b"\0"),
                     wav_bytes(b"\0\0", channels=2), wav_bytes(b"\0\0", rate=8_000),
                     wav_bytes(b"\0\0", tag=2), duplicate, wav_bytes(b"")):
            with self.subTest(size=len(data)), self.assertRaises(ValueError):
                pcm_samples(data)

    def test_nonfinite_overrange_or_oversized_audio_is_rejected(self):
        for value in (float("nan"), float("inf"), -1.001, 1.001):
            with self.subTest(value=value), self.assertRaises(ValueError):
                pcm_samples(wav_bytes(struct.pack("<f", value), tag=3, bits=32))
        with self.assertRaises(ValueError):
            pcm_samples(wav_bytes(bytes((MAX_SAMPLES + 1) * 2)))


class BackendInitializationTest(unittest.TestCase):
    def fixture(self, present):
        events = []
        def load(path):
            events.append(("load", path))
        def cpu(kind):
            events.append(("cpu", kind))
            return 321 if present else None
        def initialize(path, params):
            events.append(("vad", path, params.n_threads, params.use_gpu, params.gpu_device))
            return 123
        return SimpleNamespace(ggml_backend_load_all_from_path=load,
                               ggml_backend_dev_by_type=cpu,
                               whisper_vad_init_from_file_with_params=initialize), events

    def test_managed_backend_load_and_cpu_gate_precede_vad_initialization(self):
        library, events = self.fixture(True)
        self.assertEqual(initialize_context(library, b"/pinned-model", b"/managed-backends"),
                         (123, 321))
        self.assertEqual(events, [("load", b"/managed-backends"), ("cpu", 0),
                                  ("vad", b"/pinned-model", 4, False, 0)])

    def test_missing_cpu_prevents_vad_initialization(self):
        library, events = self.fixture(False)
        with self.assertRaises(ValueError):
            initialize_context(library, b"/pinned-model", b"/managed-backends")
        self.assertEqual(events, [("load", b"/managed-backends"), ("cpu", 0)])


if __name__ == "__main__":
    unittest.main()
