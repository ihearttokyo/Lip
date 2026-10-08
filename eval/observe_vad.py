"""Pinned native VAD observation only; never crop speech or discard PCM."""
import argparse
from array import array
import ctypes
import hashlib
import json
import math
import os
from pathlib import Path
import struct
import sys
import time

from benchmark import verify_audio

FRAME_SAMPLES = 512
MAX_SAMPLES = 30 * 16_000
MODEL_BYTES = 885_098
MODEL_SHA256 = "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987"
MODEL_REVISION = "9ffd54a1e1ee413ddf265af9913beaf518d1639b"
MODEL_URL = ("https://huggingface.co/ggml-org/whisper-vad/resolve/" + MODEL_REVISION +
             "/ggml-silero-v6.2.0.bin")
NATIVE_VERSION = "1.9.2"
NATIVE_SOURCE_COMMIT = "306c88f4d1286aec1bf96e544632897886af5501"
DEFAULT_LIBRARY = "/opt/homebrew/opt/whisper-cpp/lib/libwhisper.1.9.2.dylib"
MANAGED_GGML = Path("/opt/homebrew/opt/ggml")
# Exact enum value from pinned ggml/include/ggml.h:645, not a severity-string heuristic.
GGML_LOG_LEVEL_ERROR = 4
GGML_BACKEND_DEVICE_TYPE_CPU = 0  # First enum member in pinned ggml-backend.h.
LOG_CALLBACK = ctypes.CFUNCTYPE(None, ctypes.c_int, ctypes.c_char_p, ctypes.c_void_p)


class ContextParams(ctypes.Structure):
    _fields_ = [("n_threads", ctypes.c_int), ("use_gpu", ctypes.c_bool),
                ("gpu_device", ctypes.c_int)]


def probability_report(sample_count, probabilities, processing_ok, native_error=False):
    if type(sample_count) is not int or not 0 < sample_count <= MAX_SAMPLES:
        raise ValueError("VAD window must contain 1–480000 observed samples")
    if processing_ok is not True or native_error:
        raise ValueError("Native VAD did not complete successfully")
    frame_count = (sample_count + FRAME_SAMPLES - 1) // FRAME_SAMPLES
    if len(probabilities) != frame_count:
        raise ValueError("Native VAD returned incomplete or extra frame probabilities")
    if any(type(value) not in (int, float) or not math.isfinite(value) or
           not 0 <= value <= 1 for value in probabilities):
        raise ValueError("Native VAD probability is not finite within [0,1]")
    values = list(probabilities)
    maximum = max(values)
    argmax = values.index(maximum)
    return {"sample_count": sample_count, "frame_samples": FRAME_SAMPLES,
            "frame_count": frame_count, "probabilities": values,
            "max_probability": maximum, "argmax_frame": argmax,
            "argmax_start_sample": argmax * FRAME_SAMPLES,
            "argmax_end_sample": min((argmax + 1) * FRAME_SAMPLES, sample_count),
            "tail_padding_samples": frame_count * FRAME_SAMPLES - sample_count}


