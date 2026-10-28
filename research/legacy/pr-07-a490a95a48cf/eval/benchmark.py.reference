"""Dependency-free audio scoring and timed, explicitly supplied offline CLI runs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import struct
import sys
import tempfile
import time
import unicodedata

SCORER_VERSION = 2


def stop_owned_group(process):
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait(timeout=5)


def units(text, language):
    text = unicodedata.normalize("NFC", text).casefold().replace("’", "'")
    if language == "en":
        return re.findall(r"[+-]?\d+(?:\.\d+)?|[^\W_]+(?:'[^\W_]+)*|[%$€£¥]", text)
    if language not in ("ja", "zh"):
        raise ValueError("Unsupported scoring language: " + language)
    return [char for i, char in enumerate(text) if not char.isspace() and
            (not unicodedata.category(char).startswith("P") or char in "%" or
             (char in ".:-" and i + 1 < len(text) and text[i + 1].isdigit()
              and (char == "-" or i > 0 and text[i - 1].isdigit())))]


def error_rate(reference, actual, language):
    expected, observed = units(reference, language), units(actual, language)
    # ponytail: quadratic edit-distance time, linear memory; short utterance corpus only.
    previous = list(range(len(observed) + 1))
    for i, expected_unit in enumerate(expected, 1):
        current = [i]
        for j, observed_unit in enumerate(observed, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1,
                               previous[j - 1] + (expected_unit != observed_unit)))
        previous = current
    errors = previous[-1]
    return {"errors": errors, "reference_units": len(expected),
            "rate": errors / len(expected) if expected else (None if errors else 0.0)}


def score_case(case, raw, clean=None):
    if not isinstance(raw, str) or clean is not None and not isinstance(clean, str):
        raise ValueError("Engine output must be text")
    threshold = case["max_error_rate"]
    if not isinstance(threshold, (int, float)) or not 0 <= threshold <= 1:
        raise ValueError("Invalid frozen error threshold")
    result = {"scorer_version": SCORER_VERSION,
              "raw": error_rate(case["reference_raw"], raw, case["language"]),
              "checks": []}
    if "reference_clean" in case:
        result["clean"] = (error_rate(case["reference_clean"], clean, case["language"])
                           if clean is not None else None)
    for check in case.get("checks", []):
        target = check["target"]
        if target not in ("raw", "clean", "both"):
            raise ValueError("Invalid check target")
        for field in ("raw", "clean") if target == "both" else (target,):
            value = raw if field == "raw" else clean
            narrative = (field == "raw" and case.get("origin") == "human_recording" and
                         case.get("source", {}).get("dataset") == "google/fleurs" and
                         check.get("scope") != "literal")
            if narrative and value is not None:
                value = re.sub(r"\s+", " ", value)
            passed = (value is not None and
                      all(re.search(pattern, value) for pattern in check.get("required", [])) and
                      not any(re.search(pattern, value) for pattern in check.get("forbidden", [])))
            result["checks"].append({"name": check["name"], "target": field,
                                     "matching_view": "narrative_whitespace" if narrative else "exact",
                                     "passed": bool(passed)})
    result["passed"] = (all(metric is not None and metric["rate"] is not None and
                            metric["rate"] <= threshold
                            for key, metric in result.items() if key in ("raw", "clean")) and
                        all(check["passed"] for check in result["checks"]))
    return result


def verify_audio(case, root):
    root = Path(root).resolve()
    path = (root / case["audio"]).resolve()
    if not path.is_relative_to(root):
        raise ValueError("Audio path escapes corpus directory")
    if not isinstance(case.get("size_bytes"), int) or not 0 < case["size_bytes"] <= 4 * 1024 * 1024:
        raise ValueError("Audio size is unverified or exceeds the per-clip bound")
    if not re.fullmatch(r"[0-9a-f]{64}", case.get("sha256") or ""):
        raise ValueError("Audio SHA-256 has not been frozen")
    if path.stat().st_size != case["size_bytes"]:
        raise ValueError("Audio size mismatch")
    if hashlib.sha256(path.read_bytes()).hexdigest() != case["sha256"]:
        raise ValueError("Audio SHA-256 mismatch")
    return path


def audio_duration(path):
    data = path.read_bytes()
    if data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise ValueError("Expected RIFF WAVE audio")
    offset, byte_rate, audio_bytes = 12, None, None
    while offset + 8 <= len(data):
        kind, size = data[offset:offset + 4], int.from_bytes(data[offset + 4:offset + 8], "little")
        chunk = data[offset + 8:offset + 8 + size]
        if len(chunk) != size:
            raise ValueError("Truncated WAVE chunk")
        if kind == b"fmt ":
            if size < 16:
                raise ValueError("Truncated WAVE format")
            tag, channels, rate, byte_rate, alignment, bits = struct.unpack("<HHIIHH", chunk[:16])
            if (tag not in (1, 3) or channels < 1 or rate < 1 or bits not in (16, 32) or
                    tag == 3 and bits != 32 or alignment != channels * bits // 8 or
                    byte_rate != rate * alignment):
                raise ValueError("Unsupported or inconsistent WAVE format")
        elif kind == b"data":
            audio_bytes = size
        offset += 8 + size + size % 2
    if byte_rate is None or audio_bytes is None:
        raise ValueError("Missing WAVE format or data")
    return audio_bytes / byte_rate


def run_case(case, root, command, timeout=120):
    if not hasattr(os, "killpg"):
        raise ValueError("Benchmark timeouts require owned POSIX process groups")
    audio = verify_audio(case, root)
    seconds = audio_duration(audio)
    if not seconds or timeout <= 0:
        raise ValueError("Audio duration and timeout must be positive")
    with tempfile.TemporaryDirectory(prefix="lip-eval-") as temp:
        output = Path(temp) / "transcript.txt"
        substitutions = {"audio": str(audio), "language": case["language"],
                         "output": str(output), "output_stem": str(output.with_suffix(""))}
        args = []
        for arg in command:
            for name, value in substitutions.items():
                arg = arg.replace("{" + name + "}", value)
            args.append(arg)
        start = time.perf_counter()
        result = {"id": case["id"], "language": case["language"],
                  "origin": case.get("origin", "fixture"), "audio_seconds": seconds}
        with subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                              start_new_session=True) as completed:
            try:
                completed.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                result.update(status="timeout", elapsed_seconds=time.perf_counter() - start)
                return result
            finally:
                # Reap descendants even when a failed wrapper already exited.
                stop_owned_group(completed)
        elapsed = time.perf_counter() - start
        result.update(elapsed_seconds=elapsed, real_time_factor=elapsed / seconds)
        if completed.returncode:
            result.update(status="engine_error", exit_code=completed.returncode)
            return result
        if not output.is_file():
            result["status"] = "missing_output"
            return result
        if output.stat().st_size > 128 * 1024:
            raise ValueError("Engine transcript exceeds the short-utterance output bound")
        text = output.read_text(encoding="utf-8").strip()
        if text.startswith("{"):
            decoded = json.loads(text)
            raw, clean = decoded["raw"], decoded.get("clean")
            evidence = {key: decoded[key] for key in ("model_raw", "detected_language", "research")
                        if key in decoded}
            if evidence:
                result["engine_evidence"] = evidence
        else:
            raw, clean = text, None
        result.update(status="ok", raw=raw, clean=clean, score=score_case(case, raw, clean))
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--engine-id", required=True, help="Frozen engine/model/config identity")
    parser.add_argument("--case", action="append", default=[])
    split = sys.argv.index("--") if "--" in sys.argv else len(sys.argv)
    args = parser.parse_args(sys.argv[1:split])
    command = sys.argv[split + 1:]
    if not command:
        parser.error("An explicitly supplied offline CLI command is required after --")
    manifest_bytes = args.manifest.read_bytes()
    manifest = json.loads(manifest_bytes)
    if not 1 <= len(manifest["cases"]) <= 32 or len({case["id"] for case in manifest["cases"]}) != len(manifest["cases"]):
        parser.error("Corpus must contain 1–32 uniquely identified cases")
    cases = [case for case in manifest["cases"] if not args.case or case["id"] in args.case]
    if not cases or args.case and set(args.case) != {case["id"] for case in cases}:
        parser.error("Unknown or empty case selection")
    report = {"manifest_sha256": hashlib.sha256(manifest_bytes).hexdigest(),
              "scorer_version": SCORER_VERSION,
              "scorer_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "engine_id": args.engine_id, "command": command, "cases": []}
    for case in cases:
        try:
            report["cases"].append(run_case(case, args.manifest.parent, command, args.timeout))
        except (OSError, ValueError, KeyError) as error:
            report["cases"].append({"id": case["id"], "status": "invalid_input_or_output",
                                    "error": str(error)})
    report["passed"] = all(result.get("score", {}).get("passed", False)
                           for result in report["cases"])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": report["passed"], "cases": len(cases), "report": str(args.output)}))
    raise SystemExit(0 if report["passed"] else 1)


if __name__ == "__main__":
    main()
