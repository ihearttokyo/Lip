"""One unprivileged hosted CPU research canary; never a production backend."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import resource
import signal
import selectors
import subprocess
import sys
import tempfile
import time
import urllib.request

from benchmark import run_case, stop_owned_group
from fetch_corpus import fetch
from qwen17_cli import load_json, require_host, require_public_repo


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
        stop_owned_group(child)
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


def main():
    require_host(os.environ, sys.platform, os.geteuid())
    require_public_repo(load_json(os.environ["GITHUB_EVENT_PATH"]))
    repository = Path(__file__).resolve().parent.parent
    runner_temp = Path(os.environ["RUNNER_TEMP"]).resolve(strict=True)
    require_capacity(shutil.disk_usage(runner_temp).free)
    resource.setrlimit(resource.RLIMIT_AS, (12 * 1024 ** 3, 12 * 1024 ** 3))
    evidence = runner_temp / "lip-qwen17-evidence"
    evidence.mkdir()  # This job owns a fresh runner; never reuse an uncertain result directory.
    work = Path(tempfile.mkdtemp(prefix="lip-qwen17-", dir=runner_temp))
    pins = load_json(repository / "eval/qwen17-pins.json")
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
    save(evidence / "build.json", receipt)
    shutil.copyfile(build / "CMakeCache.txt", evidence / "cmake-cache.txt")
    for pin in pins["weights"]["files"]:
        download(pin, work / pin["name"])
    corpus = work / "corpus.json"
    shutil.copyfile(repository / "eval/corpus.json", corpus)
    original = load_json(corpus)
    fetch(corpus)
    admitted = load_json(corpus)
    verify_admission(original, admitted)
    save(evidence / "corpus.json", admitted)
    case = next(case for case in admitted["cases"] if case["id"] == "fleurs-en-013")
    command = [sys.executable, "-B", str(repository / "eval/qwen17_cli.py"),
               "--server", str(binary), "--model", str(work / pins["weights"]["files"][0]["name"]),
               "--projector", str(work / pins["weights"]["files"][1]["name"]),
               "--build-receipt", str(evidence / "build.json"),
               "--diagnostics", str(evidence),
               "--audio", "{audio}", "--language", "{language}", "--output", "{output}"]
    result = run_case(case, work, command, timeout=300)
    report = {"engine_id": "qwen3-asr-1.7b-q8-cpu-auto-canary", "command": command,
              "scorer_version": 2, "scorer_sha256": hashlib.sha256((repository / "eval/benchmark.py").read_bytes()).hexdigest(),
              "manifest_sha256": hashlib.sha256(corpus.read_bytes()).hexdigest(), "cases": [result],
              "passed": result.get("score", {}).get("passed", False),
              "scope": "One original human canary; not the full corpus, warm latency, Android or microphone proof."}
    save(evidence / "canary.json", report)
    print(json.dumps({"passed": report["passed"], "status": result["status"], "evidence": str(evidence)}))
    raise SystemExit(0 if report["passed"] else 1)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("Qwen17 canary failed: " + type(error).__name__ + "; inspect owned research receipts.",
              file=sys.stderr)
        raise SystemExit(1) from None