def pcm_samples(data):
    if (not isinstance(data, bytes) or not 44 <= len(data) <= MAX_SAMPLES * 4 + 65_536 or
            data[:4] != b"RIFF" or data[8:12] != b"WAVE" or
            int.from_bytes(data[4:8], "little") != len(data) - 8):
        raise ValueError("Require a bounded, complete RIFF WAVE file")
    offset, fmt, payload = 12, None, None
    while offset + 8 <= len(data):
        kind, size = data[offset:offset + 4], int.from_bytes(data[offset + 4:offset + 8], "little")
        start, end = offset + 8, offset + 8 + size
        if end + size % 2 > len(data):
            raise ValueError("WAVE chunk is truncated")
        if kind == b"fmt ":
            if fmt is not None or size < 16:
                raise ValueError("WAVE format is duplicate or incomplete")
            fmt = struct.unpack_from("<HHIIHH", data, start)
        elif kind == b"data":
            if payload is not None:
                raise ValueError("WAVE data chunk is duplicate")
            payload = memoryview(data)[start:end]
        offset = end + size % 2
    if offset != len(data) or fmt is None or payload is None:
        raise ValueError("WAVE contains incomplete chunks or missing format/data")
    tag, channels, rate, byte_rate, alignment, bits = fmt
    if ((tag, bits) not in ((1, 16), (3, 32)) or channels != 1 or rate != 16_000 or
            alignment != bits // 8 or byte_rate != rate * alignment or
            len(payload) % alignment or not 0 < len(payload) // alignment <= MAX_SAMPLES):
        raise ValueError("Require nonempty mono 16kHz PCM16 or IEEE float32, at most 30 seconds")
    samples = array("h" if tag == 1 else "f")
    samples.frombytes(payload)
    if sys.byteorder != "little":
        samples.byteswap()
    if tag == 1:
        samples = array("f", (value / 32768.0 for value in samples))
    if any(not math.isfinite(value) or not -1 <= value <= 1 for value in samples):
        raise ValueError("WAVE samples must be finite and normalized within [-1,1]")
    return samples


def file_identity(path, byte_limit):
    path = Path(path).resolve(strict=True)
    digest, count = hashlib.sha256(), 0
    with path.open("rb") as stream:
        while block := stream.read(64 * 1024):
            count += len(block)
            if count > byte_limit:
                raise ValueError("Pinned runtime/model file exceeds its size bound")
            digest.update(block)
    return {"path": str(path), "bytes": count, "sha256": digest.hexdigest()}


def initialize_context(library, model_path, backend_directory):
    # Use the registry linked by this whisper library, not a separately loaded ggml instance.
    library.ggml_backend_load_all_from_path.argtypes = [ctypes.c_char_p]
    library.ggml_backend_load_all_from_path.restype = None
    library.ggml_backend_dev_by_type.argtypes = [ctypes.c_int]
    library.ggml_backend_dev_by_type.restype = ctypes.c_void_p
    library.ggml_backend_load_all_from_path(backend_directory)
    cpu_device = library.ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)
    if not cpu_device:
        raise ValueError("Managed GGML did not register a CPU device; refusing VAD initialization")
    context = library.whisper_vad_init_from_file_with_params(model_path, ContextParams(4, False, 0))
    return context, cpu_device


