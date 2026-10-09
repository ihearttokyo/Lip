"""Simulated CLI/parser contracts, not ASR accuracy or latency evidence."""
import json
import hashlib
from pathlib import Path
import tempfile
import unittest

from sensevoice_cli import MAX_OUTPUT_BYTES, command, parse_result, verify_file


def result(text="Do not send it."):
    return json.dumps({"text": text, "lang": "<|en|>", "emotion": "<|NEUTRAL|>",
                       "event": "<|Speech|>", "tokens": [text] if text else [],
                       "timestamps": [0.1] if text else [], "durations": []}).encode()


COMPLETE = (b"Creating recognizer ...\nrecognizer created in 0.100 s\nStarted\nDone!\n"
            b"/fixture.wav\n----\nnum threads: 4\ndecoding method: greedy_search\n"
            b"Elapsed seconds: 0.123 s\nReal time factor (RTF): 0.123 / 5.000 = 0.025\n")


class SenseVoiceCliTest(unittest.TestCase):
    def test_preserves_multilingual_raw_and_accepts_only_proven_empty_completion(self):
        for text in ["Do not send -3.5 dollars.", "いいえ。", "不是。", ""]:
            with self.subTest(text=text):
                self.assertEqual(parse_result(result(text), COMPLETE, 0), text)
        for stderr in [b"", COMPLETE.replace(b"Done!\n", b""),
                       COMPLETE.replace(b"recognizer created in 0.100 s\n", b"")]:
            with self.subTest(stderr=stderr):
                with self.assertRaises(ValueError):
                    parse_result(result(""), stderr, 0)

    def test_native_failures_cannot_become_successful_empty_or_nonempty_transcripts(self):
        for error in [b"Caught exception: ONNX failure", b"Return an empty result",
                      b"[E:onnxruntime:kernel] internal failure", b"GGML_ASSERT(device)",
                      b"Failed to read audio", b"Errors in config!", b"Invalid input",
                      b"terminate called after throwing an instance"]:
            for text in ["", "a plausible partial"]:
                with self.subTest(error=error, text=text):
                    with self.assertRaises(ValueError):
                        parse_result(result(text), COMPLETE + error + b"\n", 0)
        with self.assertRaises(ValueError):
            parse_result(result(""), COMPLETE, 1)

    def test_rejects_duplicate_keys_multiple_results_and_malformed_json_utf8(self):
        for stdout in [b'{"text":"","text":"partial"}', result() + b"\n" + result(),
                       b'{"text":', b'\xff', b'[]', b'{"text":null}',
                       b'{"text":"","tokens":[],"timestamps":[NaN]}']:
            with self.subTest(stdout=stdout):
                with self.assertRaises(ValueError):
                    parse_result(stdout, COMPLETE, 0)

    def test_rejects_malformed_native_result_shape(self):
        base = json.loads(result())
        for field, wrong in [("text", 1), ("tokens", [None]), ("timestamps", [-1]),
                             ("timestamps", [True]), ("timestamps", [float("inf")]),
                             ("timestamps", [10 ** 400]), ("timestamps", [30.1]),
                             ("lang", None), ("event", []), ("emotion", 0)]:
            changed = dict(base, **{field: wrong})
            with self.subTest(field=field, wrong=wrong):
                with self.assertRaises(ValueError):
                    parse_result(json.dumps(changed).encode(), COMPLETE, 0)
        del base["tokens"]
        with self.assertRaises(ValueError):
            parse_result(json.dumps(base).encode(), COMPLETE, 0)

    def test_output_bound_and_completion_configuration_are_enforced(self):
        for stdout, stderr in [(result("a" * MAX_OUTPUT_BYTES), COMPLETE),
                               (result(), COMPLETE + b"a" * MAX_OUTPUT_BYTES),
                               (result(), COMPLETE.replace(b"num threads: 4", b"num threads: 1")),
                               (result(), COMPLETE.replace(b"greedy_search", b"modified_beam_search"))]:
            with self.subTest(stdout_size=len(stdout), stderr_size=len(stderr)):
                with self.assertRaises(ValueError):
                    parse_result(stdout, stderr, 0)

    def test_command_freezes_cpu4_explicit_language_itn_off_and_greedy_without_shell(self):
        for language in ["en", "ja", "zh"]:
            expected = ["/runtime/bin/sherpa-onnx-offline", "--tokens=/models/tokens.txt",
                        "--sense-voice-model=/models/model.int8.onnx", "--num-threads=4",
                        "--provider=cpu", "--debug=0", "--decoding-method=greedy_search",
                        "--sense-voice-language=" + language, "--sense-voice-use-itn=0",
                        "/audio/space ; $HOME.wav"]
            self.assertEqual(command(Path(expected[0]), Path("/models/model.int8.onnx"),
                                     Path("/models/tokens.txt"), Path(expected[-1]), language), expected)

    def test_command_rejects_auto_and_unsupported_languages(self):
        for language in ["auto", "", "ko", "en --provider=cuda", None]:
            with self.subTest(language=language):
                with self.assertRaises(ValueError):
                    command("cli", "model", "tokens", "audio", language)

    def test_research_artifact_identity_checks_actual_file_size_and_sha256(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "synthetic-identity-fixture"
            path.write_bytes(b"fixture")
            digest = hashlib.sha256(b"fixture").hexdigest()
            verify_file(path, 7, digest)
            with self.assertRaises(ValueError):
                verify_file(path, 6, digest)
            path.write_bytes(b"changed")
            with self.assertRaises(ValueError):
                verify_file(path, 7, digest)


if __name__ == "__main__":
    unittest.main()
