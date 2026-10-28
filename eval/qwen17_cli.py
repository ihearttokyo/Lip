"""Research-only owned CPU llama-server adapter; no downloads or cloud inference."""
import argparse
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import resource
import signal
import socket
import subprocess
import sys
import tempfile
import time

from observe_vad import pcm_samples
from sensevoice_cli import verify_file


MAX_OUTPUT_BYTES = 128 * 1024
STARTUP_SECONDS = 90
REQUEST_SECONDS = 180
ADDRESS_SPACE_BYTES = 12 * 1024 ** 3
REPOSITORY = "ihearttokyo/Lip"
MODEL_ALIAS = "qwen17-research"
LANGUAGES = {"English": "en", "Japanese": "ja", "Chinese": "zh", "None": ""}


def strict_json(data):
    if not isinstance(data, bytes) or len(data) > MAX_OUTPUT_BYTES:
        raise ValueError("JSON exceeds the research output bound")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON key")
            result[key] = value
        return result

    def reject_constant(_):
        raise ValueError("Nonfinite JSON number")

    try:
        result = json.loads(data.decode("utf-8"), object_pairs_hook=unique,
                            parse_constant=reject_constant)
        json.dumps(result, ensure_ascii=False, allow_nan=False).encode("utf-8")
        if not isinstance(result, dict):
            raise ValueError("Expected one JSON object")
        return result
    except RecursionError as error:
        raise ValueError("Excessive JSON nesting") from error


def load_json(path):
    with Path(path).open("rb") as stream:
        return strict_json(stream.read(MAX_OUTPUT_BYTES + 1))


def parse_result(data, expected_language):
    body = strict_json(data)
    choices, verbose = body.get("choices"), body.get("__verbose")
    if (expected_language not in ("en", "ja", "zh") or "error" in body or
            body.get("object") != "chat.completion" or not isinstance(choices, list) or
            len(choices) != 1 or not isinstance(choices[0], dict) or not isinstance(verbose, dict)):
        raise ValueError("Missing or malformed completion")
    choice = choices[0]
    message = choice.get("message")
    if (type(choice.get("index")) is not int or choice["index"] != 0 or
            choice.get("finish_reason") != "stop" or not isinstance(message, dict) or
            set(message) != {"role", "content"} or message["role"] != "assistant" or
            not isinstance(message["content"], str) or verbose.get("stop") is not True or
            verbose.get("stop_type") != "eos" or verbose.get("truncated") is not False or
            type(verbose.get("index")) is not int or verbose["index"] != 0 or
            type(verbose.get("id_slot")) is not int or verbose["id_slot"] != 0 or
            verbose.get("content") != message["content"]):
        raise ValueError("Completion is partial, transformed, or not a plain EOS assistant result")
    model_raw = verbose["content"]
    match = re.fullmatch(r"language (English|Japanese|Chinese|None)(?:\r?\n)*<asr_text>([\s\S]*)", model_raw)
    if not match or model_raw.count("<asr_text>") != 1:
        raise ValueError("Missing, duplicated or malformed AUTO-language header")
    language, text = LANGUAGES[match[1]], match[2]
    if (not language and text != "" or language and (language != expected_language or text == "")):
        raise ValueError("Language mismatch or unproven empty transcription")
    return {"raw": text, "model_raw": model_raw, "detected_language": language}


def request_body(audio):
    pcm_samples(audio)  # Validation only; the original WAVE bytes are sent unchanged.
    return {"model": MODEL_ALIAS, "stream": False, "verbose": True, "n": 1, "max_tokens": 512,
            "temperature": 0, "cache_prompt": False,
            "messages": [{"role": "system", "content": ""}, {"role": "user", "content": [
                {"type": "input_audio", "input_audio": {
                    "data": base64.b64encode(audio).decode("ascii"), "format": "wav"}}]}]}


def require_host(env, platform, uid):
    expected = {"GITHUB_ACTIONS": "true", "RUNNER_OS": "Linux",
                "RUNNER_ENVIRONMENT": "github-hosted", "GITHUB_REPOSITORY": REPOSITORY,
                "GITHUB_REPOSITORY_OWNER": "ihearttokyo", "GITHUB_EVENT_NAME": "push"}
    if platform != "linux" or uid == 0 or any(env.get(key) != value for key, value in expected.items()):
        raise ValueError("Require unprivileged owned GitHub-hosted Linux push")


