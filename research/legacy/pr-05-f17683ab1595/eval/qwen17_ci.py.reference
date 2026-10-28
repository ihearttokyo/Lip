"""Canary-gated unprivileged hosted CPU research; never a production backend."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import resource
import re
import signal
import selectors
import subprocess
import sys
import tempfile
import time
import urllib.request

from benchmark import SCORER_VERSION, run_case, score_case, stop_owned_group
from fetch_corpus import fetch
from qwen17_cli import (ADDRESS_SPACE_BYTES, STARTUP_SECONDS, REQUEST_SECONDS, command as server_command,
                        load_json, parse_result, qualify_props, require_host, require_public_repo)


def require_capacity(free_bytes):
    if free_bytes < 6 * 1024 ** 3:
        raise ValueError("Require six GiB free before source/build/weight acquisition")


def download(pin, path):
    digest, size = hashlib.sha256(), 0
    if signal.getitimer(signal.ITIMER_REAL) != (0.0, 0.0):
        raise ValueError("Refuse to replace an existing acquisition deadline")
    deadline = time.monotonic() + 600

    def expired(_signal, _frame):
        raise TimeoutError("Pinned acquisition deadline exceeded")

    with path.open("xb") as target:
        previous = signal.signal(signal.SIGALRM, expired)
        try:
            signal.setitimer(signal.ITIMER_REAL, 600)
            with urllib.request.urlopen(pin["url"], timeout=45) as source:
                while chunk := source.read(1024 * 1024):
                    size += len(chunk)
                    if size > pin["bytes"] or time.monotonic() > deadline:
                        raise ValueError("Weight acquisition exceeded its byte/time bound")
                    digest.update(chunk)
                    target.write(chunk)
                if time.monotonic() > deadline:
                    raise ValueError("Weight completion exceeded its time bound")
        except (OSError, ValueError):
            # Provider exception text can contain signed delivery URLs.
            raise ValueError("Pinned weight acquisition failed") from None
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
            signal.signal(signal.SIGALRM, previous)
    if size != pin["bytes"] or digest.hexdigest() != pin["sha256"]:
        raise ValueError("Complete weight size or SHA-256 mismatch")


def run_logged(command, env, log, timeout=900, byte_limit=1024 * 1024):
    deadline = time.monotonic() + timeout
    child = subprocess.Popen(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             start_new_session=True)
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(child.stdout, selectors.EVENT_READ)
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise subprocess.TimeoutExpired(command, timeout)
                if not selector.select(remaining):
                    continue
                chunk = os.read(child.stdout.fileno(), 65536)
                if not chunk:
                    break
                available = max(0, byte_limit - log.tell())
                log.write(chunk[:available])
                log.flush()
                if len(chunk) > available:
                    raise ValueError("Build diagnostics exceeded the one MiB retained-output bound")
        code = child.wait(timeout=max(.001, deadline - time.monotonic()))
        if code:
            raise subprocess.CalledProcessError(code, command)
    finally:
        try:
            stop_owned_group(child)
        finally:
            child.stdout.close()


def verify_admission(original, admitted):
    expected, observed = json.loads(json.dumps(original)), json.loads(json.dumps(admitted))
    for manifest in (expected, observed):
        manifest.pop("status", None)
        manifest.pop("admitted_utc", None)
        for case in manifest["cases"]:
            case.pop("admission", None)
    if expected != observed:
        raise ValueError("Corpus references, gates, source identity or audio pins drifted")


def save(path, data):
    with path.open("x", encoding="utf-8") as output:
        json.dump(data, output, ensure_ascii=False, indent=2, allow_nan=False)
        output.write("\n")


def within(value, bound):
    return type(value) in (int, float) and 0 < value <= bound


def select_cases(event, manifest, pins, scorer_sha256, manifest_sha256, receipt=None):
    require_public_repo(event)
    cases = manifest["cases"]
    if (len(cases) != 18 or len({case["id"] for case in cases}) != 18 or
            any(not re.fullmatch(r"[A-Za-z0-9_-]+", case["id"]) or case["max_error_rate"] != .05
                for case in cases)):
        raise ValueError("Require the unchanged uniquely identified 18-case five-percent corpus")
    canary = next(case for case in cases if case["id"] == "fleurs-en-013")
    if "[qwen17-corpus]" not in event["head_commit"]["message"]:
        return [canary]
    expected = {"schema_version": 1, "runtime_revision": pins["runtime_revision"],
                "weight_sha256": [pin["sha256"] for pin in pins["weights"]["files"]],
                "case_id": canary["id"], "audio_sha256": canary["sha256"],
                "scorer_version": SCORER_VERSION, "scorer_sha256": scorer_sha256,
                "source_manifest_sha256": manifest_sha256, "max_error_rate": .05,
                "raw_errors": 0, "cpu_threads": 4,
                "critical_checks": score_case(canary, canary["reference_raw"])["checks"]}
    if (not isinstance(receipt, dict) or any(receipt.get(k) != v for k, v in expected.items()) or
            any(type(receipt.get(k)) is not int for k in ("schema_version", "scorer_version", "raw_errors", "cpu_threads")) or
            receipt.get("passed") is not True or receipt.get("natural_eos") is not True or
            receipt.get("truncated") is not False or
            not isinstance(receipt.get("admission"), str) or not receipt["admission"] or
            any(check.get("passed") is not True for check in receipt.get("critical_checks", [])) or
            any(not isinstance(receipt.get(k), str) or not re.fullmatch(r"[0-9a-f]{64}", receipt[k])
                for k in ("template_sha256", "response_sha256", "artifact_sha256")) or
            type(receipt.get("server_peak_rss_bytes")) is not int or
            not within(receipt.get("server_peak_rss_bytes"), ADDRESS_SPACE_BYTES) or
            not within(receipt.get("cold_start_seconds"), STARTUP_SECONDS) or
            not within(receipt.get("request_seconds"), REQUEST_SECONDS) or
            not within(receipt.get("outer_elapsed_seconds"), 300)):
        raise ValueError("Full corpus requires the reviewed matching qualified hosted canary receipt")
    return [canary] + [case for case in cases if case["id"] != canary["id"]]


def qualify_result(case, result, pins, build, template_sha256=None):
    if result.get("status") != "ok":
        return False
    if not isinstance(result.get("engine_evidence"), dict):
        raise ValueError("Missing actual per-case engine evidence")
    evidence = result["engine_evidence"]
    research = evidence["research"]
    response = research["http_response"]
    parsed = parse_result(json.dumps(response, ensure_ascii=False, allow_nan=False).encode("utf-8"), case["language"])
    argv = research["server_argv"]
    template = qualify_props(research["server_props"], Path(argv[2]))
    actual_template_sha = hashlib.sha256(template.encode("utf-8")).hexdigest()
    if (result.get("id") != case["id"] or result["raw"] != parsed["raw"] or
            any(evidence.get(k) != parsed[k] for k in ("model_raw", "detected_language")) or
            result["score"] != score_case(case, parsed["raw"], result.get("clean")) or
            build["target"] != "llama-server" or build["cpu_only"] is not True or
            build["source_revision"] != pins["runtime_revision"] or build["weights"] != pins["weights"] or
            research["runtime_revision"] != pins["runtime_revision"] or research["binary"] != build["binary"] or
            argv != server_command(argv[0], argv[2], argv[4], int(argv[10])) or
            type(response["__verbose"]["generation_settings"]["temperature"]) not in (int, float) or
            response["__verbose"]["generation_settings"]["temperature"] != 0 or
            research["embedded_template_sha256"] != actual_template_sha or
            template_sha256 is not None and actual_template_sha != template_sha256 or
            research["address_space_bytes"] != ADDRESS_SPACE_BYTES or
            type(research.get("server_peak_rss_bytes")) is not int or
            not within(research.get("server_peak_rss_bytes"), ADDRESS_SPACE_BYTES) or
            not within(research.get("cold_start_seconds"), STARTUP_SECONDS) or
            not within(research.get("request_seconds"), REQUEST_SECONDS) or
            not within(result.get("elapsed_seconds"), 300)):
        raise ValueError("Fresh per-case protocol, identity, score or resource qualification failed")
    return result["score"]["passed"] is True


def run_cases(cases, root, command, evidence, pins, bindings):
    results = []
    reviewed = bindings.get("reviewed_canary_receipt")
    template_sha = reviewed["template_sha256"] if reviewed is not None else None
    for index, case in enumerate(cases):
        result = {"id": case["id"]}
        cleanup_failed = False
        try:
            result = run_case(case, root, command, timeout=300)
            result["qualified"] = qualify_result(case, result, pins, bindings["build"], template_sha)
        except (OSError, ValueError, KeyError, TypeError, IndexError, subprocess.TimeoutExpired) as error:
            # Exception messages may contain delivery URLs; native failure receipts retain safe diagnostics.
            cleanup_failed = isinstance(error, subprocess.TimeoutExpired)
            result.update(status="cleanup_timeout" if cleanup_failed else "invalid_input_or_output",
                          error_type=type(error).__name__, qualified=False)
        result["audio_sha256"] = case["sha256"]
        results.append(result)
        report = {**bindings, "engine_id": "qwen3-asr-1.7b-q8-cpu-auto-" + ("canary" if index == 0 else "corpus"),
                  "command": command, "execution_order": [r["id"] for r in results], "cases": list(results),
                  "passed": all(r.get("qualified") is True for r in results),
                  "scope": ("One original human canary" if index == 0 else "Original 18-case human corpus") +
                           "; cold per-case research only, not warm latency, Android or microphone proof."}
        save(evidence / (case["id"] + "-score.json"), {**report, "execution_order": [case["id"]], "cases": [result],
                                                       "passed": result["qualified"]})
        if index == 0:
            save(evidence / "canary.json", report)
        if cleanup_failed:
            raise RuntimeError("Owned process group could not be joined; remaining cases held")
        if index == 0 and not report["passed"]:
            return report
    if len(cases) == 18:
        save(evidence / "corpus-results.json", report)
    return report


def main():
    require_host(os.environ, sys.platform, os.geteuid())
    event = load_json(os.environ["GITHUB_EVENT_PATH"])
    require_public_repo(event)
    repository = Path(__file__).resolve().parent.parent
    pins = load_json(repository / "eval/qwen17-pins.json")
    original = load_json(repository / "eval/corpus.json")
    source_manifest_sha = hashlib.sha256((repository / "eval/corpus.json").read_bytes()).hexdigest()
    scorer_sha = hashlib.sha256((repository / "eval/benchmark.py").read_bytes()).hexdigest()
    reviewed = (load_json(repository / "eval/qwen17-canary-receipt.json")
                if "[qwen17-corpus]" in event["head_commit"]["message"] else None)
    cases = select_cases(event, original, pins, scorer_sha, source_manifest_sha, reviewed)
    runner_temp = Path(os.environ["RUNNER_TEMP"]).resolve(strict=True)
    require_capacity(shutil.disk_usage(runner_temp).free)
    resource.setrlimit(resource.RLIMIT_AS, (12 * 1024 ** 3, 12 * 1024 ** 3))
    evidence = runner_temp / "lip-qwen17-evidence"
    evidence.mkdir()  # This job owns a fresh runner; never reuse an uncertain result directory.
    work = Path(tempfile.mkdtemp(prefix="lip-qwen17-", dir=runner_temp))
    source, build = work / "source", work / "build"
    source.mkdir()
    commands = [
        ["git", "init", str(source)],
        ["git", "-C", str(source), "fetch", "--depth=1", "https://github.com/ggml-org/llama.cpp.git",
         pins["runtime_revision"]],
        ["git", "-C", str(source), "checkout", "--detach", "FETCH_HEAD"],
        ["cmake", "-S", str(source), "-B", str(build), "-DCMAKE_BUILD_TYPE=Release",
         "-DBUILD_SHARED_LIBS=OFF", "-DLLAMA_BUILD_TESTS=OFF", "-DLLAMA_BUILD_EXAMPLES=OFF",
         "-DLLAMA_BUILD_APP=OFF", "-DLLAMA_BUILD_UI=OFF", "-DLLAMA_USE_PREBUILT_UI=OFF",
         "-DLLAMA_OPENSSL=OFF", "-DGGML_CUDA=OFF", "-DGGML_VULKAN=OFF", "-DGGML_BLAS=OFF"],
        ["cmake", "--build", str(build), "--target", "llama-server", "-j", "2"],
    ]
    env = {"PATH": os.environ["PATH"], "HOME": str(work), "LANG": "C.UTF-8",
           "GIT_TERMINAL_PROMPT": "0", "OMP_NUM_THREADS": "4"}
    with (evidence / "build.log").open("xb") as log:
        for index, command in enumerate(commands):
            run_logged(command, env, log)
            if index == 2:
                actual_revision = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"],
                                                          env=env, timeout=10, text=True).strip()
                if actual_revision != pins["runtime_revision"]:
                    raise ValueError("Checked-out runtime source revision drifted")
    binary = build / "bin/llama-server"
    receipt = {"source_revision": actual_revision, "target": "llama-server", "cpu_only": True,
               "commands": commands, "github_sha": os.environ["GITHUB_SHA"],
               "run_id": os.environ["GITHUB_RUN_ID"], "run_attempt": os.environ["GITHUB_RUN_ATTEMPT"],
               "host": {key: os.environ.get(key) for key in
                        ["RUNNER_OS", "RUNNER_ARCH", "ImageOS", "ImageVersion", "RUNNER_ENVIRONMENT"]},
               "binary": {"bytes": binary.stat().st_size,
                          "sha256": hashlib.sha256(binary.read_bytes()).hexdigest()},
               "weights": pins["weights"], "scope": "CPU research only; not Android parity or redistribution clearance."}
    receipt["host"]["logical_cpu_count"] = os.cpu_count()
    receipt["host"]["cpu_affinity"] = sorted(os.sched_getaffinity(0))
    try:
        with Path("/proc/cpuinfo").open(encoding="utf-8") as cpuinfo:
            receipt["host"]["cpuinfo_first_processor"] = cpuinfo.read(16384).split("\n\n", 1)[0]
    except OSError as error:
        receipt["host"]["cpuinfo_error_type"] = type(error).__name__
    save(evidence / "build.json", receipt)
    shutil.copyfile(build / "CMakeCache.txt", evidence / "cmake-cache.txt")
    for pin in pins["weights"]["files"]:
        download(pin, work / pin["name"])
    corpus = work / "corpus.json"
    shutil.copyfile(repository / "eval/corpus.json", corpus)
    fetch(corpus)
    admitted = load_json(corpus)
    verify_admission(original, admitted)
    save(evidence / "corpus.json", admitted)
    admitted_by_id = {case["id"]: case for case in admitted["cases"]}
    cases = [admitted_by_id[case["id"]] for case in cases]
    command = [sys.executable, "-B", str(repository / "eval/qwen17_cli.py"),
               "--server", str(binary), "--model", str(work / pins["weights"]["files"][0]["name"]),
               "--projector", str(work / pins["weights"]["files"][1]["name"]),
               "--build-receipt", str(evidence / "build.json"),
               "--diagnostics", str(evidence),
               "--audio", "{audio}", "--language", "{language}", "--output", "{output}"]
    bindings = {"scorer_version": SCORER_VERSION, "scorer_sha256": scorer_sha,
                "source_manifest_sha256": source_manifest_sha,
                "manifest_sha256": hashlib.sha256(corpus.read_bytes()).hexdigest(),
                "adapter_sha256": hashlib.sha256((repository / "eval/qwen17_cli.py").read_bytes()).hexdigest(),
                "build": receipt, "reviewed_canary_receipt": reviewed}
    report = run_cases(cases, work, command, evidence, pins, bindings)
    print(json.dumps({"passed": report["passed"], "cases": len(report["cases"]), "evidence": str(evidence)}))
    raise SystemExit(0 if report["passed"] else 1)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("Qwen17 canary failed: " + type(error).__name__ + "; inspect owned research receipts.",
              file=sys.stderr)
        raise SystemExit(1) from None
