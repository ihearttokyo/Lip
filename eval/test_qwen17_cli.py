"""Pure request/parser/safety fixtures and source checks; no native execution."""
import base64
import copy
import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import Mock, patch

import qwen17_cli as adapter


ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "validation/hillclimb/qwen17-hosted"
HOST = {"GITHUB_ACTIONS": "true", "RUNNER_OS": "Linux",
        "RUNNER_ENVIRONMENT": "github-hosted", "GITHUB_REPOSITORY": "ihearttokyo/Lip",
        "GITHUB_REPOSITORY_OWNER": "ihearttokyo", "GITHUB_EVENT_NAME": "push"}


def completion(content="language English<asr_text>Do not send -3.5 dollars."):
    return {"object": "chat.completion", "choices": [{"index": 0, "finish_reason": "stop",
            "message": {"role": "assistant", "content": content}}],
            "__verbose": {"index": 0, "id_slot": 0, "stop": True, "stop_type": "eos",
                          "truncated": False, "content": content}}


def encoded(value):
    return json.dumps(value, ensure_ascii=False).encode("utf-8")


def wave():
    fmt = struct.pack("<HHIIHH", 1, 1, 16000, 32000, 2, 16)
    chunks = b"fmt " + struct.pack("<I", len(fmt)) + fmt + b"data\x04\x00\x00\x00\0\0\1\0"
    return b"RIFF" + struct.pack("<I", len(chunks) + 4) + b"WAVE" + chunks