def require_public_repo(event):
    repo = event.get("repository")
    if (not isinstance(repo, dict) or repo.get("full_name") != REPOSITORY or
            repo.get("private") is not False or repo.get("visibility") != "public" or
            not isinstance(repo.get("owner"), dict) or repo["owner"].get("login") != "ihearttokyo"):
        raise ValueError("Require the owned public GitHub repository")
    commit = event.get("head_commit")
    if (not isinstance(commit, dict) or not isinstance(commit.get("message"), str) or
            "[qwen17-canary]" not in commit["message"]):
        raise ValueError("Require the explicit qwen17-canary push marker")


def verify_build(binary, receipt, revision):
    identity = receipt.get("binary")
    if (receipt.get("source_revision") != revision or receipt.get("target") != "llama-server" or
            receipt.get("cpu_only") is not True or not isinstance(identity, dict) or
            type(identity.get("bytes")) is not int or not 0 < identity["bytes"] <= 128 * 1024 * 1024 or
            not isinstance(identity.get("sha256"), str) or
            not re.fullmatch(r"[0-9a-f]{64}", identity["sha256"])):
        raise ValueError("Missing pinned CPU-only parent build receipt")
    verify_file(Path(binary), identity["bytes"], identity["sha256"])


def verify_weights(model, projector, weights):
    files = weights.get("files") if isinstance(weights, dict) else None
    names = ["Qwen3-ASR-1.7B-Q8_0.gguf", "mmproj-Qwen3-ASR-1.7B-Q8_0.gguf"]
    if not isinstance(files, list) or len(files) != 2:
        raise ValueError("Require exactly the pinned model and projector")
    for path, pin, name in zip((model, projector), files, names):
        if (not isinstance(pin, dict) or pin.get("name") != name or
                type(pin.get("bytes")) is not int or pin["bytes"] <= 0 or
                not isinstance(pin.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", pin["sha256"])):
            raise ValueError("Malformed model/projector pin")
        verify_file(path, pin["bytes"], pin["sha256"])


def command(server, model, projector, port):
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError("Invalid owned loopback port")
    return [str(server), "--model", str(model), "--mmproj", str(projector), "--alias", MODEL_ALIAS,
            "--host", "127.0.0.1", "--port", str(port), "--threads", "4", "--threads-batch", "4",
            "--threads-http", "1", "--parallel", "1", "--gpu-layers", "0", "--no-mmproj-offload",
            "--no-kv-offload", "--no-op-offload", "--jinja", "--ctx-size", "4096", "--batch-size", "512",
            "--ubatch-size", "512", "--n-predict", "512", "--cache-ram", "0"]


def qualify_props(props, model):
    if (type(props.get("total_slots")) is not int or props["total_slots"] != 1 or
            props.get("model_alias") != MODEL_ALIAS or props.get("model_path") != str(model) or
            not isinstance(props.get("modalities"), dict) or props["modalities"].get("audio") is not True or
            not isinstance(props.get("chat_template"), str) or not props["chat_template"] or
            props.get("is_sleeping") is not False or props.get("cors_proxy_enabled") is not False):
        raise ValueError("Actual server metadata did not qualify model/audio/slot/template")
    return props["chat_template"]


def listener_owned(table, socket_links, port):
    if len(table) > MAX_OUTPUT_BYTES:
        raise ValueError("Owned socket metadata exceeded its bound")
    address = "0100007F:" + format(port, "04X")
    for line in table.decode("ascii").splitlines():
        fields = line.split()
        if (len(fields) >= 10 and fields[1] == address and fields[3] == "0A" and
                "socket:[" + fields[9] + "]" in socket_links):
            return True
    return False


def owned_port(pid, port):
    proc = Path("/proc") / str(pid)
    links = set()
    for index, fd in enumerate((proc / "fd").iterdir()):
        if index >= 512:
            raise ValueError("Owned server file descriptor count exceeded its bound")
        try:
            links.add(os.readlink(fd))
        except FileNotFoundError:
            pass
    with (proc / "net/tcp").open("rb") as stream:
        return listener_owned(stream.read(MAX_OUTPUT_BYTES + 1), links, port)


def http_json(port, path, deadline, body=None):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError("Research HTTP deadline exceeded")
    if signal.getitimer(signal.ITIMER_REAL) != (0.0, 0.0):
        raise ValueError("Refuse to replace an existing process deadline")

    def expired(_signal, _frame):
        raise TimeoutError("Research HTTP deadline exceeded")

    # HTTPConnection neither uses proxies nor follows redirects; the destination is fixed.
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=remaining)
    previous_handler = signal.signal(signal.SIGALRM, expired)
    try:
        signal.setitimer(signal.ITIMER_REAL, remaining)
        connection.request("GET" if body is None else "POST", path,
                           body=None if body is None else json.dumps(body).encode("utf-8"),
                           headers={"Content-Type": "application/json", "Connection": "close"})
        response = connection.getresponse()
        if response.status != 200 or response.getheader("Content-Type", "").split(";", 1)[0] != "application/json":
            raise ValueError("Research HTTP did not return one successful JSON response")
        chunks, size = [], 0
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Research HTTP deadline exceeded")
            if connection.sock is not None:
                connection.sock.settimeout(remaining)
            chunk = response.read1(min(65536, MAX_OUTPUT_BYTES + 1 - size))
            if not chunk:
                if response.length not in (None, 0):
                    raise ValueError("Incomplete HTTP response body")
                break
            chunks.append(chunk)
            size += len(chunk)
            if size > MAX_OUTPUT_BYTES:
                raise ValueError("Research HTTP output exceeded its bound")
        return b"".join(chunks)
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous_handler)
        connection.close()