def observe(manifest_paths, model_path, library_path):
    model = file_identity(model_path, MODEL_BYTES)
    if model["bytes"] != MODEL_BYTES or model["sha256"] != MODEL_SHA256:
        raise ValueError("VAD model differs from the frozen size/SHA-256")
    native = file_identity(library_path, 32 * 1024 * 1024)
    if "GGML_BACKEND_PATH" in os.environ:
        raise ValueError("Refuse unrecorded out-of-tree GGML backend override")
    backend_directory = (MANAGED_GGML / "libexec").resolve(strict=True)
    cpu_backends = sorted(backend_directory.glob("libggml-cpu*.so"))
    if not 1 <= len(cpu_backends) <= 32:
        raise ValueError("Managed CPU backend modules are missing or exceed the bound")
    managed_ggml = {
        "scope": "managed_host_not_lip_android",
        "library": file_identity(MANAGED_GGML / "lib/libggml.0.dylib", 32 * 1024 * 1024),
        "base_library": file_identity(MANAGED_GGML / "lib/libggml-base.0.dylib", 32 * 1024 * 1024),
        "backend_directory": str(backend_directory),
        "cpu_backend_candidates": [file_identity(path, 32 * 1024 * 1024) for path in cpu_backends],
    }
    manifests, cases = [], []
    for manifest_path in manifest_paths:
        manifest_path = Path(manifest_path).resolve(strict=True)
        with manifest_path.open("rb") as stream:
            data = stream.read(1024 * 1024 + 1)
        if len(data) > 1024 * 1024:
            raise ValueError("VAD manifest exceeds its size bound")
        manifest = json.loads(data)
        entries = manifest["cases"]
        if (not 1 <= len(entries) <= 32 or
                len({case["id"] for case in entries}) != len(entries)):
            raise ValueError("VAD corpus must contain 1–32 unique case IDs")
        identity = {"path": str(manifest_path), "sha256": hashlib.sha256(data).hexdigest()}
        manifests.append(identity)
        cases.extend((case, manifest_path.parent, identity["sha256"]) for case in entries)
    if not 1 <= len(cases) <= 64:
        raise ValueError("VAD observation is limited to 64 finite clips")

    # No library is loaded during import/tests. Only this explicitly invoked worker path loads it.
    library = ctypes.CDLL(native["path"])
    library.whisper_version.argtypes = []
    library.whisper_version.restype = ctypes.c_char_p
    version = library.whisper_version()
    if version != NATIVE_VERSION.encode("ascii"):
        raise ValueError("Native whisper version differs from the frozen runtime")
    library.whisper_log_set.argtypes = [LOG_CALLBACK, ctypes.c_void_p]
    library.whisper_log_set.restype = None
    library.whisper_vad_init_from_file_with_params.argtypes = [ctypes.c_char_p, ContextParams]
    library.whisper_vad_init_from_file_with_params.restype = ctypes.c_void_p
    library.whisper_vad_detect_speech.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_float), ctypes.c_int]
    library.whisper_vad_detect_speech.restype = ctypes.c_bool
    library.whisper_vad_n_probs.argtypes = [ctypes.c_void_p]
    library.whisper_vad_n_probs.restype = ctypes.c_int
    library.whisper_vad_probs.argtypes = [ctypes.c_void_p]
    library.whisper_vad_probs.restype = ctypes.POINTER(ctypes.c_float)
    library.whisper_vad_free.argtypes = [ctypes.c_void_p]
    library.whisper_vad_free.restype = None

    error_logged = [False]
    def capture_error(level, _message, _user_data):
        if level == GGML_LOG_LEVEL_ERROR:
            error_logged[0] = True
    callback = LOG_CALLBACK(capture_error)
    library.whisper_log_set(callback, None)  # Deliberately retain no native log messages.
    context, results = None, []
    try:
        started = time.perf_counter()
        context, _cpu_device = initialize_context(
            library, model["path"].encode("utf-8"), str(backend_directory).encode("utf-8"))
        init_elapsed = time.perf_counter() - started
        if not context or error_logged[0]:
            raise ValueError("Native VAD context did not initialize successfully")
        for case, root, manifest_sha256 in cases:
            path = verify_audio(case, root)
            # Freeze the bounded payload actually parsed, rather than trusting an earlier path read.
            with path.open("rb") as stream:
                data = stream.read(case["size_bytes"] + 1)
            if len(data) != case["size_bytes"] or hashlib.sha256(data).hexdigest() != case["sha256"]:
                raise ValueError("WAV changed after its frozen source verification")
            samples = pcm_samples(data)
            native_samples = (ctypes.c_float * len(samples))(*samples)
            error_logged[0] = False
            started = time.perf_counter()
            processing_ok = library.whisper_vad_detect_speech(context, native_samples, len(samples))
            elapsed = time.perf_counter() - started
            count = library.whisper_vad_n_probs(context)
            expected_count = (len(samples) + FRAME_SAMPLES - 1) // FRAME_SAMPLES
            if not processing_ok or error_logged[0] or count != expected_count:
                raise ValueError("Native VAD failed or returned incomplete frame probabilities")
            probabilities = library.whisper_vad_probs(context)
            if not probabilities:
                raise ValueError("Native VAD did not return a probability buffer")
            result = probability_report(len(samples), [probabilities[i] for i in range(count)],
                                        processing_ok, error_logged[0])
            result.update(case_id=case["id"], language=case.get("language"),
                          manifest_sha256=manifest_sha256, audio_sha256=case["sha256"],
                          origin=case.get("origin"), elapsed_seconds=elapsed)
            results.append(result)
    finally:
        try:
            if context:
                library.whisper_vad_free(context)
        finally:
            library.whisper_log_set(LOG_CALLBACK(), None)
    if error_logged[0]:
        raise ValueError("Native VAD reported an error while closing its context")
    return {"schema_version": 1, "kind": "vad_observation_only",
            "claim": "No ASR quality, speech cropping or PCM-discard policy is evaluated",
            "native": dict(native, version=NATIVE_VERSION,
                           source_commit_expected=NATIVE_SOURCE_COMMIT),
            "external_ggml": managed_ggml,
            "vad_model": dict(model, revision=MODEL_REVISION, source_url=MODEL_URL, license="MIT"),
            "parameters": {"n_threads": 4, "use_gpu": False, "gpu_device": 0,
                           "sample_rate": 16_000, "mode": "reset_full_window"},
            "initialization_elapsed_seconds": init_elapsed, "manifests": manifests, "cases": results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifests", type=Path, nargs="+")
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--library", type=Path, default=Path(DEFAULT_LIBRARY))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.output.exists():
            raise ValueError("Observation receipt already exists; choose a new output path")
        report = observe(args.manifests, args.model, args.library)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("x", encoding="utf-8") as output:
            json.dump(report, output, ensure_ascii=False, allow_nan=False, indent=2)
            output.write("\n")
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(json.dumps({"kind": "vad_observation_failed", "error_type": type(error).__name__}),
              file=sys.stderr)
        raise SystemExit(1)
    print(json.dumps({"kind": "vad_observation_only", "cases": len(report["cases"]),
                      "report": str(args.output)}))


if __name__ == "__main__":
    main()
