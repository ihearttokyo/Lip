"""Hosted orchestration fixtures; no downloads, builds, or model inference."""
import hashlib
import copy
import io
import json
from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import patch

import qwen17_ci as ci
import qwen17_cli as adapter
from benchmark import score_case


ROOT = Path(__file__).resolve().parent


def inputs():
    manifest = json.loads((ROOT / "corpus.json").read_text())
    pins = json.loads((ROOT / "qwen17-pins.json").read_text())
    receipt = json.loads((ROOT / "qwen17-canary-receipt.json").read_text())
    manifest_sha = hashlib.sha256((ROOT / "corpus.json").read_bytes()).hexdigest()
    scorer_sha = hashlib.sha256((ROOT / "benchmark.py").read_bytes()).hexdigest()
    event = {"repository": {"full_name": "ihearttokyo/Lip", "private": False,
                           "visibility": "public", "owner": {"login": "ihearttokyo"}},
             "head_commit": {"message": "research [qwen17-canary] [qwen17-corpus]"}}
    build = {"source_revision": pins["runtime_revision"], "target": "llama-server", "cpu_only": True,
             "binary": {"bytes": 7, "sha256": hashlib.sha256(b"newbuild").hexdigest()},
             "weights": pins["weights"]}
    return manifest, pins, receipt, scorer_sha, manifest_sha, event, build


def result_for(case, pins, build, raw=None):
    names = {"en": "English", "ja": "Japanese", "zh": "Chinese"}
    raw = case["reference_raw"] if raw is None else raw
    model_raw = "language " + names[case["language"]] + "<asr_text>" + raw
    response = {"object": "chat.completion", "choices": [{"index": 0, "finish_reason": "stop",
                "message": {"role": "assistant", "content": model_raw}}],
                "__verbose": {"index": 0, "id_slot": 0, "stop": True, "stop_type": "eos",
                              "truncated": False, "content": model_raw,
                              "generation_settings": {"temperature": 0.0}}}
    props = {"total_slots": 1, "model_alias": adapter.MODEL_ALIAS, "model_path": "/owned/model",
             "modalities": {"audio": True}, "chat_template": "fixture template",
             "is_sleeping": False, "cors_proxy_enabled": False}
    research = {"runtime_revision": pins["runtime_revision"], "binary": build["binary"],
                "server_argv": adapter.command("/owned/server", "/owned/model", "/owned/projector", 12345),
                "server_props": props, "http_response": response,
                "embedded_template_sha256": hashlib.sha256(b"fixture template").hexdigest(),
                "cold_start_seconds": 1, "request_seconds": 5,
                "address_space_bytes": 12 * 1024 ** 3, "server_peak_rss_bytes": 3295793152}
    return {"id": case["id"], "status": "ok", "raw": raw, "clean": None,
            "score": score_case(case, raw), "elapsed_seconds": 8,
            "engine_evidence": {"model_raw": model_raw, "detected_language": case["language"],
                                "research": research}}