def stop_owned(child):
    if child.poll() is None:
        try:
            child.terminate()
        except ProcessLookupError:
            pass
    try:
        child.wait(timeout=5)
    except subprocess.TimeoutExpired:
        try:
            child.kill()
        except ProcessLookupError:
            pass
        child.wait(timeout=5)


def publish(output, result):
    data = (json.dumps(result, ensure_ascii=False, allow_nan=False) + "\n").encode("utf-8")
    publish_bytes(output, data)


def publish_bytes(output, data):
    if len(data) > MAX_OUTPUT_BYTES:
        raise ValueError("Research transcript exceeds benchmark output bound")
    with tempfile.NamedTemporaryFile(dir=output.parent, prefix=".qwen17-") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
        os.link(stream.name, output)  # Atomic complete publication; never replace an existing output.


def diagnostic_path(directory, audio, kind="native"):
    owned = (Path(os.environ["RUNNER_TEMP"]) / "lip-qwen17-evidence").resolve(strict=True)
    if directory.resolve(strict=True) != owned or not re.fullmatch(r"[A-Za-z0-9_-]+", audio.stem):
        raise ValueError("Require the owned hosted research diagnostic directory and case name")
    suffix = {"native": "native.log", "failure": "failure.json", "response": "response.json"}[kind]
    path = owned / (audio.stem + "-" + suffix)
    if path.exists() or path.is_symlink():
        raise FileExistsError("Refuse to overwrite native diagnostics")
    return path


