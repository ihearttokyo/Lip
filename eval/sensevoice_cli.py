"""Research-only SenseVoice CLI adapter; no downloads or audio uploads."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from observe_vad import pcm_samples


MAX_OUTPUT_BYTES = 128 * 1024
CLI_BYTES = 2051152
CLI_SHA256 = "23888c34222b5b47f4fe59d81ebd1bff1b1a683a01dcdc5ce4152892a23233b1"
MODEL_BYTES = 239233841
MODEL_SHA256 = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"
TOKENS_BYTES = 315894
TOKENS_SHA256 = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"


def parse_result(stdout, stderr, returncode):
    if (returncode != 0 or not isinstance(stdout, bytes) or not isinstance(stderr, bytes)
            or len(stdout) + len(stderr) > MAX_OUTPUT_BYTES):
        raise ValueError("Native process failed or exceeded its output bound")
    logs = stderr.decode("utf-8")
    if re.search(r"\b(error|errors|exception|failed|invalid|terminate|abort|assert)\b|"
                 r"return an empty result|GGML_ASSERT|\[E:onnxruntime", logs, re.IGNORECASE):
        raise ValueError("Native error evidence; transcript rejected")
    for marker in [r"recognizer created in \d+\.\d+ s", r"Started", r"Done!",
                   r"num threads: 4", r"decoding method: greedy_search",
                   r"Elapsed seconds: \d+\.\d+ s",
                   r"Real time factor \(RTF\): \d+\.\d+ / \d+\.\d+ = \d+\.\d+"]:
        if len(re.findall("^" + marker + "$", logs, re.MULTILINE)) != 1:
            raise ValueError("Native completion/configuration evidence is missing")

    def unique_object(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ValueError("Duplicate native JSON key")
            value[key] = item
        return value

    def reject_constant(_):
        raise ValueError("Non-finite native JSON value")

    decoded = json.loads(stdout.decode("utf-8"), object_pairs_hook=unique_object,
                         parse_constant=reject_constant)
    if (not isinstance(decoded, dict) or
            any(not isinstance(decoded.get(key), str)
                for key in ["text", "lang", "emotion", "event"]) or
            not isinstance(decoded.get("tokens"), list) or
            any(not isinstance(token, str) for token in decoded["tokens"]) or
            not isinstance(decoded.get("timestamps"), list) or
            any(type(value) not in (int, float) or not 0 <= value <= 30
                for value in decoded["timestamps"])):
        raise ValueError("Malformed native result")
    return decoded["text"]


def command(cli, model, tokens, audio, language):
    if language not in ("en", "ja", "zh"):
        raise ValueError("Research language must be explicit EN/JA/ZH")
    return [str(cli), "--tokens=" + str(tokens), "--sense-voice-model=" + str(model),
            "--num-threads=4", "--provider=cpu", "--debug=0",
            "--decoding-method=greedy_search", "--sense-voice-language=" + language,
            "--sense-voice-use-itn=0", str(audio)]


def verify_file(path, expected_bytes, expected_sha256):
    if path.stat().st_size != expected_bytes:
        raise ValueError("Research artifact size differs from its pin")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != expected_sha256:
        raise ValueError("Research artifact SHA-256 differs from its pin")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ["cli", "model", "tokens", "audio", "output"]:
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--language", required=True, choices=["en", "ja", "zh"])
    args = parser.parse_args()
    try:
        if args.output.exists():
            raise ValueError("Refuse to overwrite an existing transcript")
        verify_file(args.cli, CLI_BYTES, CLI_SHA256)
        verify_file(args.model, MODEL_BYTES, MODEL_SHA256)
        verify_file(args.tokens, TOKENS_BYTES, TOKENS_SHA256)
        with args.audio.open("rb") as stream:
            audio = stream.read(4 * 1024 * 1024 + 1)
        pcm_samples(audio)  # Existing finite, mono-16k, <=30s PCM validator; no native load.
        with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
            completed = subprocess.run(command(args.cli.resolve(strict=True),
                                       args.model.resolve(strict=True), args.tokens.resolve(strict=True),
                                       args.audio.resolve(strict=True), args.language),
                                       stdout=stdout, stderr=stderr, timeout=100, check=False)
            stdout.seek(0)
            stderr.seek(0)
            raw = parse_result(stdout.read(MAX_OUTPUT_BYTES + 1),
                               stderr.read(MAX_OUTPUT_BYTES + 1), completed.returncode)
        with args.output.open("x", encoding="utf-8") as stream:
            json.dump({"raw": raw}, stream, ensure_ascii=False)
            stream.write("\n")
    except (OSError, ValueError, subprocess.TimeoutExpired):
        print("SenseVoice research run failed; no transcript published.", file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