class Qwen17CiTest(unittest.TestCase):
    def test_corpus_marker_requires_public_canary_marker_and_reviewed_matching_receipt(self):
        manifest, pins, receipt, scorer_sha, manifest_sha, event, _ = inputs()
        selected = ci.select_cases(event, manifest, pins, scorer_sha, manifest_sha, receipt)
        expected = ["fleurs-en-013"] + [c["id"] for c in manifest["cases"] if c["id"] != "fleurs-en-013"]
        self.assertEqual([c["id"] for c in selected], expected)
        self.assertEqual(len(selected), len({c["id"] for c in selected}))
        event["head_commit"]["message"] = "research [qwen17-canary]"
        self.assertEqual([c["id"] for c in ci.select_cases(event, manifest, pins, scorer_sha, manifest_sha)],
                         ["fleurs-en-013"])
        for message in ["ordinary checks", "research [qwen17-corpus]"]:
            event["head_commit"]["message"] = message
            with self.subTest(message=message), self.assertRaises(ValueError):
                ci.select_cases(event, manifest, pins, scorer_sha, manifest_sha, receipt)
        event["head_commit"]["message"] = "[qwen17-canary] [qwen17-corpus]"
        with self.assertRaises(ValueError):
            ci.select_cases(event, manifest, pins, scorer_sha, manifest_sha)
        changes = [("passed", False), ("runtime_revision", "wrong"), ("weight_sha256", []),
                   ("case_id", "other"), ("audio_sha256", "0" * 64), ("scorer_sha256", "0" * 64),
                   ("source_manifest_sha256", "0" * 64), ("scorer_version", 1), ("max_error_rate", .5),
                   ("critical_checks", []), ("raw_errors", 1), ("natural_eos", False), ("truncated", True),
                   ("cpu_threads", True), ("server_peak_rss_bytes", 0),
                   ("server_peak_rss_bytes", 12 * 1024 ** 3 + 1), ("cold_start_seconds", 91),
                   ("request_seconds", 181), ("outer_elapsed_seconds", 301), ("request_seconds", float("nan"))]
        for field, value in changes:
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                ci.select_cases(event, manifest, pins, scorer_sha, manifest_sha, dict(receipt, **{field: value}))
        for field in ["source_manifest_sha256", "template_sha256", "response_sha256", "admission"]:
            changed = dict(receipt)
            changed.pop(field)
            with self.subTest(missing=field), self.assertRaises(ValueError):
                ci.select_cases(event, manifest, pins, scorer_sha, manifest_sha, changed)
        for field, value in [("reference_raw", "easier"), ("checks", []), ("max_error_rate", .5)]:
            changed = copy.deepcopy(manifest)
            changed["cases"][0][field] = value
            changed_sha = hashlib.sha256(json.dumps(changed).encode()).hexdigest()
            with self.subTest(manifest_field=field), self.assertRaises(ValueError):
                ci.select_cases(event, changed, pins, scorer_sha, changed_sha, receipt)

    def test_fresh_canary_qualifies_current_build_not_old_binary_and_rejects_bad_evidence(self):
        manifest, pins, receipt, _, _, _, build = inputs()
        case = next(c for c in manifest["cases"] if c["id"] == "fleurs-en-013")
        result = result_for(case, pins, build)
        template_sha = result["engine_evidence"]["research"]["embedded_template_sha256"]
        self.assertNotEqual(build["binary"], receipt["binary"])
        self.assertTrue(ci.qualify_result(case, result, pins, build, template_sha))
        for field, value in [("runtime_revision", "wrong"), ("binary", receipt["binary"]),
                             ("server_peak_rss_bytes", 0), ("server_peak_rss_bytes", 12 * 1024 ** 3 + 1),
                             ("cold_start_seconds", 91), ("request_seconds", 181),
                             ("request_seconds", float("inf")), ("address_space_bytes", 1),
                             ("embedded_template_sha256", "0" * 64)]:
            changed = copy.deepcopy(result)
            changed["engine_evidence"]["research"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                ci.qualify_result(case, changed, pins, build, template_sha)
        for mutate in [lambda r: r.update(elapsed_seconds=301), lambda r: r.pop("engine_evidence"),
                       lambda r: r.update(raw="easier"),
                       lambda r: r["engine_evidence"]["research"]["http_response"]["__verbose"].update(stop_type="limit"),
                       lambda r: r["engine_evidence"]["research"]["http_response"]["__verbose"]["generation_settings"].update(temperature=.5),
                       lambda r: r["engine_evidence"]["research"]["server_argv"].__setitem__(12, "8")]:
            changed = copy.deepcopy(result)
            mutate(changed)
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                ci.qualify_result(case, changed, pins, build, template_sha)
        changed = copy.deepcopy(result)
        changed["score"]["passed"] = False
        with self.assertRaises(ValueError):
            ci.qualify_result(case, changed, pins, build, template_sha)

    def test_full18_retains_each_failure_immediately_and_reuses_one_en13(self):
        manifest, pins, receipt, scorer_sha, manifest_sha, event, build = inputs()
        cases = ci.select_cases(event, manifest, pins, scorer_sha, manifest_sha, receipt)
        receipt["template_sha256"] = hashlib.sha256(b"fixture template").hexdigest()
        bindings = {"scorer_version": 2, "scorer_sha256": scorer_sha,
                    "source_manifest_sha256": manifest_sha, "manifest_sha256": "a" * 64,
                    "build": build, "reviewed_canary_receipt": receipt}
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            seen = []

            def run(case, work, command, timeout):
                self.assertEqual(timeout, 300)
                for case_id in seen:
                    self.assertTrue((root / (case_id + "-score.json")).is_file())
                self.assertFalse((root / "corpus-results.json").exists())
                if seen:
                    self.assertTrue((root / "canary.json").is_file())
                seen.append(case["id"])
                if len(seen) == 2:
                    raise ValueError("fixture invalid input/output")
                if len(seen) in (3, 4):
                    return {"id": case["id"], "status": "engine_error" if len(seen) == 3 else "timeout"}
                if len(seen) == 5:
                    return result_for(case, pins, build, case["reference_raw"].replace("1000", "9999"))
                return result_for(case, pins, build)

            with patch.object(ci, "run_case", side_effect=run):
                report = ci.run_cases(cases, root, ["fixture"], root, pins, bindings)
            self.assertEqual(seen, [c["id"] for c in cases])
            self.assertEqual(len(seen), 18)
            self.assertEqual(seen.count("fleurs-en-013"), 1)
            self.assertEqual(report["execution_order"], seen)
            self.assertFalse(report["passed"])
            self.assertEqual([r["status"] for r in report["cases"]][1:4],
                             ["invalid_input_or_output", "engine_error", "timeout"])
            self.assertFalse(report["cases"][4]["score"]["passed"])
            self.assertEqual(report, json.loads((root / "corpus-results.json").read_text()))
            canary = json.loads((root / "canary.json").read_text())
            self.assertTrue(canary["passed"])
            self.assertEqual(canary["execution_order"], ["fleurs-en-013"])
            for case in cases:
                self.assertEqual(json.loads((root / (case["id"] + "-score.json")).read_text())["cases"][0]["audio_sha256"],
                                 case["sha256"])

    def test_failed_fresh_en13_never_starts_remaining_cases_or_publishes_corpus_report(self):
        manifest, pins, receipt, scorer_sha, manifest_sha, event, build = inputs()
        cases = ci.select_cases(event, manifest, pins, scorer_sha, manifest_sha, receipt)
        receipt["template_sha256"] = hashlib.sha256(b"fixture template").hexdigest()
        bindings = {"build": build, "reviewed_canary_receipt": receipt}
        good = result_for(cases[0], pins, build)
        malformed = copy.deepcopy(good)
        malformed["engine_evidence"]["research"]["server_peak_rss_bytes"] = 0
        quality = result_for(cases[0], pins, build, "wrong")
        for outcome in [ValueError("bad input"), {"id": cases[0]["id"], "status": "timeout"},
                        malformed, quality]:
            with self.subTest(outcome=outcome), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                with patch.object(ci, "run_case", side_effect=[outcome]) as runner:
                    report = ci.run_cases(cases, root, ["fixture"], root, pins, bindings)
                runner.assert_called_once()
                self.assertFalse(report["passed"])
                self.assertFalse((root / "corpus-results.json").exists())
                self.assertTrue((root / "fleurs-en-013-score.json").is_file())
                self.assertTrue((root / "canary.json").is_file())

    def test_all18_and_canary_only_pass_only_when_every_selected_case_qualifies(self):
        manifest, pins, receipt, scorer_sha, manifest_sha, event, build = inputs()
        cases = ci.select_cases(event, manifest, pins, scorer_sha, manifest_sha, receipt)
        receipt["template_sha256"] = hashlib.sha256(b"fixture template").hexdigest()
        bindings = {"build": build, "reviewed_canary_receipt": receipt}
        for selection in [cases, cases[:1]]:
            with self.subTest(count=len(selection)), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                with patch.object(ci, "run_case", side_effect=lambda c, *_a, **_kw: result_for(c, pins, build)) as runner:
                    report = ci.run_cases(selection, root, ["fixture"], root, pins, bindings)
                self.assertTrue(report["passed"])
                self.assertEqual(runner.call_count, len(selection))
                self.assertEqual((root / "corpus-results.json").exists(), len(selection) == 18)
                self.assertEqual(report["execution_order"], [c["id"] for c in selection])

    def test_result_publication_never_overwrites_prior_raw_or_failed_diagnostics(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name in ["canary.json", "fleurs-en-013-score.json", "corpus-results.json"]:
                path = root / name
                path.write_bytes(b"preserve original raw/failed record")
                with self.assertRaises(FileExistsError):
                    ci.save(path, {"passed": True})
                self.assertEqual(path.read_bytes(), b"preserve original raw/failed record")

    def test_unjoined_group_timeout_is_retained_but_never_launches_another_case(self):
        manifest, pins, receipt, scorer_sha, manifest_sha, event, build = inputs()
        cases = ci.select_cases(event, manifest, pins, scorer_sha, manifest_sha, receipt)
        receipt["template_sha256"] = hashlib.sha256(b"fixture template").hexdigest()
        bindings = {"build": build, "reviewed_canary_receipt": receipt}
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            outcomes = [result_for(cases[0], pins, build), ci.subprocess.TimeoutExpired("owned-group fixture", 5)]
            with patch.object(ci, "run_case", side_effect=outcomes) as runner, self.assertRaises(RuntimeError):
                ci.run_cases(cases, root, ["fixture"], root, pins, bindings)
            self.assertEqual(runner.call_count, 2)
            failed = json.loads((root / (cases[1]["id"] + "-score.json")).read_text())
            self.assertFalse(failed["passed"])
            self.assertEqual(failed["cases"][0]["status"], "cleanup_timeout")
            self.assertEqual(failed["cases"][0]["error_type"], "TimeoutExpired")
            self.assertFalse((root / "corpus-results.json").exists())

    def test_main_rejects_unmarked_or_unqualified_full_mode_before_acquisition(self):
        manifest, pins, receipt, _, _, event, _ = inputs()
        host = {"GITHUB_ACTIONS": "true", "RUNNER_OS": "Linux", "RUNNER_ENVIRONMENT": "github-hosted",
                "GITHUB_REPOSITORY": "ihearttokyo/Lip", "GITHUB_REPOSITORY_OWNER": "ihearttokyo",
                "GITHUB_EVENT_NAME": "push", "GITHUB_EVENT_PATH": "fixture-event"}
        bad_receipt = dict(receipt)
        bad_receipt.pop("source_manifest_sha256")
        for message in ["[qwen17-corpus]", "[qwen17-canary] [qwen17-corpus]"]:
            changed = copy.deepcopy(event)
            changed["head_commit"]["message"] = message
            with self.subTest(message=message), patch.dict(ci.os.environ, host, clear=True), \
                    patch.object(ci.sys, "platform", "linux"), patch.object(ci.os, "geteuid", return_value=1001), \
                    patch.object(ci, "load_json", side_effect=[changed, pins, manifest, bad_receipt]), \
                    patch.object(ci.shutil, "disk_usage") as capacity, patch.object(ci, "download") as acquisition, \
                    patch.object(ci, "run_logged") as build, patch.object(ci, "run_case") as inference, \
                    self.assertRaises(ValueError):
                ci.main()
            capacity.assert_not_called()
            acquisition.assert_not_called()
            build.assert_not_called()
            inference.assert_not_called()

    def test_main_mocked_public_push_binds_manifest_build_and_exact_execution_order(self):
        manifest, pins, receipt, scorer_sha, manifest_sha, event, _ = inputs()
        receipt["template_sha256"] = hashlib.sha256(b"fixture template").hexdigest()
        for full in [False, True]:
            with self.subTest(full=full), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                changed = copy.deepcopy(event)
                if not full:
                    changed["head_commit"]["message"] = "[qwen17-canary]"
                event_path = root / "event.json"
                event_path.write_text(json.dumps(changed))
                host = {"GITHUB_ACTIONS": "true", "RUNNER_OS": "Linux", "RUNNER_ENVIRONMENT": "github-hosted",
                        "GITHUB_REPOSITORY": "ihearttokyo/Lip", "GITHUB_REPOSITORY_OWNER": "ihearttokyo",
                        "GITHUB_EVENT_NAME": "push", "GITHUB_EVENT_PATH": str(event_path), "PATH": ci.os.defpath,
                        "RUNNER_TEMP": str(root), "GITHUB_SHA": "new-fixture-commit", "GITHUB_RUN_ID": "1",
                        "GITHUB_RUN_ATTEMPT": "1"}
                original_load = ci.load_json

                def load(path):
                    return receipt if Path(path).name == "qwen17-canary-receipt.json" else original_load(path)

                def build(command, *_args):
                    if command[:2] == ["cmake", "--build"]:
                        directory = Path(command[2])
                        (directory / "bin").mkdir(parents=True)
                        (directory / "bin/llama-server").write_bytes(b"fixture, not a model executable")
                        (directory / "CMakeCache.txt").write_text("fixture")

                def run(case, work, command, timeout):
                    current_build = original_load(root / "lip-qwen17-evidence/build.json")
                    result = result_for(case, pins, current_build)
                    argv = adapter.command(*(command[command.index(flag) + 1]
                                             for flag in ("--server", "--model", "--projector")), 12345)
                    research = result["engine_evidence"]["research"]
                    research["server_argv"] = argv
                    research["server_props"]["model_path"] = argv[2]
                    return result

                with patch.dict(ci.os.environ, host, clear=True), patch.object(ci.sys, "platform", "linux"), \
                        patch.object(ci.os, "geteuid", return_value=1001), patch.object(ci, "load_json", side_effect=load), \
                        patch.object(ci.resource, "setrlimit"), patch.object(ci.os, "sched_getaffinity", return_value={0, 1, 2, 3}, create=True), \
                        patch.object(ci, "run_logged", side_effect=build) as builds, patch.object(ci, "download") as downloads, \
                        patch.object(ci, "fetch"), patch.object(ci.subprocess, "check_output", return_value=pins["runtime_revision"]), \
                        patch.object(ci.subprocess, "Popen", side_effect=AssertionError("No real build or model process")), \
                        patch.object(ci, "run_case", side_effect=run) as runner, patch.object(ci.sys, "stdout", io.StringIO()), \
                        self.assertRaises(SystemExit) as exit_code:
                    ci.main()
                self.assertEqual(exit_code.exception.code, 0)
                self.assertEqual(builds.call_count, 5)
                self.assertEqual(downloads.call_count, 2)
                self.assertEqual(runner.call_count, 18 if full else 1)
                evidence = root / "lip-qwen17-evidence"
                report = original_load(evidence / ("corpus-results.json" if full else "canary.json"))
                expected = ["fleurs-en-013"] + ([c["id"] for c in manifest["cases"] if c["id"] != "fleurs-en-013"] if full else [])
                self.assertTrue(report["passed"])
                self.assertEqual(report["execution_order"], expected)
                self.assertEqual(report["source_manifest_sha256"], manifest_sha)
                self.assertEqual(report["manifest_sha256"], hashlib.sha256((evidence / "corpus.json").read_bytes()).hexdigest())
                self.assertEqual(report["scorer_sha256"], scorer_sha)
                self.assertEqual(report["build"]["host"]["cpu_affinity"], [0, 1, 2, 3])
                self.assertEqual(report["build"]["github_sha"], "new-fixture-commit")

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