class Qwen17CliTest(unittest.TestCase):
    def test_preserves_lexical_content_and_full_model_content(self):
        for name, language, text in [("English", "en", " Do not send -3.5 dollars.\n"),
                                      ("English", "en", "repeat " * 24),
                                      ("Japanese", "ja", "いいえ。  いいえ。"),
                                      ("Chinese", "zh", "不是。不是。不是。")]:
            for newline in ["", "\n", "\r\n", "\n\n"]:
                raw = "language " + name + newline + "<asr_text>" + text
                with self.subTest(raw=raw):
                    self.assertEqual(adapter.parse_result(encoded(completion(raw)), language),
                                     {"raw": text, "model_raw": raw, "detected_language": language})

    def test_empty_requires_explicit_complete_none_and_never_discards_nonempty(self):
        raw = "language None\n<asr_text>"
        self.assertEqual(adapter.parse_result(encoded(completion(raw)), "ja"),
                         {"raw": "", "model_raw": raw, "detected_language": ""})
        for raw in ["", "language English<asr_text>", "language None<asr_text> ",
                    "language None<asr_text>not empty", "Error: audio failed"]:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                adapter.parse_result(encoded(completion(raw)), "en")

    def test_rejects_missing_duplicate_malformed_and_mismatched_metadata(self):
        for raw in ["speech", "<asr_text>speech", "Language English<asr_text>speech",
                    "language en<asr_text>speech", " language English<asr_text>speech",
                    "language English\nextra metadata<asr_text>speech",
                    "language English\nlanguage English<asr_text>speech",
                    "language English<asr_text>speech<asr_text>more",
                    "language Chinese<asr_text>speech", "language English <asr_text>speech"]:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                adapter.parse_result(encoded(completion(raw)), "en")

    def test_rejects_partial_error_tool_reasoning_and_unproven_completion(self):
        changes = [("finish_reason", "length"), ("finish_reason", None), ("index", 1)]
        for key, value in changes:
            body = completion()
            body["choices"][0][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                adapter.parse_result(encoded(body), "en")
        for key, value in [("stop_type", "word"), ("stop_type", "limit"), ("stop_type", "none"),
                           ("truncated", True), ("truncated", 0), ("stop", False),
                           ("content", "different"), ("id_slot", 1), ("index", True)]:
            body = completion()
            body["__verbose"][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                adapter.parse_result(encoded(body), "en")
        for key in completion()["__verbose"]:
            body = completion()
            del body["__verbose"][key]
            with self.subTest(missing=key), self.assertRaises(ValueError):
                adapter.parse_result(encoded(body), "en")
        for key, value in [("tool_calls", []), ("reasoning_content", ""), ("reasoning", None),
                           ("function_call", {}), ("refusal", "blocked"), ("role", "user"),
                           ("content", None)]:
            body = completion()
            body["choices"][0]["message"][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                adapter.parse_result(encoded(body), "en")
        for body in [{"error": "failure"}, dict(completion(), error={}),
                     dict(completion(), choices=[]),
                     dict(completion(), choices=completion()["choices"] * 2),
                     dict(completion(), object="chat.completion.chunk")]:
            with self.subTest(body=body), self.assertRaises(ValueError):
                adapter.parse_result(encoded(body), "en")

    def test_json_and_utf8_are_bounded_unique_and_finite(self):
        for data in [b'{} {}', b'[]', b'\xff', b'{"error":0,"error":1}',
                     b'{"x":NaN}', b'{"x":Infinity}', b'{"x":1e400}',
                     b'{"x":"\\ud800"}', b'{' * 1100, b' ' * (adapter.MAX_OUTPUT_BYTES + 1)]:
            with self.subTest(data=data[:40]), self.assertRaises(ValueError):
                adapter.parse_result(data, "en")
        valid = encoded(completion())
        for data in [valid.replace(b'"finish_reason": "stop"',
                                   b'"finish_reason": "length", "finish_reason": "stop"'),
                     valid[:-1] + b',"extra":NaN}', valid[:-1] + b',"extra":1e400}',
                     valid[:-1] + b',"extra":"\\ud800"}']:
            with self.subTest(data=data[-40:]), self.assertRaises(ValueError):
                adapter.parse_result(data, "en")

    def test_request_is_original_wave_auto_language_and_no_reference_context(self):
        audio = wave()
        body = adapter.request_body(audio)
        self.assertEqual(set(body), {"model", "messages", "stream", "verbose", "n",
                                     "max_tokens", "temperature", "cache_prompt"})
        self.assertEqual({key: body[key] for key in body if key != "messages"},
                         {"model": "qwen17-research", "stream": False, "verbose": True,
                          "n": 1, "max_tokens": 512, "temperature": 0, "cache_prompt": False})
        self.assertEqual(body["messages"], [{"role": "system", "content": ""},
                         {"role": "user", "content": [{"type": "input_audio", "input_audio": {
                             "data": base64.b64encode(audio).decode("ascii"), "format": "wav"}}]}])
        self.assertEqual(base64.b64decode(body["messages"][1]["content"][0]["input_audio"]["data"]), audio)
        for bad in [b"", audio[:-1], b"x" * (4 * 1024 * 1024 + 1)]:
            with self.subTest(size=len(bad)), self.assertRaises(ValueError):
                adapter.request_body(bad)

    def test_host_guard_denies_mac_root_other_repo_and_nonhosted_contexts(self):
        adapter.require_host(HOST, "linux", 1001)
        for platform, uid, env in [("darwin", 501, HOST), ("linux", 0, HOST),
                                   ("linux", 1001, {}),
                                   ("linux", 1001, dict(HOST, RUNNER_ENVIRONMENT="self-hosted")),
                                   ("linux", 1001, dict(HOST, GITHUB_REPOSITORY="other/Lip")),
                                   ("linux", 1001, dict(HOST, GITHUB_EVENT_NAME="pull_request"))]:
            with self.subTest(platform=platform, uid=uid, env=env), self.assertRaises(ValueError):
                adapter.require_host(env, platform, uid)

    def test_public_repository_metadata_is_required(self):
        event = {"repository": {"full_name": "ihearttokyo/Lip", "private": False,
                                "visibility": "public", "owner": {"login": "ihearttokyo"}},
                 "head_commit": {"message": "research [qwen17-canary]"}}
        adapter.require_public_repo(event)
        for field, value in [("private", True), ("private", 0), ("visibility", "private"),
                             ("full_name", "ihearttokyo/Other"), ("owner", {})]:
            changed = copy.deepcopy(event)
            changed["repository"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                adapter.require_public_repo(changed)
        for message in ["ordinary checks", "[android-cancel]", None]:
            changed = copy.deepcopy(event)
            changed["head_commit"]["message"] = message
            with self.subTest(message=message), self.assertRaises(ValueError):
                adapter.require_public_repo(changed)

    def test_denied_main_does_not_read_files_contact_http_or_spawn(self):
        with patch.object(adapter.sys, "platform", "darwin"), \
                patch.object(adapter, "load_json") as files, \
                patch.object(adapter.subprocess, "Popen") as child, \
                patch.object(adapter.http.client, "HTTPConnection") as network, \
                patch.object(adapter.sys, "stderr", io.StringIO()):
            with self.assertRaises(SystemExit) as exit_code:
                adapter.main()
            self.assertEqual(exit_code.exception.code, 1)
            files.assert_not_called()
            child.assert_not_called()
            network.assert_not_called()

    def test_owned_diagnostic_path_never_escapes_or_overwrites(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            directory = root / "lip-qwen17-evidence"
            directory.mkdir()
            with patch.dict(adapter.os.environ, {"RUNNER_TEMP": str(root)}):
                path = adapter.diagnostic_path(directory, Path("/audio/fleurs-en-013.wav"))
                self.assertEqual(path, directory.resolve() / "fleurs-en-013-native.log")
                with self.assertRaises(ValueError):
                    adapter.diagnostic_path(root, Path("/audio/case.wav"))
                path.write_text("preserve")
                with self.assertRaises(FileExistsError):
                    adapter.diagnostic_path(directory, Path("/audio/fleurs-en-013.wav"))
                self.assertEqual(path.read_text(), "preserve")
                self.assertEqual(adapter.diagnostic_path(directory, Path("/audio/fleurs-en-013.wav"), kind="failure"),
                                 directory.resolve() / "fleurs-en-013-failure.json")

    def test_main_retains_error_without_publishing_transcript(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            directory = root / "lip-qwen17-evidence"
            directory.mkdir()
            output = root / "transcript.json"
            argv = ["qwen17_cli", "--server", "server", "--model", "model", "--projector", "projector",
                    "--build-receipt", "receipt", "--audio", "fleurs-en-013.wav", "--output", str(output),
                    "--diagnostics", str(directory), "--language", "en"]
            with patch.object(adapter, "require_host"), patch.object(adapter.sys, "argv", argv), \
                    patch.dict(adapter.os.environ, {"RUNNER_TEMP": str(root)}), \
                    patch.object(adapter, "run", side_effect=ValueError("fixture protocol failure")), \
                    patch.object(adapter.sys, "stderr", io.StringIO()), self.assertRaises(SystemExit):
                adapter.main()
            failure = json.loads((directory / "fleurs-en-013-failure.json").read_text())
            self.assertEqual(failure["error_type"], "ValueError")
            self.assertFalse(output.exists())

    def test_binary_receipt_identity_not_a_hardcoded_mac_binary_hash(self):
        with tempfile.TemporaryDirectory() as temp:
            binary = Path(temp) / "synthetic-server"
            binary.write_bytes(b"fixture")
            receipt = {"source_revision": "71ad0590f4808b6202f9213d166913858c73b1bc",
                       "target": "llama-server", "cpu_only": True,
                       "binary": {"bytes": 7, "sha256": hashlib.sha256(b"fixture").hexdigest()}}
            adapter.verify_build(binary, receipt, receipt["source_revision"])
            for key, wrong in [("source_revision", "wrong"), ("target", "llama-mtmd-cli"),
                               ("cpu_only", False)]:
                with self.subTest(key=key), self.assertRaises(ValueError):
                    adapter.verify_build(binary, dict(receipt, **{key: wrong}), receipt["source_revision"])
            binary.write_bytes(b"changed")
            with self.assertRaises(ValueError):
                adapter.verify_build(binary, receipt, receipt["source_revision"])

    def test_weight_verification_cannot_skip_missing_duplicate_or_malformed_pins(self):
        with tempfile.TemporaryDirectory() as temp:
            model, projector = Path(temp) / "model", Path(temp) / "projector"
            model.write_bytes(b"model")
            projector.write_bytes(b"projector")
            pins = {"files": [{"name": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                              for name, data in [("Qwen3-ASR-1.7B-Q8_0.gguf", b"model"),
                                                 ("mmproj-Qwen3-ASR-1.7B-Q8_0.gguf", b"projector")]]}
            adapter.verify_weights(model, projector, pins)
            for bad in [{}, {"files": []}, {"files": pins["files"][:1]},
                        {"files": pins["files"] * 2}, {"files": [pins["files"][0]] * 2},
                        {"files": [dict(pins["files"][0], bytes=True), pins["files"][1]]}]:
                with self.subTest(bad=bad), self.assertRaises(ValueError):
                    adapter.verify_weights(model, projector, bad)
            projector.write_bytes(b"changed!!")
            with self.assertRaises(ValueError):
                adapter.verify_weights(model, projector, pins)

    def test_command_freezes_loopback_cpu_and_uses_no_detach_or_template_override(self):
        argv = adapter.command("/owned/llama-server", "/owned/model", "/owned/projector", 12345)
        self.assertEqual(argv, ["/owned/llama-server", "--model", "/owned/model", "--mmproj",
                         "/owned/projector", "--alias", "qwen17-research", "--host", "127.0.0.1",
                         "--port", "12345", "--threads", "4", "--threads-batch", "4",
                         "--threads-http", "1", "--parallel", "1", "--gpu-layers", "0",
                         "--no-mmproj-offload", "--no-kv-offload", "--no-op-offload", "--jinja",
                         "--ctx-size", "4096", "--batch-size", "512", "--ubatch-size", "512",
                         "--n-predict", "512", "--cache-ram", "0"])
        for port in [0, 65536, True, "12345 --host 0.0.0.0"]:
            with self.subTest(port=port), self.assertRaises(ValueError):
                adapter.command("server", "model", "projector", port)

    def test_listen_port_requires_loopback_listening_socket_owned_by_child(self):
        row = b"0: 0100007F:3039 00000000:0000 0A 0:0 00:0 0 1001 0 12345\n"
        self.assertTrue(adapter.listener_owned(row, {"socket:[12345]"}, 12345))
        for table, links in [(row, {"socket:[99999]"}),
                             (row.replace(b"0100007F", b"00000000"), {"socket:[12345]"}),
                             (row.replace(b" 0A ", b" 01 "), {"socket:[12345]"}),
                             (row.replace(b"3039", b"303A"), {"socket:[12345]"}),
                             (b"header only\n", {"socket:[12345]"})]:
            with self.subTest(table=table, links=links):
                self.assertFalse(adapter.listener_owned(table, links, 12345))

    def test_actual_props_qualify_audio_slot_model_and_embedded_template(self):
        props = {"total_slots": 1, "model_alias": "qwen17-research", "model_path": "/model.gguf",
                 "modalities": {"audio": True}, "chat_template": "actual embedded template",
                 "is_sleeping": False, "cors_proxy_enabled": False}
        self.assertEqual(adapter.qualify_props(props, "/model.gguf"), "actual embedded template")
        for key, wrong in [("total_slots", True), ("model_path", "/other.gguf"),
                           ("modalities", {"audio": False}), ("chat_template", ""),
                           ("is_sleeping", True), ("cors_proxy_enabled", True)]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                adapter.qualify_props(dict(props, **{key: wrong}), "/model.gguf")

    def test_frozen_model_pins(self):
        pins = adapter.load_json(ROOT / "eval/qwen17-pins.json")
        self.assertEqual(pins["runtime_revision"], "71ad0590f4808b6202f9213d166913858c73b1bc")
        self.assertIs(pins["research_only"], True)
        self.assertEqual(pins["weights"]["revision"], "36a678687ba7d07a74ca70ccb0e36902e005fb80")
        self.assertEqual([(pin["name"], pin["bytes"], pin["sha256"]) for pin in pins["weights"]["files"]],
                         [("Qwen3-ASR-1.7B-Q8_0.gguf", 2165034944,
                           "58e22d0532d4eacaf034cfac17a6fed159f37c41390c710186783be439d1fc57"),
                          ("mmproj-Qwen3-ASR-1.7B-Q8_0.gguf", 355709344,
                           "46c1d533af3f354ceb37ce855dbceff7da7fa7cf1e6a523df3b13440bd164c0d")])
        self.assertEqual(pins["limits"]["address_space_bytes"], adapter.ADDRESS_SPACE_BYTES)
        self.assertEqual(pins["limits"]["startup_seconds"], adapter.STARTUP_SECONDS)
        self.assertEqual(pins["limits"]["request_seconds"], adapter.REQUEST_SECONDS)
        self.assertEqual(pins["limits"]["output_bytes"], adapter.MAX_OUTPUT_BYTES)

    @unittest.skipUnless(SOURCE.is_dir(), "Private fetched-source evidence is not shipped in the repository")
    def test_static_fetched_source_contract_and_hashes(self):
        pins = adapter.load_json(ROOT / "eval/qwen17-pins.json")
        self.assertEqual(pins["weights"], json.loads((SOURCE / "weight-input-pins.json").read_text()))
        for name, digest in pins["source_sha256"].items():
            self.assertEqual(hashlib.sha256((SOURCE / name).read_bytes()).hexdigest(), digest, name)
        common = (SOURCE / "tools_server_server-common.cpp").read_text()
        self.assertIn('type == "input_audio"', common)
        self.assertIn('json_value(input_audio, "data",', common)
        task = (SOURCE / "tools_server_server-task.cpp").read_text()
        for evidence in ['res["__verbose"] = to_json_non_oaicompat()', 'return "eos";',
                         '{"truncated",           truncated}', '{"object",             "chat.completion"}']:
            self.assertIn(evidence, task)
        arg = (SOURCE / "common_arg.cpp").read_text()
        argv = adapter.command("server", "model", "projector", 12345)
        for flag in [value for value in argv if value.startswith("--")]:
            self.assertIn('"' + flag + '"', arg)

    def test_simulated_http_rejects_error_redirect_and_incomplete_transport(self):
        for status, declared_size, content_type in [(500, 2, "application/json"),
                                                   (302, 2, "application/json"),
                                                   (200, 20, "application/json"),
                                                   (200, 2, "text/event-stream")]:
            response = Mock(status=status, length=declared_size)
            response.getheader.return_value = content_type

            def read1(_):
                data = response.data
                response.data = b""
                response.length -= len(data)
                return data

            response.data = b"{}"
            response.read1.side_effect = read1
            connection = Mock()
            connection.getresponse.return_value = response
            with self.subTest(status=status, declared_size=declared_size), \
                    patch.object(adapter.http.client, "HTTPConnection", return_value=connection), \
                    patch.object(adapter.time, "monotonic", return_value=100), \
                    patch.object(adapter.signal, "getitimer", return_value=(0, 0)), \
                    patch.object(adapter.signal, "setitimer"), patch.object(adapter.signal, "signal"):
                with self.assertRaises(ValueError):
                    adapter.http_json(12345, "/v1/chat/completions", 280, {"fixture": True})
                connection.close.assert_called_once()

    def test_complete_malformed_response_is_retained_exactly_before_validation(self):
        for data in [b'{"choices":', b'{"x":1,"x":2}', b'\xff']:
            response = Mock(status=200, length=len(data))
            response.getheader.return_value = "application/json"
            response.read1.side_effect = [data, b""]
            response.length = 0
            connection = Mock()
            connection.getresponse.return_value = response
            with self.subTest(data=data), \
                    patch.object(adapter.http.client, "HTTPConnection", return_value=connection), \
                    patch.object(adapter.time, "monotonic", return_value=100), \
                    patch.object(adapter.signal, "getitimer", return_value=(0, 0)), \
                    patch.object(adapter.signal, "setitimer"), patch.object(adapter.signal, "signal"), \
                    tempfile.TemporaryDirectory() as temp:
                observed = adapter.http_json(12345, "/v1/chat/completions", 280, {})
                self.assertEqual(observed, data)
                path = Path(temp) / "response.json"
                adapter.publish_bytes(path, observed)
                with self.assertRaises((ValueError, UnicodeError)):
                    adapter.parse_result(observed, "en")
                self.assertEqual(path.read_bytes(), data)
                with self.assertRaises(FileExistsError):
                    adapter.publish_bytes(path, b"replacement")

    def test_simulated_http_absolute_deadline_covers_headers_and_restores_handler(self):
        captured = {}

        def set_handler(_signal, handler):
            captured["handler"] = handler
            return "previous handler"

        connection = Mock()
        connection.getresponse.side_effect = lambda: captured["handler"](None, None)
        with patch.object(adapter.http.client, "HTTPConnection", return_value=connection), \
                patch.object(adapter.time, "monotonic", return_value=100), \
                patch.object(adapter.signal, "getitimer", return_value=(0, 0)), \
                patch.object(adapter.signal, "setitimer") as timer, \
                patch.object(adapter.signal, "signal", side_effect=set_handler) as handler:
            with self.assertRaises(TimeoutError):
                adapter.http_json(12345, "/v1/chat/completions", 280, {"fixture": True})
            self.assertEqual(timer.call_args_list[0].args, (adapter.signal.ITIMER_REAL, 180))
            self.assertEqual(timer.call_args_list[-1].args, (adapter.signal.ITIMER_REAL, 0))
            self.assertEqual(handler.call_args_list[-1].args, (adapter.signal.SIGALRM, "previous handler"))
            connection.close.assert_called_once()

    def test_simulated_http_success_and_streamed_output_bound(self):
        for data, accepted in [(encoded(completion()), True),
                               (b"{}" + b" " * adapter.MAX_OUTPUT_BYTES, False)]:
            response = Mock(status=200, length=len(data))
            response.getheader.return_value = "application/json; charset=utf-8"
            stream = io.BytesIO(data)

            def read1(limit):
                chunk = stream.read(min(limit, 17))
                response.length -= len(chunk)
                return chunk

            response.read1.side_effect = read1
            connection = Mock()
            connection.getresponse.return_value = response
            with self.subTest(accepted=accepted), \
                    patch.object(adapter.http.client, "HTTPConnection", return_value=connection) as constructor, \
                    patch.object(adapter.time, "monotonic", return_value=100), \
                    patch.object(adapter.signal, "getitimer", return_value=(0, 0)), \
                    patch.object(adapter.signal, "setitimer"), patch.object(adapter.signal, "signal"):
                if accepted:
                    self.assertEqual(adapter.http_json(12345, "/v1/chat/completions", 280, {"fixture": True}), data)
                else:
                    with self.assertRaises(ValueError):
                        adapter.http_json(12345, "/v1/chat/completions", 280, {"fixture": True})
                constructor.assert_called_once_with("127.0.0.1", 12345, timeout=180)
                self.assertEqual(connection.request.call_args.args[:2], ("POST", "/v1/chat/completions"))
                connection.close.assert_called_once()

    def test_owned_shutdown_joins_even_if_pid_exits_before_terminate(self):
        child = Mock()
        child.poll.return_value = None
        child.terminate.side_effect = ProcessLookupError()
        adapter.stop_owned(child)
        child.wait.assert_called_once_with(timeout=5)
        child.kill.assert_not_called()

    def test_owned_shutdown_kill_fallback_targets_only_the_supplied_child(self):
        child = Mock()
        child.poll.return_value = None
        child.wait.side_effect = [adapter.subprocess.TimeoutExpired("owned fixture", 5), 0]
        adapter.stop_owned(child)
        child.terminate.assert_called_once_with()
        child.kill.assert_called_once_with()
        self.assertEqual([call.args for call in child.wait.call_args_list], [(), ()])
        self.assertEqual([call.kwargs for call in child.wait.call_args_list], [{"timeout": 5}] * 2)

    def test_publish_is_bounded_atomic_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "result.json"
            value = {"raw": "いいえ。", "model_raw": "language Japanese<asr_text>いいえ。",
                     "detected_language": "ja"}
            adapter.publish(output, value)
            self.assertEqual(json.loads(output.read_text()), value)
            with self.assertRaises(FileExistsError):
                adapter.publish(output, {"raw": "replacement"})
            self.assertEqual(json.loads(output.read_text()), value)
            for bad in [{"raw": "x" * adapter.MAX_OUTPUT_BYTES}, {"raw": float("inf")},
                        {"raw": "\ud800"}]:
                missing = Path(temp) / "must-not-exist.json"
                with self.subTest(bad_type=type(bad["raw"])), self.assertRaises(ValueError):
                    adapter.publish(missing, bad)
                self.assertFalse(missing.exists())
            self.assertEqual(list(Path(temp).iterdir()), [output])


if __name__ == "__main__":
    unittest.main()