def run(args):
    args.phase = "input_validation"
    event_path = os.environ.get("GITHUB_EVENT_PATH")
    if not event_path:
        raise ValueError("Missing GitHub event metadata")
    require_public_repo(load_json(event_path))
    if os.getsid(0) != os.getpid() or os.getpgrp() != os.getpid():
        raise ValueError("Require benchmark-owned POSIX session/group")
    if args.output.exists():
        raise ValueError("Refuse to overwrite an existing transcript")
    pins = load_json(Path(__file__).with_name("qwen17-pins.json"))
    receipt = load_json(args.build_receipt)
    server, model, projector = [path.resolve(strict=True) for path in (args.server, args.model, args.projector)]
    verify_build(server, receipt, pins["runtime_revision"])
    verify_weights(model, projector, pins["weights"])
    with args.audio.open("rb") as stream:
        body = request_body(stream.read(4 * 1024 * 1024 + 1))
    _, hard = resource.getrlimit(resource.RLIMIT_AS)
    if hard != resource.RLIM_INFINITY and hard < ADDRESS_SPACE_BYTES:
        raise ValueError("Host address-space limit is below the frozen 12GiB bound")
    resource.setrlimit(resource.RLIMIT_AS, (ADDRESS_SPACE_BYTES, ADDRESS_SPACE_BYTES))
    resource.setrlimit(resource.RLIMIT_FSIZE, (1024 * 1024, 1024 * 1024))
    with socket.socket() as reserved:
        reserved.bind(("127.0.0.1", 0))
        port = reserved.getsockname()[1]
    argv = command(server, model, projector, port)
    started = time.monotonic()
    args.phase = "server_startup"
    # Inherit benchmark's group, not a detached session; pass no credentials or llama overrides.
    with diagnostic_path(args.diagnostics, args.audio).open("xb") as log:
        child = subprocess.Popen(argv, stdout=subprocess.DEVNULL, stderr=log,
                                 env={"PATH": os.defpath, "LANG": "C.UTF-8", "OMP_NUM_THREADS": "4"})
    args.server_pid = child.pid
    try:
        deadline = started + STARTUP_SECONDS
        while time.monotonic() < deadline:
            if child.poll() is not None:
                raise ValueError("Owned server exited during startup")
            try:
                if owned_port(child.pid, port):
                    health = strict_json(http_json(port, "/health", min(deadline, time.monotonic() + 2)))
                    if health == {"status": "ok"}:
                        break
            except (OSError, ValueError, http.client.HTTPException):
                pass
            time.sleep(min(1, max(0, deadline - time.monotonic())))
        else:
            raise TimeoutError("Owned server startup exceeded 90 seconds")
        props = strict_json(http_json(port, "/props", deadline))
        template = qualify_props(props, model)
        if not owned_port(child.pid, port):
            raise ValueError("Owned server lost its verified loopback listener")
        cold_start_seconds = time.monotonic() - started
        requested = time.monotonic()
        args.phase = "inference_and_protocol"
        response = http_json(port, "/v1/chat/completions", requested + REQUEST_SECONDS, body)
        publish_bytes(diagnostic_path(args.diagnostics, args.audio, kind="response"), response)
        result = parse_result(response, args.language)
        if child.poll() is not None:
            raise ValueError("Owned server exited before accepted completion")
        result["research"] = {"runtime_revision": pins["runtime_revision"], "binary": receipt["binary"],
                              "server_argv": argv, "server_props": props,
                              "embedded_template_sha256": hashlib.sha256(template.encode("utf-8")).hexdigest(),
                              "http_response": strict_json(response), "cold_start_seconds": cold_start_seconds,
                              "request_seconds": time.monotonic() - requested,
                              "address_space_bytes": ADDRESS_SPACE_BYTES,
                              "measurement": "Cold per-case server; benchmark timer also includes hashing and shutdown.",
                              "scope": "Research only; no native promotion, quality, memory-fitness or redistribution claim."}
    finally:
        stop_owned(child)
        args.server_exit_code = child.returncode
    result["research"]["server_peak_rss_bytes"] = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss * 1024
    publish(args.output, result)


def main():
    args = None
    started = time.monotonic()
    try:
        require_host(os.environ, sys.platform, os.geteuid())
        parser = argparse.ArgumentParser(description=__doc__)
        for name in ["server", "model", "projector", "build-receipt", "audio", "output", "diagnostics"]:
            parser.add_argument("--" + name, required=True, type=Path)
        parser.add_argument("--language", required=True, choices=["en", "ja", "zh"])
        args = parser.parse_args()
        run(args)
    except (OSError, ValueError, KeyError, http.client.HTTPException, subprocess.TimeoutExpired) as error:
        if args is not None:
            try:
                path = diagnostic_path(args.diagnostics, args.audio, kind="failure")
                publish(path, {"status": "engine_error", "error_type": type(error).__name__, "error": str(error),
                               "phase": getattr(args, "phase", "preflight"),
                               "elapsed_seconds": time.monotonic() - started,
                               "server_pid": getattr(args, "server_pid", None),
                               "server_exit_code": getattr(args, "server_exit_code", None)})
            except (OSError, ValueError, KeyError):
                pass
        print("Qwen17 research run failed; no transcript published: " + str(error), file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
